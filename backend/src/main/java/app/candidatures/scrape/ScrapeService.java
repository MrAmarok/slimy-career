package app.candidatures.scrape;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.candidatures.common.ApiException;
import app.candidatures.common.Json;
import app.candidatures.common.Values;
import app.candidatures.jobs.Geo;
import app.candidatures.jobs.Job;
import app.candidatures.jobs.JobRepository;
import app.candidatures.jobs.JobService.Detectors;
import app.candidatures.jobs.SearchPlan;
import app.candidatures.settings.SettingsService;
import jakarta.annotation.PreDestroy;

/**
 * Lance le script Python de recherche d'offres (scraper/scrape.py) pour un utilisateur, suit sa progression
 * et enregistre les offres qu'il renvoie. Une seule recherche à la fois par utilisateur.
 */
@Service
public class ScrapeService {

    private static final Logger log = LoggerFactory.getLogger(ScrapeService.class);
    private static final Pattern STEP = Pattern.compile("^\\[(\\d+)/(\\d+)] \\[(\\w+)] « (.+) » à (.+) \\.\\.\\.$");
    private static final String JOBS_PREFIX = "@@JOBS ";
    private static final int LOG_LINES = 300;

    /** Ce qui sera cherché : réglages de l'utilisateur, remplacés par les choix faits dans le panneau de recherche. */
    public record Request(List<String> searches, List<String> searchTags, List<String> searchSkills, List<String> locations,
                          List<String> sites, String contract, List<SearchPlan.Query> queries, Map<String, Object> cfg) {}

    /** Recherche en cours ou terminée d'un utilisateur. */
    private static final class Run {
        final Process process;
        final long startCount;
        final Deque<String> lines = new ArrayDeque<>();
        volatile boolean stopped;
        volatile boolean finished;

        Run(Process process, long startCount) {
            this.process = process;
            this.startCount = startCount;
        }

        synchronized void add(String line) {
            if (lines.size() >= LOG_LINES) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        synchronized List<String> snapshot() {
            return new ArrayList<>(lines);
        }
    }

    private final Map<Long, Run> runs = new ConcurrentHashMap<>();
    private final SettingsService settings;
    private final JobRepository jobs;
    private final Json json;
    private final String python;
    private final Path script;

    public ScrapeService(SettingsService settings, JobRepository jobs, Json json,
                         @Value("${app.scraper.python}") String python, @Value("${app.scraper.script}") String script) {
        this.settings = settings;
        this.jobs = jobs;
        this.json = json;
        this.python = python;
        this.script = Path.of(script).toAbsolutePath().normalize();
    }

    public Request request(long userId, Map<String, Object> data) {
        Map<String, Object> cfg = settings.load(userId);
        List<String> locations = pick(data, cfg, "locations");
        if (locations.isEmpty()) {
            locations = SettingsService.strings(cfg, "locations");
        }
        List<String> sites = pick(data, cfg, "sites");
        if (sites.isEmpty()) {
            sites = SettingsService.strings(cfg, "sites");
        }
        if (!SettingsService.SITES.containsAll(sites)) {
            throw ApiException.badRequest("Site inconnu.");
        }
        String contract = Values.trimmed(data.containsKey("contract") ? data.get("contract") : cfg.get("contract"));
        List<String> searches = pick(data, cfg, "searches");
        List<String> tags = pick(data, cfg, "search_tags");
        List<String> skills = pick(data, cfg, "search_skills");
        List<SearchPlan.Query> queries = SearchPlan.build(searches, contract, tags, skills, SettingsService.categories(cfg).keySet());
        return new Request(searches, tags, skills, locations, sites, contract, queries, cfg);
    }

    private static List<String> pick(Map<String, Object> data, Map<String, Object> cfg, String key) {
        return Values.strings(data.containsKey(key) ? data.get(key) : cfg.get(key));
    }

    public boolean running(long userId) {
        Run run = runs.get(userId);
        return run != null && !run.finished;
    }

    public synchronized void start(long userId, Request req, boolean remember) {
        if (running(userId)) {
            throw ApiException.conflict("Une recherche tourne déjà.");
        }
        if (req.queries().isEmpty()) {
            throw ApiException.badRequest("Rien à chercher : ajoute une recherche, un tag ou une compétence.");
        }
        Map<String, Object> cfg = req.cfg();
        if (remember) {
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("searches", req.searches());
            kept.put("search_tags", req.searchTags());
            kept.put("search_skills", req.searchSkills());
            kept.put("locations", req.locations());
            kept.put("sites", req.sites());
            kept.put("contract", req.contract());
            cfg = settings.save(userId, kept);
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("queries", req.queries().stream().map(q -> Map.of("query", q.query(), "tag", q.tag())).toList());
        input.put("locations", req.locations());
        input.put("sites", req.sites());
        input.put("limit", SettingsService.integer(cfg, "results_per_search", 25));
        input.put("max_age_days", SettingsService.integer(cfg, "max_age_days", 30));
        input.put("parallel_searches", SettingsService.integer(cfg, "parallel_searches", 6));
        input.put("per_site", SettingsService.integer(cfg, "per_site", 2));
        input.put("country", Values.str(cfg.get("country")));
        input.put("radius_km", SettingsService.integer(cfg, "radius_km", 30));
        input.put("serpapi_key", settings.serpapiKey(cfg));

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(python, "-u", script.toString())
                    .directory(script.getParent().toFile())
                    .redirectErrorStream(true);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            process = builder.start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(json.write(input).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.error("Impossible de lancer {} {}", python, script, e);
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "Impossible de lancer la recherche : le script Python est introuvable sur le serveur.");
        }
        Run run = new Run(process, jobs.count(userId));
        runs.put(userId, run);
        Detectors detectors = Detectors.of(cfg);
        String country = Values.str(cfg.get("country"));
        Thread.ofVirtual().name("scrape-" + userId).start(() -> follow(userId, run, detectors, country.isEmpty() ? null : country));
    }

