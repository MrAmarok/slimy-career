package app.candidatures.auth;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.candidatures.common.ApiException;
import app.candidatures.common.Values;

/** Gestion des comptes, réservée à l'administrateur (il n'y a pas d'inscription libre). */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder encoder;

    AdminController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @GetMapping
    List<Map<String, Object>> list() {
        return users.findAll().stream().map(AuthController::view).toList();
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String username = Values.trimmed(body.get("username"));
        if (!username.matches("[\\p{L}\\p{N}._@-]{2,40}")) {
            throw ApiException.badRequest("Identifiant invalide : 2 à 40 lettres, chiffres, points, tirets ou @.");
        }
        if (users.findByUsername(username).isPresent()) {
            throw ApiException.conflict("Cet identifiant est déjà pris.");
        }
        String hash = encoder.encode(AuthController.checkedPassword(body.get("password")));
        AppUser created = users.create(username, hash, Boolean.TRUE.equals(body.get("admin")));
        return ResponseEntity.status(HttpStatus.CREATED).body(AuthController.view(created));
    }

    @PutMapping("/{id}/password")
    Map<String, Object> resetPassword(@PathVariable long id, @RequestBody Map<String, Object> body) {
        users.findById(id).orElseThrow(ApiException::notFound);
        users.updatePassword(id, encoder.encode(AuthController.checkedPassword(body.get("password"))));
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, @AuthenticationPrincipal AppUser me) {
        if (id == me.id()) {
            throw ApiException.badRequest("Tu ne peux pas supprimer ton propre compte.");
        }
        users.delete(id); // offres, CV et réglages du compte partent avec (ON DELETE CASCADE)
        return ResponseEntity.noContent().build();
    }
}
