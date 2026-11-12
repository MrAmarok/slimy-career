package app.candidatures.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.auth.AppUser;
import app.candidatures.jobs.JobService;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private final SettingsService settings;
    private final JobService jobs;

    ConfigController(SettingsService settings, JobService jobs) {
        this.settings = settings;
        this.jobs = jobs;
    }

    @GetMapping
    Map<String, Object> get(@AuthenticationPrincipal AppUser user) {
        return view(settings.load(user.id()));
    }

    @PutMapping
    Map<String, Object> put(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> data) {
        Object before = settings.load(user.id()).get("categories");
        Map<String, Object> saved = settings.save(user.id(), data);
        if (!saved.get("categories").equals(before)) {
            jobs.recategorize(user.id());
        }
        return view(saved);
    }

    private static Map<String, Object> view(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", cfg);
        out.put("available_sites", SettingsService.SITES);
        return out;
    }
}