    /** Lit la sortie du script jusqu'à sa fin : lignes de suivi dans le journal, offres en base. */
    private void follow(long userId, Run run, Detectors detectors, String defaultCountry) {
        int totalNew = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(run.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(JOBS_PREFIX)) {
                    try {
                        totalNew += ingest(userId, run, json.map(line.substring(JOBS_PREFIX.length())), detectors, defaultCountry);
                    } catch (RuntimeException e) {
                        log.error("Offres non enregistrées", e);
                        run.add("  erreur à l'enregistrement des offres : " + e.getMessage());
                    }
                } else {
                    run.add(line.stripTrailing());
                }
            }
            int code = run.process.waitFor();
            if (run.stopped) {
                run.add("Recherche arrêtée : " + totalNew + " nouvelles offres.");
            } else if (code != 0) {
                run.add("La recherche s'est arrêtée sur une erreur (code " + code + ").");
            } else {
                run.add("");
                run.add("Terminé : " + totalNew + " nouvelles offres, " + jobs.count(userId) + " au total.");
            }
        } catch (IOException e) {
            run.add("Lecture de la recherche interrompue : " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.process.destroy();
            run.finished = true;
        }
    }

    private int ingest(long userId, Run run, Map<String, Object> step, Detectors detectors, String defaultCountry) {
        String tag = Values.str(step.get("tag"));
        String location = Values.str(step.get("location"));
        String country = Geo.normalizeCountry(location);
        if (country == null) {
            country = defaultCountry;
        }
        List<Object> found = Values.list(step.get("jobs"));
        int count = 0;
        int added = 0;
        for (Object o : found == null ? List.of() : found) {
            Map<String, Object> j = Values.map(o);
            if (j == null) {
                continue;
            }
            String title = Values.trimmed(j.get("title")).replace("\u0000", "");
            String link = Values.trimmed(j.get("link")).replace("\u0000", "");
            if (title.isEmpty() || link.isEmpty()) {
                continue;
            }
            count++;
            String description = text(j.get("description"));
            String place = text(j.get("location"));
            Job job = new Job(title, text(j.get("company")), place, description, link, text(j.get("source")), text(j.get("contract")),
                    text(j.get("date_posted")), Geo.countryOf(place, country),
                    detectors.skills(title, description, Values.strings(j.get("extra_skills"))), List.of(tag),
                    detectors.categories(title, description), List.of(location));
            if (jobs.upsert(userId, job)) {
                added++;
            }
        }
        run.add("  " + count + " offres, " + added + " nouvelles");
        return added;
    }

    private static String text(Object value) {
        String s = value == null ? null : value.toString();
        return s == null || s.isEmpty() ? null : s.replace("\u0000", "");
    }

    /** État courant : étape, site, recherche, zone et nombre d'offres ajoutées depuis le lancement. */
    public Map<String, Object> status(long userId) {
        Run run = runs.get(userId);
        List<String> lines = run == null ? List.of() : run.snapshot();
        Map<String, Object> step = null;
        for (int i = lines.size() - 1; i >= 0 && step == null; i--) {
            Matcher m = STEP.matcher(lines.get(i));
            if (m.matches()) {
                step = new LinkedHashMap<>();
                step.put("index", Integer.parseInt(m.group(1)));
                step.put("total", Integer.parseInt(m.group(2)));
                step.put("site", m.group(3));
                step.put("query", m.group(4));
                step.put("location", m.group(5));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", run != null && !run.finished);
        out.put("started", run != null);
        out.put("stopped", run != null && run.stopped);
        out.put("new_jobs", run == null ? 0 : Math.max(0, jobs.count(userId) - run.startCount));
        out.put("step", step);
        out.put("log", lines);
        return out;
    }

    public void stop(long userId) {
        Run run = runs.get(userId);
        if (run != null && !run.finished) {
            run.stopped = true;
            run.process.destroy();
        }
    }

    @PreDestroy
    void stopAll() {
        runs.values().forEach(run -> run.process.destroyForcibly());
    }
}
