package app.candidatures.scrape;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.auth.AppUser;
import app.candidatures.common.Values;

@RestController
@RequestMapping("/api/scrape")
public class ScrapeController {

    private final ScrapeService scrape;

    ScrapeController(ScrapeService scrape) {
        this.scrape = scrape;
    }

    /** Aperçu : requêtes qui seront lancées et nombre total de recherches. */
    @PostMapping("/plan")
    Map<String, Object> plan(@AuthenticationPrincipal AppUser user, @RequestBody(required = false) Map<String, Object> data) {
        ScrapeService.Request req = scrape.request(user.id(), data == null ? Map.of() : data);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("queries", req.queries().stream().map(q -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("query", q.query());
            m.put("tag", q.tag());
            return m;
        }).toList());
        out.put("steps", req.queries().size() * req.locations().size() * req.sites().size());
        out.put("locations", req.locations().size());
        out.put("sites", req.sites().size());
        return out;
    }

    @PostMapping
    Map<String, Object> start(@AuthenticationPrincipal AppUser user, @RequestBody(required = false) Map<String, Object> data) {
        Map<String, Object> body = data == null ? Map.of() : data;
        scrape.start(user.id(), scrape.request(user.id(), body), Values.truthy(body.get("remember")));
        return Map.of("running", true);
    }

    @GetMapping
    Map<String, Object> status(@AuthenticationPrincipal AppUser user) {
        return scrape.status(user.id());
    }

    @PostMapping("/stop")
    Map<String, Object> stop(@AuthenticationPrincipal AppUser user) {
        scrape.stop(user.id());
        return Map.of("running", false);
    }
}
