package app.candidatures.account;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.auth.AppUser;
import app.candidatures.common.ApiException;
import app.candidatures.common.Json;
import app.candidatures.common.Values;
import app.candidatures.cv.CvRepository;
import app.candidatures.jobs.JobRepository;
import app.candidatures.jobs.JobService;
import app.candidatures.settings.SettingsService;

/**
 * Sauvegarde des données d'un compte dans un fichier JSON (offres, CV, réglages), et import d'une sauvegarde.
 * Sert aussi à reprendre les données de l'ancienne version (voir tools/export_legacy.py).
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final JobRepository jobs;
    private final CvRepository cvs;
    private final SettingsService settings;
    private final Json json;

    AccountController(JobRepository jobs, CvRepository cvs, SettingsService settings, Json json) {
        this.jobs = jobs;
        this.cvs = cvs;
        this.settings = settings;
        this.json = json;
    }

    @GetMapping("/export")
    ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal AppUser user) {
        Map<String, Object> config = settings.load(user.id());
        config.remove("serpapi_key");   // les clés d'API ne sortent pas dans les sauvegardes
        config.remove("deepl_key");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "candidatures-export");
        out.put("version", 1);
        out.put("config", config);
        out.put("jobs", jobs.findAll(user.id()));
        out.put("cvs", cvs.findAll(user.id()));
        String filename = "candidatures-" + user.username().replaceAll("[^A-Za-z0-9_-]+", "_") + "-" + LocalDate.now() + ".json";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-Filename", filename)
                .body(out);
    }

    /**
     * Ajoute le contenu d'une sauvegarde au compte : les offres dont le lien existe déjà sont laissées telles
     * quelles, les CV absents sont ajoutés, les réglages remplacés s'ils sont présents dans le fichier.
     */
    @PostMapping("/import")
    @Transactional
    Map<String, Object> importData(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> data) {
        if (!"candidatures-export".equals(data.get("format"))) {
            throw ApiException.badRequest("Ce fichier n'est pas une sauvegarde de Candidatures.");
        }
        Map<String, Object> config = Values.map(data.get("config"));
        if (config != null) {
            config.remove("serpapi_key");
            config.remove("deepl_key");
            settings.save(user.id(), config);
        }
        Map<Long, Long> jobIds = new HashMap<>(); 
        int addedJobs = 0;
        int skippedJobs = 0;
        List<Object> importedJobs = Values.list(data.get("jobs"));
        for (Object o : importedJobs == null ? List.of() : importedJobs) {
            Map<String, Object> job = Values.map(o);
            if (job == null || Values.trimmed(job.get("title")).isEmpty()
                    || !app.candidatures.jobs.Job.isStorableLink(Values.trimmed(job.get("link")))) {
                continue;   // sans titre, ou lien qui n'est pas une adresse web
            }
            Map<String, Object> clean = new LinkedHashMap<>();
            for (String key : List.of("title", "company", "location", "description", "link", "source", "contract", "date_posted",
                    "country", "created_at", "updated_at")) {
                Object v = job.get(key);
                clean.put(key, v == null || v.toString().isEmpty() ? null : v.toString().replace("\u0000", ""));
            }
            for (String key : List.of("skills", "tags", "categories", "zones")) {
                clean.put(key, job.get(key));
            }
            clean.put("status", JobService.isStatus(job.get("status")) ? job.get("status") : "a_trier");
            clean.put("notes", Values.str(job.get("notes")));
            Optional<Long> id = jobs.restore(user.id(), clean, v -> json.write(Values.strings(v)));
            Long oldId = Values.asLong(job.get("id"));
            if (id.isPresent()) {
                addedJobs++;
                if (oldId != null) {
                    jobIds.put(oldId, id.get());
                }
            } else {
                skippedJobs++;
                if (oldId != null) {
                    jobs.findByLink(user.id(), (String) clean.get("link")).ifPresent(j -> jobIds.put(oldId, (Long) j.get("id")));
                }
            }
        }
        int addedCvs = 0;
        // un CV déjà présent à l'identique (même nom, même contenu) n'est pas ajouté une deuxième fois
        java.util.Set<String> known = new java.util.HashSet<>();
        cvs.findAll(user.id()).forEach(c -> known.add(c.get("name") + "\n" + json.write(c.get("data"))));
        List<Object> importedCvs = Values.list(data.get("cvs"));
        for (Object o : importedCvs == null ? List.of() : importedCvs) {
            Map<String, Object> cv = Values.map(o);
            Map<String, Object> content = cv == null ? null : Values.map(cv.get("data"));
            if (content == null) {
                continue;
            }
            String name = Values.trimmed(cv.get("name"));
            if (!known.add((name.isEmpty() ? "Mon CV" : name) + "\n" + json.write(app.candidatures.cv.CvModel.withDefaults(content)))) {
                continue;
            }
            Long oldJob = Values.asLong(cv.get("job_id"));
            cvs.restore(user.id(), name.isEmpty() ? "Mon CV" : name, content, oldJob == null ? null : jobIds.get(oldJob),
                    emptyToNull(cv.get("created_at")), emptyToNull(cv.get("updated_at")));
            addedCvs++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs", addedJobs);
        out.put("jobs_skipped", skippedJobs);
        out.put("cvs", addedCvs);
        out.put("config", config != null);
        return out;
    }

    private static String emptyToNull(Object value) {
        return value == null || value.toString().isEmpty() ? null : value.toString();
    }
}
