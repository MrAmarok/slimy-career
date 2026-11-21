package app.candidatures.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import app.candidatures.common.ApiException;
import app.candidatures.common.Json;
import app.candidatures.common.SecretBox;
import app.candidatures.common.Values;

/**
 * Réglages de recherche de chaque utilisateur (ce qui était dans config.toml) : zones, recherches, sites,
 * compétences à détecter, catégories automatiques. Les valeurs absentes viennent de default-settings.json.
 */
@Service
public class SettingsService {

    public static final List<String> SITES = List.of("linkedin", "hellowork", "indeed", "google");

    private static final int MAX_TEXT = 200;
    private static final int MAX_ITEMS = 500;
    private static final List<String> TEXT_KEYS = List.of("country", "contract");
    /** Clés d'API : chiffrées en base, jamais renvoyées au navigateur. */
    private static final List<String> SECRET_KEYS = List.of("serpapi_key", "deepl_key");
    private static final String ENCRYPTED = "enc:";
    private static final List<String> INT_KEYS = List.of("results_per_search", "max_age_days", "radius_km", "parallel_searches", "per_site");
    private static final List<String> LIST_KEYS = List.of("searches", "locations", "sites", "skills", "search_tags", "search_skills");

    private final JdbcClient db;
    private final Json json;
    private final String defaults;
    private final String sharedSerpapiKey;
    private final SecretBox secrets;

