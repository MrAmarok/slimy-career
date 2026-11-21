package app.candidatures.scrape;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Recherche d'offres d'un utilisateur (une seule à la fois par utilisateur), à partir de deux sources :
 * - le service de recherche (scraper/, conteneur à part) pour LinkedIn, HelloWork et Indeed. Il ne reçoit que la
 *   liste des recherches à faire : aucun secret, aucun accès à la base ;
 * - Google Jobs, interrogé directement par ce serveur avec la clé SerpAPI.
 * Les offres trouvées sont enregistrées ici (compétences, catégories, pays, dédoublonnage par lien).
 */
@Service
public class ScrapeService {

    private static final Logger log = LoggerFactory.getLogger(ScrapeService.class);
    private static final Pattern STEP = Pattern.compile("^\\[(\\d+)/(\\d+)] \\[(\\w+)] « (.+) » à (.+) \\.\\.\\.$");
    private static final String GOOGLE = "google";
    private static final int LOG_LINES = 300;
    private static final int MAX_DESCRIPTION = 100_000;
    private static final int MAX_STEPS = 400;

    /** Ce qui sera cherché : réglages de l'utilisateur, remplacés par les choix faits dans le panneau de recherche. */
    public record Request(List<String> searches, List<String> searchTags, List<String> searchSkills, List<String> locations,
                          List<String> sites, String contract, List<SearchPlan.Query> queries, Map<String, Object> cfg) {}

    /** Recherche en cours ou terminée d'un utilisateur. */
    private static final class Run {
        final long userId;
        final long startCount;
        final int total;
        final Detectors detectors;
        final String defaultCountry;
        final Deque<String> lines = new ArrayDeque<>();
        int done;
        int added;
        int sources;
        volatile boolean stopped;
        volatile boolean finished;
        volatile InputStream stream;

