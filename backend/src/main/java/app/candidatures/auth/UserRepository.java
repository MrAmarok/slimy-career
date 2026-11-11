package app.candidatures.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final RowMapper<AppUser> ROW = (rs, n) ->
            new AppUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"), rs.getBoolean("admin"));

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

    public long countAdmins() {
        return db.sql("SELECT COUNT(*) FROM users WHERE admin").query(Long.class).single();
    }

    public AppUser create(String username, String passwordHash, boolean admin) {
        long id = db.sql("INSERT INTO users (username, password_hash, admin) VALUES (?, ?, ?) RETURNING id")
                .params(username, passwordHash, admin).query(Long.class).single();
        return new AppUser(id, username, passwordHash, admin);
    }

    public void updatePassword(long id, String passwordHash) {
        db.sql("UPDATE users SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void delete(long id) {
        db.sql("DELETE FROM users WHERE id = ?").param(id).update();
    }
}
