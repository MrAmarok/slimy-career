package app.candidatures.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Au premier démarrage (aucun compte), crée le compte administrateur à partir de APP_ADMIN_USERNAME / APP_ADMIN_PASSWORD. */
@Component
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    AdminBootstrap(UserRepository users, PasswordEncoder encoder,
                   @Value("${app.admin.username}") String username, @Value("${app.admin.password}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String name = username.isBlank() ? "admin" : username.strip();
        if (!password.isBlank()) {
            users.create(name, encoder.encode(password), true);
            log.info("Compte administrateur « {} » créé.", name);
            return;
        }
        byte[] random = new byte[12];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        users.create(name, encoder.encode(generated), true);
        log.warn("APP_ADMIN_PASSWORD n'est pas défini. Compte administrateur « {} » créé avec le mot de passe : {}  "
                + "(à changer dans « Mon compte »)", name, generated);
    }
}