        Run(long userId, long startCount, int total, Detectors detectors, String defaultCountry) {
            this.userId = userId;
            this.startCount = startCount;
            this.total = total;
            this.detectors = detectors;
            this.defaultCountry = defaultCountry;
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
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    private final SettingsService settings;
    private final JobRepository jobs;
    private final GoogleJobs google;
    private final Json json;
    private final URI scraperUrl;

    public ScrapeService(SettingsService settings, JobRepository jobs, GoogleJobs google, Json json,
                         @Value("${app.scraper.url}") String scraperUrl) {
        this.settings = settings;
        this.jobs = jobs;
        this.google = google;
        this.json = json;
        this.scraperUrl = URI.create(scraperUrl.replaceAll("/+$", "") + "/run");
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
        List<String> values = Values.strings(data.containsKey(key) ? data.get(key) : cfg.get(key));
        if (values.size() > 500 || values.stream().anyMatch(v -> v.length() > 200)) {
            throw ApiException.badRequest("« " + key + " » : trop d'éléments ou élément trop long.");
        }
        return values;
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
        int total = req.queries().size() * req.locations().size() * req.sites().size();
        if (total > MAX_STEPS) {
            throw ApiException.badRequest("Trop de recherches d'un coup (" + total + ", maximum " + MAX_STEPS
                    + ") : réduis le nombre de recherches, de zones ou de sites.");
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
        int limit = SettingsService.integer(cfg, "results_per_search", 25);
        int perSite = SettingsService.integer(cfg, "per_site", 2);
        String country = Values.str(cfg.get("country"));
        List<String> remoteSites = req.sites().stream().filter(s -> !s.equals(GOOGLE)).toList();
        boolean withGoogle = req.sites().contains(GOOGLE);

        Run run = new Run(userId, jobs.count(userId), total, Detectors.of(cfg), country.isEmpty() ? null : country);
        run.sources = (remoteSites.isEmpty() ? 0 : 1) + (withGoogle ? 1 : 0);
        run.add(total + " recherches (max " + perSite + " en même temps par site)");
        runs.put(userId, run);

        if (!remoteSites.isEmpty()) {
            // la demande envoyée au service de recherche : uniquement quoi chercher et où, jamais de clé
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("queries", req.queries().stream().map(q -> Map.of("query", q.query(), "tag", q.tag())).toList());
            input.put("locations", req.locations());
            input.put("sites", remoteSites);
            input.put("limit", limit);
            input.put("max_age_days", SettingsService.integer(cfg, "max_age_days", 30));
            input.put("parallel_searches", SettingsService.integer(cfg, "parallel_searches", 6));
            input.put("per_site", perSite);
            input.put("country", country);
            input.put("radius_km", SettingsService.integer(cfg, "radius_km", 30));
            String body = json.write(input);
            Thread.ofVirtual().name("scrape-" + userId).start(() -> followRemote(run, body));
        }
        if (withGoogle) {
            String key = settings.serpapiKey(userId);
            Thread.ofVirtual().name("scrape-google-" + userId).start(() -> runGoogle(run, req, limit, key));
        }
    }

    /** Lit la réponse du service de recherche au fil de l'eau, jusqu'à sa fin. */
    private void followRemote(Run run, String body) {
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(scraperUrl)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            run.stream = response.body();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                if (response.statusCode() != 200) {
                    String message = reader.readLine();
                    run.add("Le service de recherche a refusé la demande (" + response.statusCode() + (message == null ? "" : " : " + message) + ").");
                    return;
                }
                if (run.stopped) {
                    return;
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    handle(run, line);
                }
            }
        } catch (IOException e) {
            if (!run.stopped) {
                log.warn("Service de recherche injoignable ou interrompu : {}", e.toString());
                run.add("Le service de recherche (LinkedIn, HelloWork, Indeed) est injoignable ou s'est interrompu.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Recherche interrompue", e);
            run.add("La recherche s'est arrêtée sur une erreur interne.");
        } finally {
            sourceDone(run);
        }
    }

    private void handle(Run run, String line) {
        if (line.equals("@@PING")) {
            return;
        }
        if (line.startsWith("@@START ")) {
            Map<String, Object> e = json.map(line.substring(8));
            run.add("  ▶ [" + Values.str(e.get("site")) + "] « " + Values.str(e.get("query")) + " » à " + Values.str(e.get("location")));
        } else if (line.startsWith("@@STEP ")) {
            Map<String, Object> e = json.map(line.substring(7));
            List<Object> found = Values.list(e.get("jobs"));
            complete(run, Values.str(e.get("site")), Values.str(e.get("query")), Values.str(e.get("tag")), Values.str(e.get("location")),
                    found == null ? List.of() : found, e.get("error") == null ? null : Values.str(e.get("error")));
        } else if (line.startsWith("@@EXIT ")) {
            if (!line.equals("@@EXIT 0") && !run.stopped) {
                run.add("Le service de recherche s'est arrêté sur une erreur.");
            }
        } else if (!line.isBlank()) {
            run.add(line.length() > 500 ? line.substring(0, 500) : line.stripTrailing());
        }
    }

    private void runGoogle(Run run, Request req, int limit, String apiKey) {
        try {
            for (SearchPlan.Query q : req.queries()) {
                for (String location : req.locations()) {
                    if (run.stopped) {
                        return;
                    }
                    run.add("  ▶ [" + GOOGLE + "] « " + q.query() + " » à " + location);
                    try {
                        List<Map<String, Object>> found = google.search(q.query(), location, limit, apiKey);
                        complete(run, GOOGLE, q.query(), q.tag(), location, new ArrayList<>(found), null);
                    } catch (GoogleJobs.GoogleException e) {
                        complete(run, GOOGLE, q.query(), q.tag(), location, List.of(), e.getMessage());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("Recherche Google interrompue", e);
            run.add("  erreur google : erreur interne");
        } finally {
            sourceDone(run);
        }
    }

    /** Une recherche (un site, une requête, une zone) est terminée : ligne de progression, puis enregistrement. */
    private void complete(Run run, String site, String query, String tag, String location, List<Object> found, String error) {
        synchronized (run) {
            run.done++;
            // ligne lue par status() pour la barre de progression : index = nombre de recherches terminées
            run.add("[" + run.done + "/" + run.total + "] [" + site + "] « " + query + " » à " + location + " ...");
            if (error != null) {
                run.add("  erreur " + site + " : " + error);
                return;
            }
            try {
                run.added += ingest(run, tag, location, found);
            } catch (RuntimeException e) {
                log.error("Offres non enregistrées", e);
                run.add("  erreur à l'enregistrement des offres");
            }
        }
    }

    private void sourceDone(Run run) {
        synchronized (run) {
            if (--run.sources > 0) {
                return;
            }
            if (run.stopped) {
                run.add("Recherche arrêtée : " + run.added + " nouvelles offres.");
            } else {
                run.add("");
                run.add("Terminé : " + run.added + " nouvelles offres, " + jobs.count(run.userId) + " au total.");
            }
            run.finished = true;
        }
    }

    private int ingest(Run run, String tag, String location, List<Object> found) {
        String country = Geo.normalizeCountry(location);
        if (country == null) {
            country = run.defaultCountry;
        }
        int count = 0;
        int added = 0;
        for (Object o : found) {
            Map<String, Object> j = Values.map(o);
            if (j == null) {
                continue;
            }
            // ce qui vient des sites n'est pas fiable : texte nettoyé et borné, lien web uniquement
            String title = text(j.get("title"), 500);
            String link = text(j.get("link"), 2000);
            if (title == null || !Job.isWebLink(link)) {
                continue;
            }
            count++;
            String description = text(j.get("description"), MAX_DESCRIPTION);
            String place = text(j.get("location"), 500);
            List<String> extra = Values.strings(j.get("extra_skills")).stream().filter(s -> s.length() <= 100).limit(100).toList();
            Job job = new Job(title, text(j.get("company"), 500), place, description, link, text(j.get("source"), 100),
                    text(j.get("contract"), 200), text(j.get("date_posted"), 40), Geo.countryOf(place, country),
                    run.detectors.skills(title, description, extra), List.of(tag), run.detectors.categories(title, description),
                    List.of(location));
            if (jobs.upsert(run.userId, job)) {
                added++;
            }
        }
        run.add("  " + count + " offres, " + added + " nouvelles");
        return added;
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.toString().replace("\u0000", "").strip();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
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
            stop(run);
        }
    }

    /** Coupe la connexion au service de recherche : il arrête alors la recherche de son côté. */
    private static void stop(Run run) {
        run.stopped = true;
        InputStream stream = run.stream;
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // déjà fermée
            }
        }
    }

    @PreDestroy
    void stopAll() {
        runs.values().stream().filter(run -> !run.finished).forEach(ScrapeService::stop);
    }
}
