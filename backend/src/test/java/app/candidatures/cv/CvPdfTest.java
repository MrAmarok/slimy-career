package app.candidatures.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.candidatures.cv.pdf.CvPdf;

class CvPdfTest {

    private static Map<String, Object> sample(String style, int bullets) {
        Map<String, Object> cv = CvModel.empty();
        cv.put("style", style);
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) cv.get("info");
        info.putAll(Map.of("first_name", "Élodie", "last_name", "Durand", "title", "Stage DevOps → 6 mois",
                "email", "elodie@example.org", "phone", "06 00 00 00 00", "city", "Toulouse",
                "linkedin", "https://www.linkedin.com/in/elodie", "summary", "Étudiante en **cybersécurité** & cloud <AWS>."));
        StringBuilder description = new StringBuilder();
        for (int i = 0; i < bullets; i++) {
            description.append("- Mise en place d'une chaîne CI/CD n°").append(i).append(" avec des tests automatisés et un déploiement continu\n");
        }
        Map<String, Object> experience = new LinkedHashMap<>(Map.of("title", "Développeuse", "company", "ACME", "start", "2024-01",
                "current", true, "location", "Toulouse", "description", description.toString()));
        cv.put("experience", new ArrayList<>(List.of(experience)));
        cv.put("hard_skills", List.of(Map.of("category", "Langages", "items", List.of("Python", "Java"))));
        cv.put("languages", List.of(Map.of("name", "Anglais", "level", "Courant (C1)")));
        cv.put("interests", List.of("Escalade", "Photo"));
        return cv;
    }

    @Test
    void everyStyleProducesAReadablePdf() {
        for (String style : CvModel.STYLES) {
            CvPdf.Result result = CvPdf.build(sample(style, 3));
            assertThat(result.pages()).isEqualTo(1);
            assertThat(result.compact()).isZero();
            assertThat(result.filename()).isEqualTo("CV_Elodie_Durand.pdf");
            String text = CvPdf.extractText(result.pdf());
            assertThat(text).contains("Élodie Durand", "Stage DevOps -> 6 mois", "cybersécurité & cloud <AWS>", "linkedin.com/in/elodie",
                    "ACME", "01/2024", "Aujourd'hui", "Langages", "Python, Java", "Courant (C1)", "Escalade, Photo");
            assertThat(text).doesNotContain("**");
        }
    }

    @Test
    void contentIsCompactedToFitOnePageThenOverflows() {
        CvPdf.Result tight = CvPdf.build(sample("moderne", 48));
        assertThat(tight.pages()).isEqualTo(1);
        assertThat(tight.compact()).isGreaterThan(0);
        assertThat(tight.overflow()).isFalse();

        CvPdf.Result tooLong = CvPdf.build(sample("moderne", 200));
        assertThat(tooLong.overflow()).isTrue();
        assertThat(tooLong.compact()).isEqualTo(1.0);

        Map<String, Object> twoPages = sample("moderne", 55);
        twoPages.put("max_pages", 2);
        CvPdf.Result allowed = CvPdf.build(twoPages);
        assertThat(allowed.pages()).isEqualTo(2);
        assertThat(allowed.overflow()).isFalse();
    }

    @Test
    void generatedPdfCanBeImportedBack() throws Exception {
        CvImport.Result imported = CvImport.parsePdf(CvPdf.build(sample("classique", 2)).pdf());
        Map<String, Object> cv = imported.cv();
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) cv.get("info");
        assertThat(info.get("email")).isEqualTo("elodie@example.org");
        assertThat(info.get("first_name")).isEqualTo("Élodie");
        assertThat(info.get("last_name")).isEqualTo("Durand");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> experience = (List<Map<String, Object>>) cv.get("experience");
        assertThat(experience).hasSize(1);
        assertThat(experience.get(0)).containsEntry("title", "Développeuse").containsEntry("company", "ACME")
                .containsEntry("start", "2024-01").containsEntry("current", true);
        assertThat(imported.warnings()).isEmpty();
    }
}
