package app.candidatures.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final RowMapper<AppUser> ROW = (rs, n) -> new AppUser(rs.getLong("id"), rs.getString("username"),
            rs.getString("password_hash"), rs.getBoolean("admin"), rs.getInt("session_version"), rs.getString("totp_secret") != null);

    /** Secret TOTP chiffré et dernière tranche de temps acceptée. */
    public record Totp(String encryptedSecret, long lastStep) {}

    private final JdbcClient db;

    public UserRepository(JdbcClient db) {
        this.db = db;
    }

    public Optional<AppUser> findByUsername(String username) {
        return db.sql("SELECT * FROM users WHERE lower(username) = lower(?)").param(username).query(ROW).optional();
    }

    public Optional<AppUser> findById(long id) {
        return db.sql("SELECT * FROM users WHERE id = ?").param(id).query(ROW).optional();
    }

    public List<AppUser> findAll() {
        return db.sql("SELECT * FROM users ORDER BY id").query(ROW).list();
    }

    public long count() {
        return db.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public AppUser create(String username, String passwordHash, boolean admin) {
        long id = db.sql("INSERT INTO users (username, password_hash, admin) VALUES (?, ?, ?) RETURNING id")
                .params(username, passwordHash, admin).query(Long.class).single();
        return findById(id).orElseThrow();
    }

    /** Change le mot de passe et invalide toutes les sessions ouvertes du compte. */
    public void updatePassword(long id, String passwordHash) {
        db.sql("UPDATE users SET password_hash = ?, session_version = session_version + 1 WHERE id = ?").params(passwordHash, id).update();
    }

    public Optional<Totp> totp(long id) {
        return db.sql("SELECT totp_secret, totp_last_step FROM users WHERE id = ? AND totp_secret IS NOT NULL").param(id)
                .query((rs, n) -> new Totp(rs.getString("totp_secret"), rs.getLong("totp_last_step"))).optional();
    }

    /** Active (secret chiffré) ou désactive (null) la double authentification ; invalide les sessions ouvertes. */
    public void setTotp(long id, String encryptedSecret, long lastStep) {
        db.sql("UPDATE users SET totp_secret = ?, totp_last_step = ?, session_version = session_version + 1 WHERE id = ?")
                .params(encryptedSecret, lastStep, id).update();
    }

    /** Note la tranche du code qui vient de servir ; faux si elle a déjà été utilisée (code rejoué). */
    public boolean useTotpStep(long id, long step) {
        return db.sql("UPDATE users SET totp_last_step = ? WHERE id = ? AND totp_last_step < ?").params(step, id, step).update() > 0;
    }

    public void delete(long id) {
        db.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }
}
