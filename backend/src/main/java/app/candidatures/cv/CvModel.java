package app.candidatures.cv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structure d'un CV (un objet JSON libre, enregistré tel quel) et ses valeurs par défaut. */
public final class CvModel {

    public static final List<String> SECTIONS = List.of("experience", "projects", "education", "hard_skills", "soft_skills",
            "languages", "certifications", "interests");
    /** Ordre conseillé pour un étudiant ou un stage : la formation d'abord. */
    public static final List<String> DEFAULT_ORDER = List.of("education", "experience", "projects", "hard_skills", "certifications",
            "languages", "soft_skills", "interests");
    public static final List<String> STYLES = List.of("moderne", "tech", "classique");
    public static final String DEFAULT_ACCENT = "#1f4e79";

    private static final List<String> INFO_FIELDS = List.of("first_name", "last_name", "title", "email", "phone", "city", "linkedin",
            "github", "website", "availability", "extra", "summary");

    private CvModel() {}

    public static Map<String, Object> emptyInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        INFO_FIELDS.forEach(f -> info.put(f, ""));
        return info;
    }

    public static Map<String, Object> empty() {
        Map<String, Object> cv = new LinkedHashMap<>();
        cv.put("lang", "fr");
        cv.put("style", "moderne");
        cv.put("accent", DEFAULT_ACCENT);
        cv.put("max_pages", 1);
        cv.put("show_photo", true);
        cv.put("photo", null);
        cv.put("section_order", new ArrayList<>(DEFAULT_ORDER));
        cv.put("info", emptyInfo());
        for (String key : List.of("experience", "projects", "education", "certifications", "hard_skills", "soft_skills", "languages", "interests")) {
            cv.put(key, new ArrayList<>());
        }
        return cv;
    }

    /** CV complété par les valeurs par défaut pour les champs absents. */
    public static Map<String, Object> withDefaults(Map<String, Object> cv) {
        Map<String, Object> out = empty();
        if (cv != null) {
            out.putAll(cv);
        }
        return out;
    }
}
