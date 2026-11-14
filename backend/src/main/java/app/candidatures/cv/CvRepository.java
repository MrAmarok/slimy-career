package app.candidatures.cv;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.candidatures.common.Json;

@Repository
public class CvRepository {

    private static final String TS = "YYYY-MM-DD HH24:MI:SS";

    private final JdbcClient db;
    private final Json json;

    public CvRepository(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    /** Liste sans le contenu (qui peut contenir une photo) : id, nom, offre liée, date de modification. */
    public List<Map<String, Object>> list(long userId) {
        return db.sql("SELECT id, name, job_id, to_char(updated_at, '" + TS + "') AS updated_at FROM cvs WHERE user_id = ? "
                + "ORDER BY updated_at DESC, id DESC").param(userId).query((rs, n) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getLong("id"));
                    d.put("name", rs.getString("name"));
                    d.put("job_id", rs.getObject("job_id", Long.class));
                    d.put("updated_at", rs.getString("updated_at"));
                    return d;
                }).list();
    }

    public Optional<Map<String, Object>> find(long userId, long id) {
        return db.sql("SELECT id, name, data, job_id, to_char(created_at, '" + TS + "') AS created_at, "
                + "to_char(updated_at, '" + TS + "') AS updated_at FROM cvs WHERE user_id = ? AND id = ?")
                .params(userId, id).query((rs, n) -> {
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", rs.getLong("id"));
                    d.put("name", rs.getString("name"));
                    d.put("data", CvModel.withDefaults(json.map(rs.getString("data"))));
                    d.put("job_id", rs.getObject("job_id", Long.class));
                    d.put("created_at", rs.getString("created_at"));
                    d.put("updated_at", rs.getString("updated_at"));
                    return d;
                }).optional();
    }

    public List<Map<String, Object>> findAll(long userId) {
        return db.sql("SELECT id FROM cvs WHERE user_id = ? ORDER BY id").param(userId).query(Long.class).list().stream()
                .map(id -> find(userId, id).orElseThrow()).toList();
    }

    /** L'offre liée n'est gardée que si elle appartient bien à l'utilisateur. */
    public long create(long userId, String name, Map<String, Object> data, Long jobId) {
        return db.sql("""
                INSERT INTO cvs (user_id, name, data, job_id)
                VALUES (?, ?, ?, (SELECT id FROM jobs WHERE id = ? AND user_id = ?)) RETURNING id""")
                .params(userId, name, json.write(data), jobId, userId).query(Long.class).single();
    }

    public long restore(long userId, String name, Map<String, Object> data, Long jobId, Object createdAt, Object updatedAt) {
        return db.sql("""
                INSERT INTO cvs (user_id, name, data, job_id, created_at, updated_at)
                VALUES (?, ?, ?, (SELECT id FROM jobs WHERE id = ? AND user_id = ?),
                        COALESCE(?::timestamp, LOCALTIMESTAMP), COALESCE(?::timestamp, LOCALTIMESTAMP)) RETURNING id""")
                .params(userId, name, json.write(data), jobId, userId, createdAt, updatedAt).query(Long.class).single();
    }

    public boolean rename(long userId, long id, String name) {
        return db.sql("UPDATE cvs SET name = ?, updated_at = LOCALTIMESTAMP WHERE user_id = ? AND id = ?").params(name, userId, id).update() > 0;
    }

    public boolean setData(long userId, long id, Map<String, Object> data) {
        return db.sql("UPDATE cvs SET data = ?, updated_at = LOCALTIMESTAMP WHERE user_id = ? AND id = ?")
                .params(json.write(data), userId, id).update() > 0;
    }

    public boolean setJob(long userId, long id, Long jobId) {
        return db.sql("UPDATE cvs SET job_id = (SELECT id FROM jobs WHERE id = ? AND user_id = ?), updated_at = LOCALTIMESTAMP "
                + "WHERE user_id = ? AND id = ?").params(jobId, userId, userId, id).update() > 0;
    }

    public String updatedAt(long userId, long id) {
        return db.sql("SELECT to_char(updated_at, '" + TS + "') FROM cvs WHERE user_id = ? AND id = ?").params(userId, id)
                .query(String.class).optional().orElse(null);
    }

    public void delete(long userId, long id) {
        db.sql("DELETE FROM cvs WHERE user_id = ? AND id = ?").params(userId, id).update();
    }
}
