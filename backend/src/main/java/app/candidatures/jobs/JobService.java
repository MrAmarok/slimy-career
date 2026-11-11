package app.candidatures.jobs;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.candidatures.common.Values;
import app.candidatures.jobs.Skills.SkillPattern;
import app.candidatures.settings.SettingsService;

/** Ce qui est déduit du texte d'une offre : compétences, catégories, pays. */
@Service
public class JobService {

    public static final List<Map.Entry<String, String>> STATUSES = List.of(
            Map.entry("a_trier", "À trier"), Map.entry("interesse", "Intéressé"), Map.entry("postule", "Postulé"),
            Map.entry("en_attente", "En attente"), Map.entry("entretien", "Entretien"), Map.entry("refuse", "Refusé"),
            Map.entry("pas_interesse", "Pas intéressé"));

    public static boolean isStatus(Object key) {
        return key instanceof String s && STATUSES.stream().anyMatch(e -> e.getKey().equals(s));
    }

    /** Motifs compilés une fois pour toute une série d'offres. */
    public record Detectors(List<SkillPattern> skills, Map<String, List<SkillPattern>> categories) {

        public static Detectors of(Map<String, Object> cfg) {
            return new Detectors(Skills.buildPatterns(SettingsService.strings(cfg, "skills")),
                    Categories.compile(SettingsService.categories(cfg)));
        }

        public List<String> skills(String title, String description, List<String> extra) {
            return Skills.merge(Skills.extract(title + "\n" + Values.str(description), skills), extra);
        }

        public List<String> categories(String title, String description) {
            return Categories.classify(title, Values.str(description), categories);
        }
    }

    private final JobRepository jobs;
    private final SettingsService settings;

    public JobService(JobRepository jobs, SettingsService settings) {
        this.jobs = jobs;
        this.settings = settings;
    }

    /** Recalcule les catégories automatiques de toutes les offres (après modification des mots-clés). */
    @Transactional
    public void recategorize(long userId) {
        Detectors detectors = Detectors.of(settings.load(userId));
        for (Map<String, Object> job : jobs.findAll(userId)) {
            List<String> categories = detectors.categories((String) job.get("title"), (String) job.get("description"));
            if (!categories.equals(job.get("categories"))) {
                jobs.setCategories((Long) job.get("id"), categories);
            }
        }
    }

    /**
     * Ajoute aux offres existantes les compétences de la liste actuelle trouvées dans leur texte
     * (n'enlève rien, pour garder les modifications manuelles). Retourne le nombre d'offres modifiées.
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public int redetectSkills(long userId) {
        Detectors detectors = Detectors.of(settings.load(userId));
        int changed = 0;
        for (Map<String, Object> job : jobs.findAll(userId)) {
            List<String> current = (List<String>) job.get("skills");
            List<String> merged = detectors.skills((String) job.get("title"), (String) job.get("description"), current);
            if (merged.size() != current.size()) {
                jobs.setSkills((Long) job.get("id"), merged);
                changed++;
            }
        }
        return changed;
    }
}