    public SettingsService(JdbcClient db, Json json, SecretBox secrets, @Value("${app.keys.serpapi}") String serpapiKey) throws IOException {
        this.secrets = secrets;
        this.db = db;
        this.json = json;
        this.sharedSerpapiKey = serpapiKey.strip();
        try (InputStream in = new ClassPathResource("default-settings.json").getInputStream()) {
            this.defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public Map<String, Object> defaults() {
        Map<String, Object> cfg = json.map(defaults);
        SECRET_KEYS.forEach(cfg::remove);
        return cfg;
    }

    /** Réglages tels qu'enregistrés, clés d'API (chiffrées) comprises : ne sort pas de ce service. */
    private Map<String, Object> stored(long userId) {
        Map<String, Object> cfg = defaults();
        db.sql("SELECT data FROM settings WHERE user_id = ?").param(userId).query(String.class).optional()
                .ifPresent(stored -> cfg.putAll(json.map(stored)));
        return cfg;
    }

    /** Réglages de l'utilisateur, sans les clés d'API (voir deeplKey et serpapiKey). */
    public Map<String, Object> load(long userId) {
        Map<String, Object> cfg = stored(userId);
        SECRET_KEYS.forEach(cfg::remove);
        return cfg;
    }

    private String secret(long userId, String key) {
        String value = Values.trimmed(stored(userId).get(key));
        if (value.startsWith(ENCRYPTED)) {
            return secrets.decrypt(value.substring(ENCRYPTED.length()));
        }
        return value;   // valeur enregistrée avant le chiffrement : rechiffrée au prochain enregistrement des réglages
    }

    /** Valide les valeurs reçues, enregistre et retourne les réglages complets. */
    public Map<String, Object> save(long userId, Map<String, Object> data) {
        Map<String, Object> cfg = stored(userId);
        for (String key : SECRET_KEYS) {
            // clé envoyée : remplacée (vide = retirée) ; sinon l'ancienne est gardée, chiffrée si elle ne l'était pas
            String value = data.containsKey(key) ? Values.trimmed(data.get(key)) : Values.trimmed(cfg.get(key));
            if (value.length() > MAX_TEXT + 400) {
                throw ApiException.badRequest("Clé d'API trop longue.");
            }
            if (value.isEmpty()) {
                cfg.remove(key);
            } else {
                cfg.put(key, value.startsWith(ENCRYPTED) && !data.containsKey(key) ? value : ENCRYPTED + secrets.encrypt(value));
            }
        }
        for (String key : TEXT_KEYS) {
            if (data.containsKey(key)) {
                String value = Values.trimmed(data.get(key));
                if (value.length() > MAX_TEXT) {
                    throw ApiException.badRequest("« " + key + " » est trop long.");
                }
                cfg.put(key, value);
            }
        }
        for (String key : INT_KEYS) {
            if (data.containsKey(key)) {
                cfg.put(key, Math.min(1000, Math.max(1, toInt(data.get(key), key))));
            }
        }
        for (String key : LIST_KEYS) {
            if (data.containsKey(key)) {
                cfg.put(key, checkedList(Values.cleanList(data.get(key)), key));
            }
        }
        if (data.containsKey("sites") && !SITES.containsAll(strings(cfg, "sites"))) {
            throw ApiException.badRequest("Site inconnu.");
        }
        if (strings(cfg, "locations").isEmpty()) {
            String country = Values.str(cfg.get("country"));
            cfg.put("locations", new ArrayList<>(List.of(country.isEmpty() ? "France" : country)));
        }
        if (data.containsKey("categories")) {
            Map<String, Object> received = Values.map(data.get("categories"));
            if (received == null) {
                throw ApiException.badRequest("Les catégories doivent être un objet « nom : mots-clés ».");
            }
            Map<String, Object> categories = new LinkedHashMap<>();
            received.forEach((name, keywords) -> {
                List<String> clean = checkedList(Values.cleanList(keywords), "categories");
                if (name.length() > MAX_TEXT) {
                    throw ApiException.badRequest("Nom de catégorie trop long.");
                }
                if (!name.isBlank() && !clean.isEmpty()) {
                    categories.put(name.strip(), clean);
                }
            });
            cfg.put("categories", categories);
        }
        db.sql("INSERT INTO settings (user_id, data) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET data = EXCLUDED.data")
                .params(userId, json.write(cfg)).update();
        SECRET_KEYS.forEach(cfg::remove);
        return cfg;
    }

    private static List<String> checkedList(List<String> values, String key) {
        if (values.size() > MAX_ITEMS || values.stream().anyMatch(v -> v.length() > MAX_TEXT)) {
            throw ApiException.badRequest("« " + key + " » : trop d'éléments ou élément trop long.");
        }
        return values;
    }

    private static int toInt(Object value, String key) {
        try {
            if (value instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(Values.trimmed(value));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("« " + key + " » doit être un nombre entier.");
        }
    }

    // ---------- lecture typée ----------

    public static List<String> strings(Map<String, Object> cfg, String key) {
        return Values.strings(cfg.get(key));
    }

    public static int integer(Map<String, Object> cfg, String key, int fallback) {
        return cfg.get(key) instanceof Number n ? n.intValue() : fallback;
    }

    /** Catégories : nom -> mots-clés, dans l'ordre choisi par l'utilisateur. */
    public static Map<String, List<String>> categories(Map<String, Object> cfg) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        Map<String, Object> raw = Values.map(cfg.get("categories"));
        if (raw != null) {
            raw.forEach((name, keywords) -> out.put(name, Values.strings(keywords)));
        }
        return out;
    }

    /** Clé DeepL de l'utilisateur (chacun a la sienne, enregistrée avec son compte). */
    public String deeplKey(long userId) {
        return secret(userId, "deepl_key");
    }

    public boolean hasOwnSerpapiKey(long userId) {
        return !Values.trimmed(stored(userId).get("serpapi_key")).isEmpty();
    }

    /** Vrai si Google Jobs peut être interrogé pour cet utilisateur (sa clé, ou celle du serveur). */
    public boolean hasSerpapiKey(long userId) {
        return hasOwnSerpapiKey(userId) || !sharedSerpapiKey.isEmpty();
    }

    /** Clé SerpAPI de l'utilisateur, sinon la clé commune du serveur (SERPAPI_KEY). */
    public String serpapiKey(long userId) {
        String own = secret(userId, "serpapi_key");
        return own.isEmpty() ? sharedSerpapiKey : own;
    }
}
