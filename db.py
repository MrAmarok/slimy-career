import json
import sqlite3
from pathlib import Path

import categories
import config
import geo

DB_PATH = Path(__file__).parent / "jobs.db"

STATUSES = [
    ("a_trier", "À trier"),
    ("interesse", "Intéressé"),
    ("postule", "Postulé"),
    ("en_attente", "En attente"),
    ("entretien", "Entretien"),
    ("refuse", "Refusé"),
    ("pas_interesse", "Pas intéressé"),
]
STATUS_KEYS = {k for k, _ in STATUSES}

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT NOT NULL,
    company     TEXT,
    location    TEXT,
    description TEXT,
    skills      TEXT DEFAULT '[]',   -- JSON list
    tags        TEXT DEFAULT '[]',   -- JSON list (recherches qui ont trouvé l'offre)
    link        TEXT NOT NULL UNIQUE,
    source      TEXT,
    contract    TEXT,
    country     TEXT,
    categories  TEXT DEFAULT '[]',   -- JSON list (catégories automatiques)
    zones       TEXT DEFAULT '[]',   -- JSON list (villes / régions de recherche qui ont trouvé l'offre)
    date_posted TEXT,
    status      TEXT NOT NULL DEFAULT 'a_trier',
    notes       TEXT DEFAULT '',
    created_at  TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at  TEXT DEFAULT (datetime('now', 'localtime'))
);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE TABLE IF NOT EXISTS cvs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    data        TEXT NOT NULL DEFAULT '{}',   -- JSON (voir cv_pdf.empty_cv)
    job_id      INTEGER,                      -- offre pour laquelle ce CV a été adapté (optionnel)
    created_at  TEXT DEFAULT (datetime('now', 'localtime')),
    updated_at  TEXT DEFAULT (datetime('now', 'localtime'))
);
"""


# colonnes ajoutées après la première version : migrées automatiquement
MIGRATIONS = {
    "country": "ALTER TABLE jobs ADD COLUMN country TEXT",
    "categories": "ALTER TABLE jobs ADD COLUMN categories TEXT DEFAULT '[]'",
    "zones": "ALTER TABLE jobs ADD COLUMN zones TEXT DEFAULT '[]'",
}


def connect():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    conn.executescript(SCHEMA)
    cols = {r["name"] for r in conn.execute("PRAGMA table_info(jobs)")}
    added = [c for c in MIGRATIONS if c not in cols]
    for c in added:
        conn.execute(MIGRATIONS[c])
    if "country" in added:
        default = config.load()["country"]
        for r in conn.execute("SELECT id, location FROM jobs").fetchall():
            conn.execute("UPDATE jobs SET country = ? WHERE id = ?", (geo.country_of(r["location"], default), r["id"]))
    if "zones" in added:  # offres trouvées avant : avec la première zone configurée
        zones = json.dumps(config.load()["locations"][:1], ensure_ascii=False)
        conn.execute("UPDATE jobs SET zones = ?", (zones,))
    if "categories" in added:
        recategorize(conn)
    if added:
        conn.commit()
    return conn


def recategorize(conn):
    """Recalcule les catégories automatiques de toutes les offres (après modification des mots-clés)."""
    compiled = categories.compile_categories(config.load()["categories"])
    for r in conn.execute("SELECT id, title, description FROM jobs").fetchall():
        cats = categories.classify(r["title"], r["description"] or "", compiled)
        conn.execute("UPDATE jobs SET categories = ? WHERE id = ?", (json.dumps(cats, ensure_ascii=False), r["id"]))
    conn.commit()


def upsert_job(conn, job):
    """Insère une offre. Si le lien existe déjà, fusionne juste les tags et zones. Retourne True si nouvelle."""
    row = conn.execute("SELECT id, tags, zones FROM jobs WHERE link = ?", (job["link"],)).fetchone()
    if row:
        tags = sorted(set(json.loads(row["tags"] or "[]")) | set(job["tags"]))
        zones = sorted(set(json.loads(row["zones"] or "[]")) | set(job.get("zones", [])))
        conn.execute("UPDATE jobs SET tags = ?, zones = ? WHERE id = ?",
                     (json.dumps(tags, ensure_ascii=False), json.dumps(zones, ensure_ascii=False), row["id"]))
        return False
    conn.execute(
        """INSERT INTO jobs (title, company, location, description, skills, tags, link, source, contract,
                             date_posted, country, categories, zones)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            job["title"], job.get("company"), job.get("location"), job.get("description"),
            json.dumps(job.get("skills", []), ensure_ascii=False),
            json.dumps(sorted(job["tags"]), ensure_ascii=False),
            job["link"], job.get("source"), job.get("contract"), job.get("date_posted"),
            job.get("country"), json.dumps(job.get("categories", []), ensure_ascii=False),
            json.dumps(job.get("zones", []), ensure_ascii=False),
        ),
    )
    return True


def row_to_dict(row):
    d = dict(row)
    if "location" in d:
        d["city"] = geo.city_of(d["location"])
    for key in ("skills", "tags", "categories", "zones"):
        if key in d:
            d[key] = json.loads(d[key] or "[]")
    return d
