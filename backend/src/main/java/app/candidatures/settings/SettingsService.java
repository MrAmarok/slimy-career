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
import app.candidatures.common.Values;

/**
 * Réglages de recherche de chaque utilisateur (ce qui était dans config.toml) : zones, recherches, sites,
 * compétences à détecter, catégories automatiques. Les valeurs absentes viennent de default-settings.json.
 */
@Service
public class SettingsService {

    public static final List<String> SITES = List.of("linkedin", "hellowork", "indeed", "google");

    private static final List<String> TEXT_KEYS = List.of("country", "serpapi_key", "deepl_key", "contract");
    private static final List<String> INT_KEYS = List.of("results_per_search", "max_age_days", "radius_km", "parallel_searches", "per_site");
    private static final List<String> LIST_KEYS = List.of("searches", "locations", "sites", "skills", "search_tags", "search_skills");

    private final JdbcClient db;
    private final Json json;
    private final String defaults;
    private final String sharedSerpapiKey;
    private final String sharedDeeplKey;

    public SettingsService(JdbcClient db, Json json, @Value("${app.keys.serpapi}") String serpapiKey,
                           @Value("${app.keys.deepl}") String deeplKey) throws IOException {
        this.db = db;
        this.json = json;
        this.sharedSerpapiKey = serpapiKey.strip();
        this.sharedDeeplKey = deeplKey.strip();
        try (InputStream in = new ClassPathResource("default-settings.json").getInputStream()) {
            this.defaults = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public Map<String, Object> defaults() {
        return json.map(defaults);
    }

    public Map<String, Object> load(long userId) {
        Map<String, Object> cfg = defaults();
        db.sql("SELECT data FROM settings WHERE user_id = ?").param(userId).query(String.class).optional()
                .ifPresent(stored -> cfg.putAll(json.map(stored)));
        return cfg;
    }

    /** Valide les valeurs reçues, enregistre et retourne les réglages complets. */
    public Map<String, Object> save(long userId, Map<String, Object> data) {
        Map<String, Object> cfg = load(userId);
        for (String key : TEXT_KEYS) {
            if (data.containsKey(key)) {
                cfg.put(key, Values.trimmed(data.get(key)));
            }
        }
        for (String key : INT_KEYS) {
            if (data.containsKey(key)) {
                cfg.put(key, Math.max(1, toInt(data.get(key), key)));
            }
        }
        for (String key : LIST_KEYS) {
            if (data.containsKey(key)) {
                cfg.put(key, Values.cleanList(data.get(key)));
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
                List<String> clean = Values.cleanList(keywords);
                if (!name.isBlank() && !clean.isEmpty()) {
                    categories.put(name.strip(), clean);
                }
            });
            cfg.put("categories", categories);
        }
        db.sql("INSERT INTO settings (user_id, data) VALUES (?, ?) ON CONFLICT (user_id) DO UPDATE SET data = EXCLUDED.data")
                .params(userId, json.write(cfg)).update();
        return cfg;
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

    /** Clé DeepL de l'utilisateur, sinon la clé commune du serveur (DEEPL_API_KEY). */
    public String deeplKey(long userId) {
        String own = Values.trimmed(load(userId).get("deepl_key"));
        return own.isEmpty() ? sharedDeeplKey : own;
    }

    /** Clé SerpAPI de l'utilisateur, sinon la clé commune du serveur (SERPAPI_KEY). */
    public String serpapiKey(Map<String, Object> cfg) {
        String own = Values.trimmed(cfg.get("serpapi_key"));
        return own.isEmpty() ? sharedSerpapiKey : own;
    }
}
