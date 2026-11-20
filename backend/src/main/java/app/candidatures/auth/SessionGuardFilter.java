package app.candidatures.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Vérifie à chaque requête que la session correspond toujours à un compte valable : compte supprimé, mot de
 * passe changé, double authentification modifiée ou droits changés -> la session est détruite et la requête
 * est traitée comme non connectée.
 */
final class SessionGuardFilter extends OncePerRequestFilter {

    private final UserRepository users;

    SessionGuardFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser session) {
            AppUser current = users.findById(session.id()).orElse(null);
            if (current == null || current.sessionVersion() != session.sessionVersion() || current.admin() != session.admin()) {
                HttpSession httpSession = request.getSession(false);
                if (httpSession != null) {
                    httpSession.invalidate();
                }
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
