package app.candidatures.jobs;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Détection des compétences dans le texte des offres. */
public final class Skills {

    /** Mots courants en français : on exige la casse exacte pour éviter les faux positifs. */
    private static final Set<String> CASE_SENSITIVE = Set.of("Vue", "Go", "Excel", "Swift", "Spring", "Rust");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public record SkillPattern(String name, Pattern pattern) {}

    private Skills() {}

    /** Enlève les accents : « Cybersecurité », « CYBERSÉCURITÉ » et « cybersécurité » doivent correspondre. */
    public static String fold(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    }

    public static List<SkillPattern> buildPatterns(Collection<String> skills) {
        List<SkillPattern> patterns = new ArrayList<>();
        for (String s : skills) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            int flags = Pattern.UNICODE_CHARACTER_CLASS;
            if (s.length() > 3 && !CASE_SENSITIVE.contains(s)) {
                flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            }
            // une seule lettre (C, R) : on exclut aussi « R&D », « R.2311 »...
            String after = s.length() == 1 ? "[\\w+#'’&.]" : "[\\w+#'’]";
            patterns.add(new SkillPattern(s, Pattern.compile("(?<![\\w+#.])" + Pattern.quote(fold(s)) + "(?!" + after + ")", flags)));
        }
        return patterns;
    }

    public static List<String> extract(String text, List<SkillPattern> patterns) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String folded = fold(text);
        for (SkillPattern p : patterns) {
            if (p.pattern().matcher(folded).find()) {
                out.add(p.name());
            }
        }
        return out;
    }

    /** Union sans doublons à la casse près (JavaScript / Javascript), triée sans tenir compte de la casse. */
    @SafeVarargs
    public static List<String> merge(Collection<String>... lists) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Collection<String> list : lists) {
            for (String s : list) {
                out.putIfAbsent(s.toLowerCase(Locale.ROOT), s);
            }
        }
        List<String> values = new ArrayList<>(out.values());
        values.sort((a, b) -> a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT)));
        return values;
    }
}
