package app.candidatures.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** Freine les essais de mot de passe : après 8 échecs, 5 minutes d'attente pour ce couple adresse / identifiant. */
@Component
class LoginThrottle {

    private static final int MAX_FAILURES = 8;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private record Entry(int failures, Instant last) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    boolean blocked(String key) {
        Entry e = entries.get(key);
        if (e == null) {
            return false;
        }
        if (e.last().plus(WINDOW).isBefore(Instant.now())) {
            entries.remove(key);
            return false;
        }
        return e.failures() >= MAX_FAILURES;
    }

    void failed(String key) {
        if (entries.size() > 10_000) {
            Instant limit = Instant.now().minus(WINDOW);
            entries.values().removeIf(e -> e.last().isBefore(limit));
        }
        entries.merge(key, new Entry(1, Instant.now()), (old, one) -> new Entry(old.failures() + 1, Instant.now()));
    }

    void succeeded(String key) {
        entries.remove(key);
    }
}
