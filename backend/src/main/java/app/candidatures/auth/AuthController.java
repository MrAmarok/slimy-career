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
import app.candidatures.common.ClientIp;
import app.candidatures.common.SecretBox;
import app.candidatures.common.Values;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 200;
    /** Statut renvoyé quand le mot de passe est bon mais qu'il faut (encore) le code de l'application. */
    private static final HttpStatus TOTP_REQUIRED = HttpStatus.PRECONDITION_REQUIRED;
    private static final String PENDING_TOTP = "totp.pending";
    private static final String ISSUER = "Candidatures";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final LoginThrottle throttle;
    private final ClientIp clientIp;
    private final SecretBox secrets;

    AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts, UserRepository users,
                   PasswordEncoder encoder, LoginThrottle throttle, ClientIp clientIp, SecretBox secrets) {
        this.authenticationManager = authenticationManager;
        this.contexts = contexts;
        this.users = users;
        this.encoder = encoder;
        this.throttle = throttle;
        this.clientIp = clientIp;
        this.secrets = secrets;
    }

    static Map<String, Object> view(AppUser user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.id());
        out.put("username", user.username());
        out.put("admin", user.admin());
        out.put("totp", user.totp());
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
        String code = Values.trimmed(body.get("code"));
        if (username.length() > 100 || password.length() > MAX_PASSWORD) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Identifiant ou mot de passe incorrect.");
        }
        String client = clientIp.of(request) + "|" + username.toLowerCase();
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
        AppUser user = (AppUser) authentication.getPrincipal();
        if (user.totp()) {
            if (code.isEmpty()) {
                throw new ApiException(TOTP_REQUIRED, "Saisis le code à 6 chiffres de ton application d'authentification.");
            }
            if (!acceptCode(user.id(), code)) {
                throttle.failed(client);
                throw new ApiException(TOTP_REQUIRED, "Code incorrect ou expiré.");
            }
        }
        throttle.succeeded(client);
        open(authentication, request, response);
        return view(user);
    }

    /** Ouvre la session : nouvel identifiant de session (fixation de session), puis enregistrement du contexte. */
    private void open(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
    }

    /** Après un changement qui invalide les sessions du compte : garde celle-ci ouverte, avec le compte à jour. */
    private AppUser keepThisSession(long userId, HttpServletRequest request, HttpServletResponse response) {
        AppUser fresh = users.findById(userId).orElseThrow(ApiException::notFound);
        open(UsernamePasswordAuthenticationToken.authenticated(fresh, null, fresh.getAuthorities()), request, response);
        return fresh;
    }

    private boolean acceptCode(long userId, String code) {
        UserRepository.Totp totp = users.totp(userId).orElse(null);
        if (totp == null) {
            return false;
        }
        long step = Totp.verify(secrets.decrypt(totp.encryptedSecret()), code, totp.lastStep());
        return step >= 0 && users.useTotpStep(userId, step);
    }

    private AppUser checkedPasswordOf(AppUser user, Object password) {
        AppUser current = users.findById(user.id()).orElseThrow(ApiException::notFound);
        String given = Values.str(password);
        if (given.length() > MAX_PASSWORD || !encoder.matches(given, current.passwordHash())) {
            throw ApiException.badRequest("Le mot de passe actuel est incorrect.");
        }
        return current;
    }

    /** Change le mot de passe : toutes les sessions du compte sont fermées, celle-ci comprise. */
    @PostMapping("/password")
    Map<String, Object> changePassword(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        checkedPasswordOf(user, body.get("current"));
        users.updatePassword(user.id(), encoder.encode(checkedPassword(body.get("password"))));
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return Map.of("ok", true);
    }

    // ---------- double authentification ----------

    /** Première étape : prépare un secret (pas encore actif) et renvoie le QR code à scanner. */
    @PostMapping("/totp/setup")
    Map<String, Object> totpSetup(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        AppUser current = checkedPasswordOf(user, body.get("password"));
        if (current.totp()) {
            throw ApiException.conflict("La double authentification est déjà activée.");
        }
        secrets.encrypt("test");   // échoue tout de suite si APP_SECRET_KEY manque
        String secret = Totp.newSecret();
        request.getSession().setAttribute(PENDING_TOTP, secret);
        String uri = Totp.uri(ISSUER, current.username(), secret);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secret", secret);
        out.put("uri", uri);
        out.put("qr", Qr.svgDataUri(uri));
        return out;
    }

    /** Seconde étape : le code affiché par l'application prouve que le secret a bien été enregistré. */
    @PostMapping("/totp/enable")
    Map<String, Object> totpEnable(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body,
                                   HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        String secret = session == null ? null : (String) session.getAttribute(PENDING_TOTP);
        if (secret == null) {
            throw ApiException.badRequest("Recommence l'activation : le QR code a expiré.");
        }
        long step = Totp.verify(secret, Values.trimmed(body.get("code")), 0);
        if (step < 0) {
            throw ApiException.badRequest("Code incorrect ou expiré.");
        }
        session.removeAttribute(PENDING_TOTP);
        users.setTotp(user.id(), secrets.encrypt(secret), step);
        return view(keepThisSession(user.id(), request, response));
    }

    @PostMapping("/totp/disable")
    Map<String, Object> totpDisable(@AuthenticationPrincipal AppUser user, @RequestBody Map<String, Object> body,
                                    HttpServletRequest request, HttpServletResponse response) {
        checkedPasswordOf(user, body.get("password"));
        if (!acceptCode(user.id(), Values.trimmed(body.get("code")))) {
            throw ApiException.badRequest("Code incorrect ou expiré.");
        }
        users.setTotp(user.id(), null, 0);
        return view(keepThisSession(user.id(), request, response));
    }

    static String checkedPassword(Object value) {
        String password = Values.str(value);
        if (password.length() < MIN_PASSWORD) {
            throw ApiException.badRequest("Le mot de passe doit faire au moins " + MIN_PASSWORD + " caractères.");
        }
        if (password.length() > MAX_PASSWORD) {
            throw ApiException.badRequest("Le mot de passe est trop long (" + MAX_PASSWORD + " caractères maximum).");
        }
        return password;
    }
}
