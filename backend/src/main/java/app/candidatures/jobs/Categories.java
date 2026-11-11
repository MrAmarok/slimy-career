package app.candidatures.jobs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.candidatures.jobs.Skills.SkillPattern;

/**
 * Catégories automatiques (Cybersécurité, DevOps…) à partir de mots-clés configurables. Une offre entre dans une
 * catégorie si son titre contient un des mots-clés, ou si sa description en contient au moins 3 différents.
 */
public final class Categories {

    private static final int DESC_MIN_HITS = 3;

    private Categories() {}

    public static Map<String, List<SkillPattern>> compile(Map<String, List<String>> categories) {
        Map<String, List<SkillPattern>> out = new LinkedHashMap<>();
        categories.forEach((name, keywords) -> out.put(name, Skills.buildPatterns(keywords)));
        return out;
    }

    public static List<String> classify(String title, String description, Map<String, List<SkillPattern>> compiled) {
        List<String> out = new ArrayList<>();
        compiled.forEach((name, patterns) -> {
            if (!Skills.extract(title, patterns).isEmpty() || Skills.extract(description, patterns).size() >= DESC_MIN_HITS) {
                out.add(name);
            }
        });
        return out;
    }
}
