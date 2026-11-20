package app.candidatures.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import app.candidatures.common.BodyTooLargeException;
import app.candidatures.common.ClientIp;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limites appliquées à toute l'API :
 * - taille du corps des requêtes (1 Mo par défaut, davantage pour les CV et les imports) ;
 * - nombre de requêtes par minute, par utilisateur connecté (ou par adresse pour les visiteurs), avec des
 *   plafonds plus bas pour les opérations coûteuses (PDF, correcteur, traduction, imports, recherche d'offres).
 */
final class RequestLimitFilter extends OncePerRequestFilter {

    private static final long MB = 1024 * 1024;
    private static final long WINDOW_MS = 60_000;

    /** Plafond pour les requêtes dont le chemin commence par `prefix` (méthode vide = toutes). */
    private record Rule(String method, String prefix, int perMinute, long maxBytes) {
        boolean matches(String m, String path) {
            return (method.isEmpty() || method.equals(m)) && path.startsWith(prefix);
        }
    }

    // la première règle qui correspond s'applique, en plus du plafond général
    private static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/auth/login", 15, 16 * 1024),
            new Rule("", "/api/auth/", 30, 16 * 1024),
            new Rule("", "/api/admin/", 30, 16 * 1024),
            new Rule("POST", "/api/account/import", 6, 30 * MB),
            new Rule("GET", "/api/account/export", 6, MB),
            new Rule("POST", "/api/cvs/import", 6, 21 * MB),
            new Rule("POST", "/api/cv/check", 12, 3 * MB),
            new Rule("POST", "/api/cv/translate", 12, 3 * MB),
            new Rule("POST", "/api/cv/", 120, 3 * MB),          // aperçu PDF en direct, lecture ATS
            new Rule("", "/api/cvs", 240, 3 * MB),             // enregistrement automatique du CV (photo comprise)
            new Rule("POST", "/api/scrape/plan", 120, 64 * 1024),
            new Rule("POST", "/api/scrape/stop", 30, 16 * 1024),
            new Rule("POST", "/api/scrape", 6, 64 * 1024),
            new Rule("POST", "/api/redetect-skills", 6, 16 * 1024),
            new Rule("PUT", "/api/config", 30, MB));
    private static final Rule GENERAL = new Rule("", "/api/", 600, MB);

    private static final class Counter {
        long windowStart;
        int count;
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final ClientIp clientIp;
    private volatile long lastCleanup = System.currentTimeMillis();

    RequestLimitFilter(ClientIp clientIp) {
        this.clientIp = clientIp;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        Rule rule = RULES.stream().filter(r -> r.matches(method, path)).findFirst().orElse(GENERAL);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String who = authentication != null && authentication.getPrincipal() instanceof AppUser user
                ? "u" + user.id() : "ip" + clientIp.of(request);
        if (!allow(who + "|*", GENERAL.perMinute()) || (rule != GENERAL && !allow(who + "|" + rule.method() + rule.prefix(), rule.perMinute()))) {
            reject(response, 429, "Trop de requêtes : réessaie dans une minute.");
            response.setHeader("Retry-After", "60");
            return;
        }

        long limit = rule.maxBytes();
        if (request.getContentLengthLong() > limit) {
            reject(response, 413, "Requête trop volumineuse (" + describe(limit) + " maximum).");
            return;
        }
        boolean hasBody = request.getContentLengthLong() != 0 && !"GET".equals(method);
        chain.doFilter(hasBody ? new LimitedRequest(request, limit) : request, response);
    }

    private boolean allow(String key, int perMinute) {
        long now = System.currentTimeMillis();
        if (now - lastCleanup > WINDOW_MS) {
            lastCleanup = now;
            counters.values().removeIf(c -> now - c.windowStart > WINDOW_MS);
        }
        Counter counter = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (counter) {
            if (now - counter.windowStart > WINDOW_MS) {
                counter.windowStart = now;
                counter.count = 0;
            }
            return ++counter.count <= perMinute;
        }
    }

    private static String describe(long bytes) {
        return bytes >= MB ? (bytes / MB) + " Mo" : (bytes / 1024) + " Ko";
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("text/plain;charset=UTF-8");
        response.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
    }

    /** Requête dont la lecture du corps s'arrête au-delà de la taille autorisée. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                ServletInputStream in = super.getInputStream();
                stream = new ServletInputStream() {
                    private long read;

                    private int counted(int n) throws IOException {
                        if (n > 0 && (read += n) > limit) {
                            throw new BodyTooLargeException();
                        }
                        return n;
                    }

                    @Override
                    public int read() throws IOException {
                        int b = in.read();
                        counted(b < 0 ? 0 : 1);
                        return b;
                    }

                    @Override
                    public int read(byte[] buffer, int offset, int length) throws IOException {
                        return counted(in.read(buffer, offset, length));
                    }

                    @Override
                    public boolean isFinished() {
                        return in.isFinished();
                    }

                    @Override
                    public boolean isReady() {
                        return in.isReady();
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                        in.setReadListener(listener);
                    }
                };
            }
            return stream;
        }

        @Override
        public java.io.BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding() == null ? "UTF-8" : getCharacterEncoding();
            return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), encoding));
        }
    }
}
