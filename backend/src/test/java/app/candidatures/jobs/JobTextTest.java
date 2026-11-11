package app.candidatures.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JobTextTest {

    @Test
    void skillsAreDetectedWithoutFalsePositives() {
        var patterns = Skills.buildPatterns(List.of("Python", "C", "C++", "Go", "R", "Node.js", "Cybersécurité"));
        assertThat(Skills.extract("Stage PYTHON / node.js, notions de C++ et de cybersecurite", patterns))
                .containsExactly("Python", "C++", "Node.js", "Cybersécurité");
        assertThat(Skills.extract("Service R&D, on y go, article R.2311", patterns)).isEmpty();
        assertThat(Skills.extract("Développement en C et en Go", patterns)).containsExactly("C", "Go");
    }

    @Test
    void mergeIgnoresCase() {
        assertThat(Skills.merge(List.of("JavaScript", "docker"), List.of("Javascript", "AWS"))).containsExactly("AWS", "docker", "JavaScript");
    }

    @Test
    void categoriesNeedTitleHitOrThreeDescriptionHits() {
        var compiled = Categories.compile(Map.of("DevOps", List.of("DevOps", "Docker", "Kubernetes", "Terraform")));
        assertThat(Categories.classify("Stage DevOps", "", compiled)).containsExactly("DevOps");
        assertThat(Categories.classify("Stage", "Docker et Kubernetes", compiled)).isEmpty();
        assertThat(Categories.classify("Stage", "Docker, Kubernetes, Terraform", compiled)).containsExactly("DevOps");
    }

    @Test
    void geo() {
        assertThat(Geo.countryOf("Lyon, Auvergne-Rhône-Alpes, France", null)).isEqualTo("France");
        assertThat(Geo.countryOf("Lyon - 69", "Belgique")).isEqualTo("France");
        assertThat(Geo.countryOf("Bruxelles", "Belgique")).isEqualTo("Belgique");
        assertThat(Geo.cityOf("Toulouse, Occitanie, France")).isEqualTo("Toulouse");
        assertThat(Geo.cityOf("Lyon - 69")).isEqualTo("Lyon");
        assertThat(Geo.cityOf("Paris 8e")).isEqualTo("Paris");
        assertThat(Geo.cityOf("Paris et périphérie")).isEqualTo("Paris");
        assertThat(Geo.cityOf("France")).isNull();
    }

    @Test
    void searchPlan() {
        var queries = SearchPlan.build(List.of("stage devops", "Stage  DevOps"), "stage", List.of("DevOps / Cloud", "Cyber"),
                List.of("Python"), List.of("DevOps / Cloud"));
        assertThat(queries).extracting(SearchPlan.Query::query)
                .containsExactly("stage devops", "stage Cloud", "stage Cyber", "stage Python");
        assertThat(queries.get(1).tag()).isEqualTo("DevOps / Cloud");
    }
}
