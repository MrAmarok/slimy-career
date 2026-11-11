package app.candidatures.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.common.ApiException;
import app.candidatures.common.Values;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final int MIN_PASSWORD = 8;

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;

    AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts, UserRepository users,
                   PasswordEncoder encoder, LoginThrottle throttle) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.users = users;
        this.encoder = encoder;
        this.throttle = throttle;
    }

    static Map<String, Object> view(AppUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("username", user.username());
        out.put("admin", user.admin());
        return out;
    }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal AppUser user) {
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Non connecté.");
        }
        return view(user);
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        String username = Values.trimmed(body.get("username"));
        String password = Values.str(body.get("password"));
        String client = request.getRemoteAddr() + "|" + username.toLowerCase();
        if (throttle.blocked(client)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Trop de tentatives : réessaie dans quelques minutes.");
        }
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (AuthenticationException e) {
            throttle.failed(client);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Identifiant ou mot de passe incorrect.");
        }
        throttle.succeeded(client);
        if (request.getSession(false) != null) {
            request.changeSessionId(); // nouvelle session à la connexion (fixation de session)
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return view((AppUser) authentication.getPrincipal());
    }

    @PostMapping("/password")
    Map<String, Object> changePassword(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body) {
        AppUser current = users.findById(user.id()).orElseThrow(ApiException::notFound);
        if (!encoder.matches(Values.str(body.get("current")), current.passwordHash())) {
            throw ApiException.badRequest("Le mot de passe actuel est incorrect.");
        }
        users.updatePassword(user.id(), encoder.encode(checkedPassword(body.get("password"))));
        return Map.of("ok", true);
    }

    static String checkedPassword(Object value) {
        String password = Values.str(value);
        if (password.length() < MIN_PASSWORD) {
            throw ApiException.badRequest("Le mot de passe doit faire au moins " + MIN_PASSWORD + " caractères.");
        }
        return password;
    }
}
