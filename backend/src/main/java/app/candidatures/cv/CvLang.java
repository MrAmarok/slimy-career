package app.candidatures.cv;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.candidatures.common.Json;
import app.candidatures.common.Values;
import app.candidatures.cv.pdf.Clean;

/**
 * Correcteur et traduction du texte d'un CV.
 *
 * - correcteur : LanguageTool (orthographe, grammaire, accords), API publique gratuite et sans clé. Pour ne rien
 *   envoyer à l'extérieur, lancer un serveur LanguageTool et mettre son adresse dans LANGUAGETOOL_URL.
 * - traduction en anglais : DeepL (clé d'API gratuite, dans les paramètres de l'utilisateur ou DEEPL_API_KEY).
 *
 * Seuls les textes rédigés sont envoyés (titre, profil, puces…) : ni les coordonnées, ni la photo, ni les liens.
 */
@Service
public class CvLang {

    /** Erreur à montrer telle quelle à l'utilisateur (service injoignable, clé manquante, quota…). */
    public static class LangException extends RuntimeException {
        public LangException(String message) {
            super(message);
        }
    }

    /** Champ rédigé du CV : chemin dans le CV, libellé affiché, texte. */
    private record Field(List<Object> path, String label, Object text) {}

    /** Ligne (= une puce) d'un champ, avec sa position dans le champ. */
    private record Line(List<Object> path, String label, String text, int base) {}

    private record Span(int start, int length, Line line) {}

    private record Seen(List<Object> path, int offset, int length) {}

    /** Texte à traduire et endroit où ranger sa traduction. */
    private record Pending(String text, Consumer<String> store) {}

