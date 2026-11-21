package app.candidatures.jobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.auth.AppUser;
import app.candidatures.common.ApiException;
import app.candidatures.common.Json;
import app.candidatures.common.Values;
import app.candidatures.jobs.JobService.Detectors;
import app.candidatures.settings.SettingsService;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*:.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_DESCRIPTION = 100_000;
    private static final int MAX_NOTES = 20_000;
    private static final int MAX_FIELD = 500;
    private static final int MAX_LINK = 2_000;
    private static final Comparator<String> IGNORE_CASE = Comparator.comparing(s -> s.toLowerCase(Locale.ROOT));

    private final JobRepository jobs;
    private final JobService service;
    private final SettingsService settings;
    private final Json json;

    JobController(JobRepository jobs, JobService service, SettingsService settings, Json json) {
        this.jobs = jobs;
        this.service = service;
        this.settings = settings;
        this.json = json;
    }

    /** Valeurs présentes dans les offres de l'utilisateur, pour remplir les filtres. */
    @GetMapping("/meta")
    @SuppressWarnings("unchecked")
    Map<String, Object> meta(@AuthenticationPrincipal AppUser user) {
        TreeSet<String> tags = new TreeSet<>();
        TreeSet<String> skills = new TreeSet<>(IGNORE_CASE.thenComparing(Comparator.naturalOrder()));
        TreeSet<String> sources = new TreeSet<>();
        TreeSet<String> cities = new TreeSet<>(IGNORE_CASE.thenComparing(Comparator.naturalOrder()));
        TreeSet<String> countries = new TreeSet<>();
        TreeSet<String> zones = new TreeSet<>(IGNORE_CASE.thenComparing(Comparator.naturalOrder()));
        for (Map<String, Object> job : jobs.findAll(user.id())) {
            tags.addAll((List<String>) job.get("tags"));
            skills.addAll((List<String>) job.get("skills"));
            zones.addAll((List<String>) job.get("zones"));
            String source = (String) job.get("source");
            if (source != null && !source.isEmpty()) {
                sources.add(source.split(" ", -1)[0]);
            }
            if (job.get("city") != null) {
                cities.add((String) job.get("city"));
            }
            String country = (String) job.get("country");
            if (country != null && !country.isEmpty()) {
                countries.add(country);
            }
        }
        List<Map<String, String>> statuses = new ArrayList<>();
        JobService.STATUSES.forEach(e -> {
            Map<String, String> s = new LinkedHashMap<>();
            s.put("key", e.getKey());
            s.put("label", e.getValue());
            statuses.add(s);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statuses", statuses);
        out.put("tags", tags);
        out.put("skills", skills);
        out.put("sources", sources);
        out.put("cities", cities);
        out.put("countries", countries);
        out.put("zones", zones);
        out.put("categories", new ArrayList<>(SettingsService.categories(settings.load(user.id())).keySet()));
        return out;
    }

    @GetMapping("/jobs")
    List<Map<String, Object>> list(@AuthenticationPrincipal AppUser user) {
        return jobs.findAll(user.id());
    }

    /**
     * Offre ajoutée à la main (trouvée ailleurs, candidature spontanée, bouche-à-oreille…).
     * Compétences, catégories et pays sont détectés comme pour les offres trouvées par la recherche.
     */
    @PostMapping("/jobs")
    ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> data) {
        String title = Values.trimmed(data.get("title"));
        if (title.isEmpty()) {
            throw ApiException.badRequest("Le titre du poste est obligatoire.");
        }
        Object status = data.get("status") == null || "".equals(data.get("status")) ? "interesse" : data.get("status");
        if (!JobService.isStatus(status)) {
            throw ApiException.badRequest("Statut inconnu.");
        }
        for (String key : List.of("title", "company", "location", "contract")) {
            limit(data.get(key), MAX_FIELD, "Ce champ");
        }
        limit(data.get("description"), MAX_DESCRIPTION, "La description");
        limit(data.get("notes"), MAX_NOTES, "Les notes");
        String link = Values.trimmed(data.get("link"));
        if (!link.isEmpty() && !HAS_SCHEME.matcher(link).matches()) {
            link = "https://" + link;
        }
        if (!link.isEmpty() && !Job.isWebLink(link)) {
            throw ApiException.badRequest("Le lien doit être une adresse web (http:// ou https://).");
        }
        limit(link, MAX_LINK, "Le lien");
        if (!link.isEmpty()) {
            var same = jobs.findByLink(user.id(), link);
            if (same.isPresent()) {
                String company = Objects.toString(same.get().get("company"), "");
                throw ApiException.conflict("Cette offre est déjà dans le tableau : " + same.get().get("title") + " — "
                        + (company.isEmpty() ? "?" : company));
            }
        } else {
            link = "manuel:" + UUID.randomUUID().toString().replace("-", ""); // lien obligatoire et unique : identifiant interne, jamais affiché
        }
        Map<String, Object> cfg = settings.load(user.id());
        Detectors detectors = Detectors.of(cfg);
        String description = Values.trimmed(data.get("description"));
        String location = emptyToNull(Values.trimmed(data.get("location")));
        Job job = new Job(title, emptyToNull(Values.trimmed(data.get("company"))), location, emptyToNull(description), link, "manuel",
                emptyToNull(Values.trimmed(data.get("contract"))), null,
                Geo.countryOf(location, emptyToNull(Values.str(cfg.get("country")))),
                detectors.skills(title, description, List.of()), List.of(), detectors.categories(title, description), List.of());
        jobs.upsert(user.id(), job);
        jobs.setStatusAndNotes(user.id(), link, (String) status, Values.trimmed(data.get("notes")));
        return ResponseEntity.status(HttpStatus.CREATED).body(jobs.findByLink(user.id(), link).orElseThrow());
    }

    @PatchMapping("/jobs/{id}")
    Map<String, Object> update(@AuthenticationPrincipal AppUser user, @PathVariable long id, @RequestBody Map<String, Object> data) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (data.containsKey("status")) {
            if (!JobService.isStatus(data.get("status"))) {
                throw ApiException.badRequest("Statut inconnu.");
            }
            fields.put("status", data.get("status"));
        }
        if (data.containsKey("notes")) {
            limit(data.get("notes"), MAX_NOTES, "Les notes");
            fields.put("notes", Values.str(data.get("notes")));
        }
        for (String key : List.of("tags", "skills")) {
            if (data.containsKey(key)) {
                if (!(data.get(key) instanceof List<?>)) {
                    throw ApiException.badRequest("« " + key + " » doit être une liste.");
                }
                List<String> values = Values.strings(data.get(key));
                if (values.size() > 200 || values.stream().anyMatch(v -> v.length() > 100)) {
                    throw ApiException.badRequest("« " + key + " » : trop d'éléments ou élément trop long.");
                }
                fields.put(key, json.write(Skills.merge(values)));
            }
        }
        if (fields.isEmpty()) {
            throw ApiException.badRequest("Rien à modifier.");
        }
        if (!jobs.update(user.id(), id, fields)) {
            throw ApiException.notFound();
        }
        return jobs.find(user.id(), id).orElseThrow(ApiException::notFound);
    }

    @PostMapping("/jobs/bulk-status")
    Map<String, Object> bulkStatus(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> data) {
        List<Long> ids = ids(data);
        if (!JobService.isStatus(data.get("status"))) {
            throw ApiException.badRequest("Statut inconnu.");
        }
        return Map.of("updated", jobs.bulkStatus(user.id(), ids, (String) data.get("status")));
    }

    @PostMapping("/jobs/bulk-delete")
    Map<String, Object> bulkDelete(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> data) {
        return Map.of("deleted", jobs.bulkDelete(user.id(), ids(data)));
    }

    @DeleteMapping("/jobs/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal AppUser user, @PathVariable long id) {
        jobs.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/redetect-skills")
    Map<String, Object> redetectSkills(@AuthenticationPrincipal AppUser user) {
        return Map.of("updated", service.redetectSkills(user.id()));
    }

    private static List<Long> ids(Map<String, Object> data) {
        List<Long> out = new ArrayList<>();
        if (data.get("ids") instanceof List<?> list) {
            for (Object o : list) {
                Long id = Values.asLong(o);
                if (id == null) {
                    out.clear();
                    break;
                }
                out.add(id);
            }
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("« ids » doit être une liste d'entiers.");
        }
        return out;
    }

    private static void limit(Object value, int max, String what) {
        if (Values.str(value).length() > max) {
            throw ApiException.badRequest(what + " : texte trop long (" + max + " caractères maximum).");
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
