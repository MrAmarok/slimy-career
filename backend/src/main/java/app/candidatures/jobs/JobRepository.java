package app.candidatures.jobs;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import app.candidatures.common.Json;

@Repository
public class JobRepository {

    private static final String TS = "YYYY-MM-DD HH24:MI:SS";
    private static final String COLUMNS = "id, title, company, location, description, skills, tags, link, source, contract, date_posted, "
            + "status, notes, to_char(created_at, '" + TS + "') AS created_at, to_char(updated_at, '" + TS + "') AS updated_at, "
            + "country, categories, zones";

    private final JdbcClient db;
    private final Json json;
    private final RowMapper<Map<String, Object>> row = this::toMap;

    public JobRepository(JdbcClient db, Json json) {
        this.db = db;
        this.json = json;
    }

    private Map<String, Object> toMap(ResultSet rs, int n) throws SQLException {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", rs.getLong("id"));
        for (String key : List.of("title", "company", "location", "description")) {
            d.put(key, rs.getString(key));
        }
        d.put("skills", json.strings(rs.getString("skills")));
        d.put("tags", json.strings(rs.getString("tags")));
        for (String key : List.of("link", "source", "contract", "date_posted", "status", "notes", "created_at", "updated_at", "country")) {
            d.put(key, rs.getString(key));
        }
        d.put("categories", json.strings(rs.getString("categories")));
        d.put("zones", json.strings(rs.getString("zones")));
        d.put("city", Geo.cityOf(rs.getString("location")));
        return d;
    }

    public List<Map<String, Object>> findAll(long userId) {
        return db.sql("SELECT " + COLUMNS + " FROM jobs WHERE user_id = ? "
                + "ORDER BY COALESCE(date_posted, to_char(created_at, '" + TS + "')) COLLATE \"C\" DESC, id DESC")
                .param(userId).query(row).list();
    }

    public Optional<Map<String, Object>> find(long userId, long id) {
        return db.sql("SELECT " + COLUMNS + " FROM jobs WHERE user_id = ? AND id = ?").params(userId, id).query(row).optional();
    }

    public Optional<Map<String, Object>> findByLink(long userId, String link) {
        return db.sql("SELECT " + COLUMNS + " FROM jobs WHERE user_id = ? AND link = ?").params(userId, link).query(row).optional();
    }

    public long count(long userId) {
        return db.sql("SELECT COUNT(*) FROM jobs WHERE user_id = ?").param(userId).query(Long.class).single();
    }

    /**
     * Insère une offre. Si le lien est déjà connu de cet utilisateur, complète seulement ses tags et zones
     * (statut et notes sont conservés). Retourne vrai si l'offre est nouvelle.
     */
    public boolean upsert(long userId, Job job) {
        Optional<Map<String, Object>> existing = db.sql("SELECT id, tags, zones FROM jobs WHERE user_id = ? AND link = ? FOR UPDATE")
                .params(userId, job.link()).query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("tags", rs.getString("tags"));
                    m.put("zones", rs.getString("zones"));
                    return m;
                }).optional();
        if (existing.isPresent()) {
            TreeSet<String> tags = new TreeSet<>(json.strings((String) existing.get().get("tags")));
            tags.addAll(job.tags());
            TreeSet<String> zones = new TreeSet<>(json.strings((String) existing.get().get("zones")));
            zones.addAll(job.zones());
            db.sql("UPDATE jobs SET tags = ?, zones = ? WHERE id = ?")
                    .params(json.write(tags), json.write(zones), existing.get().get("id")).update();
            return false;
        }
        List<String> tags = new ArrayList<>(job.tags());
        Collections.sort(tags);
        int inserted = db.sql("""
                INSERT INTO jobs (user_id, title, company, location, description, skills, tags, link, source, contract,
                                  date_posted, country, categories, zones)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, link) DO NOTHING""")
                .params(userId, job.title(), job.company(), job.location(), job.description(), json.write(job.skills()),
                        json.write(tags), job.link(), job.source(), job.contract(), job.datePosted(), job.country(),
                        json.write(job.categories()), json.write(job.zones()))
                .update();
        return inserted > 0;
    }

    /** Met à jour les colonnes données (noms sûrs : choisis par le code, jamais par le client). */
    public boolean update(long userId, long id, Map<String, Object> fields) {
        StringBuilder sets = new StringBuilder();
        List<Object> params = new ArrayList<>();
        fields.forEach((column, value) -> {
            sets.append(column).append(" = ?, ");
            params.add(value);
        });
        params.add(userId);
        params.add(id);
        return db.sql("UPDATE jobs SET " + sets + "updated_at = LOCALTIMESTAMP WHERE user_id = ? AND id = ?").params(params).update() > 0;
    }

    public void setStatusAndNotes(long userId, String link, String status, String notes) {
        db.sql("UPDATE jobs SET status = ?, notes = ? WHERE user_id = ? AND link = ?").params(status, notes, userId, link).update();
    }

    public int bulkStatus(long userId, List<Long> ids, String status) {
        return db.sql("UPDATE jobs SET status = :status, updated_at = LOCALTIMESTAMP WHERE user_id = :user AND id IN (:ids)")
                .param("status", status).param("user", userId).param("ids", ids).update();
    }

    public int bulkDelete(long userId, List<Long> ids) {
        return db.sql("DELETE FROM jobs WHERE user_id = :user AND id IN (:ids)").param("user", userId).param("ids", ids).update();
    }

    public void delete(long userId, long id) {
        db.sql("DELETE FROM jobs WHERE user_id = ? AND id = ?").params(userId, id).update();
    }

    public void setSkills(long id, List<String> skills) {
        db.sql("UPDATE jobs SET skills = ? WHERE id = ?").params(json.write(skills), id).update();
    }

    public void setCategories(long id, List<String> categories) {
        db.sql("UPDATE jobs SET categories = ? WHERE id = ?").params(json.write(categories), id).update();
    }

    /** Import d'une sauvegarde : l'offre arrive avec son statut, ses notes et ses dates. Retourne son nouvel id, ou vide si le lien existe déjà. */
    public Optional<Long> restore(long userId, Map<String, Object> j, java.util.function.Function<Object, String> list) {
        return db.sql("""
                INSERT INTO jobs (user_id, title, company, location, description, skills, tags, link, source, contract, date_posted,
                                  country, categories, zones, status, notes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(?::timestamp, LOCALTIMESTAMP), COALESCE(?::timestamp, LOCALTIMESTAMP))
                ON CONFLICT (user_id, link) DO NOTHING RETURNING id""")
                .params(userId, j.get("title"), j.get("company"), j.get("location"), j.get("description"), list.apply(j.get("skills")),
                        list.apply(j.get("tags")), j.get("link"), j.get("source"), j.get("contract"), j.get("date_posted"), j.get("country"),
                        list.apply(j.get("categories")), list.apply(j.get("zones")), j.get("status"), j.get("notes"),
                        j.get("created_at"), j.get("updated_at"))
                .query(Long.class).optional();
    }
}
