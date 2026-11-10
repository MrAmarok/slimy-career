CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    admin         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP
);
CREATE UNIQUE INDEX users_username_key ON users (lower(username));

-- Réglages de recherche de chaque utilisateur (JSON : zones, recherches, compétences, catégories…)
CREATE TABLE settings (
    user_id BIGINT PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    data    TEXT NOT NULL
);

-- Les listes (skills, tags, categories, zones) sont du JSON en texte : l'ordre des éléments est conservé.
CREATE TABLE jobs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title       TEXT NOT NULL,
    company     TEXT,
    location    TEXT,
    description TEXT,
    skills      TEXT NOT NULL DEFAULT '[]',
    tags        TEXT NOT NULL DEFAULT '[]',
    link        TEXT NOT NULL,
    source      TEXT,
    contract    TEXT,
    country     TEXT,
    categories  TEXT NOT NULL DEFAULT '[]',
    zones       TEXT NOT NULL DEFAULT '[]',
    date_posted TEXT,
    status      TEXT NOT NULL DEFAULT 'a_trier',
    notes       TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    UNIQUE (user_id, link)
);
CREATE INDEX jobs_user_status_idx ON jobs (user_id, status);

CREATE TABLE cvs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    data       TEXT NOT NULL DEFAULT '{}',
    job_id     BIGINT REFERENCES jobs (id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP
);
CREATE INDEX cvs_user_idx ON cvs (user_id);
