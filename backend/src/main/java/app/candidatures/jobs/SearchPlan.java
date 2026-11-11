package app.candidatures.jobs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Construit la liste des requêtes envoyées aux sites à partir des recherches, tags et compétences. */
public final class SearchPlan {

    public record Query(String query, String tag) {}

    private SearchPlan() {}

    /**
     * Liste de (requête, tag) sans doublons.
     * - recherches complètes : envoyées telles quelles, tag = la recherche ;
     * - tags : « contrat tag » ; un nom de catégorie « DevOps / Cloud » donne « contrat DevOps » et « contrat Cloud » ;
     * - compétences : « contrat compétence », tag = la compétence.
     */
    public static List<Query> build(Collection<String> searches, String contract, Collection<String> tags,
                                    Collection<String> skills, Collection<String> categoryNames) {
        String prefix = contract == null ? "" : contract.strip();
        List<Query> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : searches) {
            if (!s.isBlank()) {
                add(out, seen, s, s.strip());
            }
        }
        for (String t : tags) {
            if (t.isBlank()) {
                continue;
            }
            String[] parts = categoryNames.contains(t) ? t.split("/") : new String[] {t};
            for (String part : parts) {
                if (!part.isBlank()) {
                    add(out, seen, prefix + " " + part.strip(), t.strip());
                }
            }
        }
        for (String s : skills) {
            if (!s.isBlank()) {
                add(out, seen, prefix + " " + s, s.strip());
            }
        }
        return out;
    }

    private static void add(List<Query> out, Set<String> seen, String query, String tag) {
        String q = String.join(" ", query.strip().split("\\s+"));
        if (!q.isEmpty() && seen.add(q.toLowerCase(Locale.ROOT))) {
            out.add(new Query(q, tag));
        }
    }
}