    private static final int LT_MAX_CHARS = 15000;   // l'API publique refuse les textes de plus de 20 000 caractères
    // Règles sans intérêt pour un CV : espaces insécables avant « : » (retirées à l'export PDF de toute façon),
    // apostrophes typographiques, puces qui commencent par une minuscule.
    private static final String LT_DISABLED = "FRENCH_WHITESPACE,WHITESPACE_RULE,APOS_TYP,UPPERCASE_SENTENCE_START,COMMA_PARENTHESIS_WHITESPACE";
    private static final List<String> ENGLISH = List.of("en-GB", "en-US");
    private static final Pattern BULLET = Pattern.compile("^(\\s*(?:[-•–·]|\\*(?!\\*))\\s*)?(.*)$", Pattern.DOTALL);
    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s/()]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern FIXED = Pattern.compile("(.*?)(\\s*\\(.*\\))?", Pattern.DOTALL);
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern BOLD_TAG = Pattern.compile("</?b>");

    // Formules de CV courtes : traduites par une table fixe plutôt que par la machine (« Courant » ne doit
    // jamais devenir « Current »).
    private static final Map<String, String> LANG_NAMES = Map.ofEntries(
            Map.entry("francais", "French"), Map.entry("anglais", "English"), Map.entry("espagnol", "Spanish"),
            Map.entry("allemand", "German"), Map.entry("italien", "Italian"), Map.entry("portugais", "Portuguese"),
            Map.entry("arabe", "Arabic"), Map.entry("chinois", "Chinese"), Map.entry("mandarin", "Mandarin"),
            Map.entry("japonais", "Japanese"), Map.entry("coreen", "Korean"), Map.entry("russe", "Russian"),
            Map.entry("neerlandais", "Dutch"), Map.entry("polonais", "Polish"), Map.entry("turc", "Turkish"),
            Map.entry("basque", "Basque"), Map.entry("catalan", "Catalan"), Map.entry("occitan", "Occitan"),
            Map.entry("breton", "Breton"), Map.entry("corse", "Corsican"));
    private static final Map<String, String> LANG_LEVELS = Map.ofEntries(
            Map.entry("langue maternelle", "Native"), Map.entry("natif", "Native"), Map.entry("native", "Native"),
            Map.entry("bilingue", "Bilingual"), Map.entry("courant", "Fluent"),
            Map.entry("professionnel", "Professional working proficiency"), Map.entry("avance", "Advanced"),
            Map.entry("intermediaire", "Intermediate"), Map.entry("notions", "Basic"), Map.entry("debutant", "Beginner"),
            Map.entry("scolaire", "Basic"));
    private static final Map<String, String> SKILL_CATEGORIES = Map.of(
            "langages", "Languages", "frameworks", "Frameworks", "bases de donnees", "Databases", "devops / cloud", "DevOps / Cloud",
            "systemes / reseaux", "Systems / Networking", "cybersecurite", "Security", "outils", "Developer Tools",
            "methodes", "Methodologies", "web", "Web");
    private static final Map<String, String> CONTEXTS = new LinkedHashMap<>();

    static {
        CONTEXTS.put("title", "Intitulés de postes et de diplômes sur le CV d'un candidat.");
        CONTEXTS.put("prose", "Texte du CV d'un candidat : profil et puces décrivant ses expériences professionnelles, projets et formations.");
        CONTEXTS.put("list", "Listes courtes sur un CV : compétences comportementales, centres d'intérêt, catégories de compétences.");
        CONTEXTS.put("language", "Langues parlées et niveau de langue, sur un CV.");
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Json json;
    private final String languageToolUrl;

    public CvLang(Json json, @Value("${app.languagetool-url}") String languageToolUrl) {
        this.json = json;
        this.languageToolUrl = languageToolUrl;
    }

    private static List<Object> list(Map<String, Object> cv, String key) {
        List<Object> l = Values.list(cv.get(key));
        return l == null ? List.of() : l;
    }

    private static Map<String, Object> info(Map<String, Object> cv) {
        Map<String, Object> info = Values.map(cv.get("info"));
        return info == null ? new LinkedHashMap<>() : info;
    }

    private static String norm(Object text) {
        return Normalizer.normalize(Values.str(text).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("[^\\x00-\\x7f]", "").strip();
    }

    // ---------- correcteur ----------

    private static List<Field> checkFields(Map<String, Object> cv) {
        List<Field> out = new ArrayList<>();
        Map<String, Object> info = info(cv);
        String[][] infoFields = {{"title", "Titre / poste visé"}, {"summary", "Profil"}, {"availability", "Disponibilité"}, {"extra", "Autres infos"}};
        for (String[] f : infoFields) {
            out.add(new Field(List.of("info", f[0]), f[1], info.get(f[0])));
        }
        Object[][] sections = {
                {"experience", "Expérience", "title", new String[][] {{"title", "intitulé"}, {"description", "missions"}}},
                {"projects", "Projet", "name", new String[][] {{"context", "contexte"}, {"description", "description"}}},
                {"education", "Formation", "degree", new String[][] {{"degree", "diplôme"}, {"description", "détails"}}}};
        for (Object[] s : sections) {
            List<Object> items = list(cv, (String) s[0]);
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = Values.map(items.get(i));
                if (item == null) {
                    continue;
                }
                String who = Values.trimmed(item.get((String) s[2]));
                for (String[] f : (String[][]) s[3]) {
                    out.add(new Field(List.of(s[0], i, f[0]), s[1] + " " + (i + 1) + (who.isEmpty() ? "" : " — " + who) + " : " + f[1],
                            item.get(f[0])));
                }
            }
        }
        String[][] lists = {{"soft_skills", "Compétences comportementales"}, {"interests", "Centres d'intérêt"}};
        for (String[] l : lists) {
            List<Object> values = list(cv, l[0]);
            for (int i = 0; i < values.size(); i++) {
                out.add(new Field(List.of(l[0], i), l[1], values.get(i)));
            }
        }
        return out;
    }

    /** Noms propres et technologies du CV : un mot « inconnu » qui en fait partie n'est pas une faute. */
    private static Set<String> knownWords(Map<String, Object> cv) {
        List<Object> phrases = new ArrayList<>();
        Map<String, Object> info = info(cv);
        for (String k : List.of("first_name", "last_name", "city")) {
            phrases.add(info.get(k));
        }
        Map<String, List<String>> sources = new LinkedHashMap<>();
        sources.put("experience", List.of("company", "location"));
        sources.put("projects", List.of("name", "tech"));
        sources.put("education", List.of("school", "location"));
        sources.put("certifications", List.of("name", "issuer"));
        sources.put("hard_skills", List.of("items"));
        sources.forEach((key, fields) -> {
            for (Object o : list(cv, key)) {
                Map<String, Object> item = Values.map(o);
                if (item == null) {
                    continue;
                }
                for (String field : fields) {
                    Object value = item.get(field);
                    if (value instanceof List<?> l) {
                        phrases.addAll(l);
                    } else {
                        phrases.addAll(List.of(Values.str(value).split(",")));
                    }
                }
            }
        });
        Set<String> words = new HashSet<>();
        for (Object p : phrases) {
            String phrase = Values.trimmed(p).toLowerCase(Locale.ROOT);
            if (!phrase.isEmpty()) {
                words.add(phrase);
                for (String w : WORD_SPLIT.split(phrase)) {
                    if (!w.isEmpty()) {
                        words.add(w);
                    }
                }
            }
        }
        return words;
    }

    /** Vrai si le texte peut être écrit avec la police du PDF. */
    private static boolean printable(String text) {
        return text.codePoints().allMatch(cp -> cp == '\n' || cp == '\t' || Clean.encodable(cp) || cp == 0xa0 || cp == 0xad);
    }

    public static String languageOf(Map<String, Object> cv) {
        if (!"en".equals(cv.get("lang"))) {
            return "fr";
        }
        return ENGLISH.contains(cv.get("lang_variant")) ? (String) cv.get("lang_variant") : "en-US";
    }

    /**
     * Fautes trouvées dans le CV : liste de {path, label, offset, length, bad, message, replacements, kind}.
     * `offset` et `length` sont relatifs au champ, en unités UTF-16 (utilisables tels quels en JavaScript).
     */
    public List<Map<String, Object>> check(Map<String, Object> cv, List<String> ignore) {
        String language = languageOf(cv);
        Set<String> known = knownWords(cv);
        ignore.forEach(w -> known.add(w.strip().toLowerCase(Locale.ROOT)));
        // une ligne (= une puce) par paragraphe, sinon deux puces qui se suivent sont lues comme une seule phrase
        List<Line> lines = new ArrayList<>();
        for (Field field : checkFields(cv)) {
            int base = 0;
            String text = field.text() instanceof String s ? s.replace("\r", "") : "";
            for (String line : text.split("\n", -1)) {
                if (!line.isBlank()) {
                    lines.add(new Line(field.path(), field.label(), line, base));
                }
                base += line.length() + 1;
            }
        }
        List<List<Line>> chunks = new ArrayList<>();
        chunks.add(new ArrayList<>());
        int size = 0;
        for (Line line : lines) {
            if (size > 0 && size + line.text().length() > LT_MAX_CHARS) {
                chunks.add(new ArrayList<>());
                size = 0;
            }
            chunks.get(chunks.size() - 1).add(line);
            size += line.text().length() + 2;
        }

        List<Map<String, Object>> issues = new ArrayList<>();
        List<Seen> seen = new ArrayList<>();
        for (List<Line> chunk : chunks) {
            if (chunk.isEmpty()) {
                continue;
            }
            List<Span> spans = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Line line : chunk) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                spans.add(new Span(text.length(), line.text().length(), line));
                text.append(line.text());
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("text", text.toString());
            params.put("language", language);
            params.put("disabledRules", LT_DISABLED);
            if (!language.equals("fr")) {
                params.put("motherTongue", "fr");   // repère aussi les faux amis d'un francophone
            }
            HttpResponse<String> r;
            try {
                r = http.send(HttpRequest.newBuilder(URI.create(languageToolUrl)).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(params))).build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException | IllegalArgumentException e) {
                throw new LangException("Correcteur injoignable (LanguageTool) : vérifie ta connexion et réessaie.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LangException("Vérification interrompue.");
            }
            if (r.statusCode() == 429) {
                throw new LangException("Trop de vérifications d'affilée : LanguageTool demande d'attendre une minute.");
            }
            if (r.statusCode() != 200) {
                throw new LangException("Le correcteur a répondu une erreur (" + r.statusCode() + ") : " + head(r.body()));
            }
            List<Object> matches = Values.list(json.map(r.body()).get("matches"));
            for (Object o : matches == null ? List.of() : matches) {
                Map<String, Object> m = Values.map(o);
                if (m == null || !(m.get("offset") instanceof Number off) || !(m.get("length") instanceof Number len)) {
                    continue;
                }
                int mOffset = off.intValue();
                int mLength = len.intValue();
                Span span = spans.stream().filter(s -> s.start() <= mOffset && mOffset + mLength <= s.start() + s.length())
                        .findFirst().orElse(null);
                if (span == null || mLength == 0) {
                    continue;
                }
                Line line = span.line();
                String bad = line.text().substring(mOffset - span.start(), mOffset - span.start() + mLength);
                int offset = mOffset - span.start() + line.base();
                List<String> replacements = new ArrayList<>();
                List<Object> proposed = Values.list(m.get("replacements"));
                boolean hadReplacements = proposed != null && !proposed.isEmpty();
                for (Object p : proposed == null ? List.of() : proposed.subList(0, Math.min(5, proposed.size()))) {
                    Map<String, Object> pm = Values.map(p);
                    if (pm != null && pm.get("value") != null) {
                        replacements.add(pm.get("value").toString());
                    }
                }
                Map<String, Object> rule = Values.map(m.get("rule"));
                String issueType = rule == null ? "" : Values.str(rule.get("issueType"));
                String kind = issueType.equals("misspelling") ? "orthographe"
                        : issueType.equals("style") || issueType.equals("typographical") ? "style" : "grammaire";
                if (kind.equals("orthographe") && (known.contains(bad.strip().toLowerCase(Locale.ROOT)) || !hadReplacements)) {
                    continue;   // nom propre ou technologie du CV, ou mot étranger sans proposition
                }
                List<String> usable = replacements.stream().filter(CvLang::printable).toList();
                if (hadReplacements && usable.isEmpty()) {
                    continue;   // correction purement typographique (« 5ᵉ ») que la police du PDF ne sait pas écrire
                }
                if (seen.stream().anyMatch(s -> s.path().equals(line.path()) && s.offset() < offset + mLength && offset < s.offset() + s.length())) {
                    continue;   // deux règles signalent le même passage : garder la première
                }
                seen.add(new Seen(line.path(), offset, mLength));
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("path", line.path());
                issue.put("label", line.label());
                issue.put("offset", offset);
                issue.put("length", mLength);
                issue.put("bad", bad);
                issue.put("kind", kind);
                issue.put("message", Values.str(m.get("message")));
                issue.put("rule", rule == null ? "" : Values.str(rule.get("id")));
                issue.put("replacements", usable);
                issues.add(issue);
            }
        }
        return issues;
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> out.append(out.isEmpty() ? "" : "&").append(URLEncoder.encode(k, StandardCharsets.UTF_8))
                .append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    private static String head(String body) {
        return body == null ? "" : body.substring(0, Math.min(200, body.length()));
    }

    // ---------- traduction ----------

    /** Traduction par table, en gardant une précision entre parenthèses : « Courant (C1) » -> « Fluent (C1) ». */
    private static String fixed(Map<String, String> table, String text) {
        Matcher m = FIXED.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        String found = table.get(norm(m.group(1)));
        return found == null ? null : found + Objects.toString(m.group(2), "");
    }

    private static String toDeepl(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return BOLD.matcher(escaped).replaceAll("<b>$1</b>");
    }

    private static String fromDeepl(String text) {
        return BOLD_TAG.matcher(text).replaceAll(Matcher.quoteReplacement("**"))
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    private List<String> deepl(List<String> texts, String target, String context, String key) {
        String url = key.endsWith(":fx") ? "https://api-free.deepl.com/v2/translate" : "https://api.deepl.com/v2/translate";
        List<String> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += 50) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + 50)).stream().map(CvLang::toDeepl).toList();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", batch);
            body.put("source_lang", "FR");
            body.put("target_lang", target.toUpperCase(Locale.ROOT));
            body.put("context", context);
            body.put("tag_handling", "xml");
            body.put("split_sentences", "nonewlines");
            body.put("preserve_formatting", true);
            body.put("model_type", "prefer_quality_optimized");
            HttpResponse<String> r;
            try {
                r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/json").header("Authorization", "DeepL-Auth-Key " + key)
                        .POST(HttpRequest.BodyPublishers.ofString(json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException | IllegalArgumentException e) {
                throw new LangException("DeepL injoignable : vérifie ta connexion et réessaie.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LangException("Traduction interrompue.");
            }
            switch (r.statusCode()) {
                case 200 -> { }
                case 401, 403 -> throw new LangException("Clé DeepL refusée : vérifie qu'elle est copiée en entier (les clés gratuites finissent par « :fx »).");
                case 456 -> throw new LangException("Quota DeepL du mois épuisé (500 000 caractères sur l'offre gratuite).");
                case 429 -> throw new LangException("DeepL est surchargé : réessaie dans quelques secondes.");
                default -> throw new LangException("DeepL a répondu une erreur (" + r.statusCode() + ") : " + head(r.body()));
            }
            List<Object> translations = Values.list(json.map(r.body()).get("translations"));
            if (translations == null || translations.size() != batch.size()) {
                throw new LangException("Réponse incomplète de DeepL : rien n'a été modifié, réessaie.");
            }
            for (Object t : translations) {
                Map<String, Object> tm = Values.map(t);
                out.add(fromDeepl(tm == null ? "" : Values.str(tm.get("text"))));
            }
        }
        return out;
    }

    /** CV traduit et nombre de caractères envoyés à DeepL. */
    public record Translation(Map<String, Object> cv, int chars) {}

    /**
     * Copie du CV traduite du français vers l'anglais. Ne sont pas traduits : noms, coordonnées, liens,
     * entreprises, écoles, lieux, noms de projets, technologies.
     */
    @SuppressWarnings("unchecked")
    public Translation translate(Map<String, Object> cv, String wantedVariant, String key) {
        if (key == null || key.isBlank()) {
            throw new LangException("Clé d'API DeepL manquante.");
        }
        String variant = ENGLISH.contains(wantedVariant) ? wantedVariant : "en-GB";
        Map<String, Object> out = (Map<String, Object>) Json.deepCopy(cv);
        Map<String, List<Pending>> groups = new LinkedHashMap<>();
        CONTEXTS.keySet().forEach(name -> groups.put(name, new ArrayList<>()));
        List<Runnable> finish = new ArrayList<>();

        Map<String, Object> info = Values.map(out.get("info"));
        if (info != null) {
            add(groups, "title", info, "title", null);
            addLines(groups, finish, info, "summary");
            add(groups, "prose", info, "availability", null);
            add(groups, "prose", info, "extra", null);
        }
        String[][] sections = {{"experience", "title", null}, {"projects", null, "context"}, {"education", "degree", null}};
        for (String[] s : sections) {
            for (Object o : list(out, s[0])) {
                Map<String, Object> item = Values.map(o);
                if (item == null) {
                    continue;
                }
                if (s[1] != null) {
                    add(groups, "title", item, s[1], null);
                }
                if (s[2] != null) {
                    add(groups, "list", item, s[2], null);
                }
                addLines(groups, finish, item, "description");
            }
        }
        for (Object o : list(out, "hard_skills")) {
            Map<String, Object> group = Values.map(o);
            if (group != null) {
                add(groups, "list", group, "category", SKILL_CATEGORIES);
            }
        }
        for (String k : List.of("soft_skills", "interests")) {
            List<Object> values = Values.list(out.get(k));
            if (values != null) {
                for (int i = 0; i < values.size(); i++) {
                    int index = i;
                    if (values.get(i) instanceof String text && !text.isBlank()) {
                        groups.get("list").add(new Pending(text.strip(), t -> values.set(index, t)));
                    }
                }
            }
        }
        for (Object o : list(out, "languages")) {
            Map<String, Object> lang = Values.map(o);
            if (lang != null) {
                add(groups, "language", lang, "name", LANG_NAMES);
                add(groups, "language", lang, "level", LANG_LEVELS);
            }
        }

        int chars = 0;
        for (Map.Entry<String, List<Pending>> group : groups.entrySet()) {
            List<Pending> entries = group.getValue();
            if (entries.isEmpty()) {
                continue;
            }
            List<String> texts = entries.stream().map(Pending::text).toList();
            chars += texts.stream().mapToInt(String::length).sum();
            List<String> translated = deepl(texts, variant, CONTEXTS.get(group.getKey()), key.strip());
            for (int i = 0; i < entries.size(); i++) {
                entries.get(i).store().accept(translated.get(i));
            }
        }
        finish.forEach(Runnable::run);
        out.put("lang", "en");
        out.put("lang_variant", variant);
        return new Translation(out, chars);
    }

    private static void add(Map<String, List<Pending>> groups, String group, Map<String, Object> holder, String key, Map<String, String> table) {
        if (!(holder.get(key) instanceof String text) || text.isBlank()) {
            return;
        }
        String fixed = table == null ? null : fixed(table, text);
        if (fixed != null) {
            holder.put(key, fixed);
        } else {
            groups.get(group).add(new Pending(text.strip(), t -> holder.put(key, t)));
        }
    }

    /** Texte à puces : chaque ligne est traduite à part, sa puce est conservée. */
    private static void addLines(Map<String, List<Pending>> groups, List<Runnable> finish, Map<String, Object> holder, String key) {
        if (!(holder.get(key) instanceof String text) || text.isBlank()) {
            return;
        }
        String[] lines = text.replace("\r", "").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = BULLET.matcher(lines[i]);
            if (!m.matches()) {
                continue;
            }
            String prefix = Objects.toString(m.group(1), "");
            String body = m.group(2);
            if (!body.isBlank()) {
                int index = i;
                groups.get("prose").add(new Pending(body.strip(), t -> lines[index] = prefix + t));
            }
        }
        finish.add(() -> holder.put(key, String.join("\n", lines)));
    }
}
