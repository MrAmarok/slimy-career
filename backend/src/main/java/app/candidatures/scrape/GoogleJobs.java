package app.candidatures.scrape;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import app.candidatures.common.Json;
import app.candidatures.common.Values;

/**
 * Google Jobs via l'API SerpAPI (Google bloque la lecture directe de ses pages). Fait par le serveur lui-même :
 * c'est la seule source qui demande une clé, et elle ne doit pas être confiée au service de recherche.
 */
@Component
class GoogleJobs {

    /** Erreur à écrire dans le journal de la recherche. */
    static class GoogleException extends Exception {
        GoogleException(String message) {
            super(message);
        }
    }

    private static final String API_URL = "https://serpapi.com/search.json";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Json json;

    GoogleJobs(Json json) {
        this.json = json;
    }

    List<Map<String, Object>> search(String query, String location, int limit, String apiKey) throws GoogleException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new GoogleException("pas de clé SerpAPI : Google est ignoré (voir Paramètres)");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("engine", "google_jobs");
        // la zone va dans la requête : le paramètre « location » de SerpAPI refuse les noms qu'il ne connaît pas
        params.put("q", query + " " + location);
        params.put("hl", "fr");
        params.put("gl", "fr");
        params.put("api_key", apiKey);
        List<Map<String, Object>> out = new ArrayList<>();
        while (out.size() < limit) {
            StringBuilder url = new StringBuilder(API_URL).append('?');
            params.forEach((k, v) -> url.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
            HttpResponse<String> r;
            try {
                r = http.send(HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new GoogleException("SerpAPI injoignable");
            }
            if (r.statusCode() != 200) {
                // le message de SerpAPI est repris sans l'adresse appelée, qui contient la clé
                String detail = Values.str(json.map(r.body()).get("error"));
                throw new GoogleException("erreur SerpAPI " + r.statusCode() + (detail.isEmpty() ? "" : " : " + detail));
            }
            Map<String, Object> data = json.map(r.body());
            List<Object> results = Values.list(data.get("jobs_results"));
            for (Object o : results == null ? List.of() : results) {
                Map<String, Object> j = Values.map(o);
                if (j == null) {
                    continue;
                }
                List<Object> options = Values.list(j.get("apply_options"));
                Map<String, Object> first = options == null || options.isEmpty() ? null : Values.map(options.get(0));
                Object link = first != null ? first.get("link") : j.get("share_link");
                if (link == null || link.toString().isEmpty()) {
                    continue;
                }
                Map<String, Object> extensions = Values.map(j.get("detected_extensions"));
                String via = Values.str(j.get("via"));
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("title", j.get("title"));
                job.put("company", j.get("company_name"));
                job.put("location", j.get("location"));
                job.put("description", j.get("description"));
                job.put("link", link);
                job.put("contract", extensions == null ? null : extensions.get("schedule_type"));
                job.put("source", "google" + (via.isEmpty() ? "" : " (" + (via.startsWith("via ") ? via.substring(4) : via) + ")"));
                out.add(job);
                if (out.size() >= limit) {
                    return out;
                }
            }
            Map<String, Object> pagination = Values.map(data.get("serpapi_pagination"));
            String token = pagination == null ? "" : Values.str(pagination.get("next_page_token"));
            if (token.isEmpty()) {
                break;
            }
            params.put("next_page_token", token);
        }
        return out;
    }
}
