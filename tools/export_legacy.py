"""Exporte les données de l'ancienne version (jobs.db + config.toml) dans un fichier de sauvegarde
à importer dans la nouvelle application : « Mon compte » > « Importer une sauvegarde ».

    python3 tools/export_legacy.py [jobs.db] [config.toml] > sauvegarde.json

Les clés d'API (DeepL, SerpAPI) ne sont pas exportées : elles vont dans le fichier .env du serveur.
"""
import json
import sqlite3
import sys
import tomllib
from pathlib import Path

db_path = Path(sys.argv[1] if len(sys.argv) > 1 else "jobs.db")
cfg_path = Path(sys.argv[2] if len(sys.argv) > 2 else "config.toml")

conn = sqlite3.connect(db_path)
conn.row_factory = sqlite3.Row

jobs = []
for row in conn.execute("SELECT * FROM jobs ORDER BY id"):
    job = dict(row)
    for key in ("skills", "tags", "categories", "zones"):
        job[key] = json.loads(job.get(key) or "[]")
    jobs.append(job)

cvs = []
for row in conn.execute("SELECT * FROM cvs ORDER BY id"):
    cv = dict(row)
    cv["data"] = json.loads(cv["data"] or "{}")
    cvs.append(cv)

out = {"format": "candidatures-export", "version": 1, "jobs": jobs, "cvs": cvs}
if cfg_path.exists():
    config = tomllib.loads(cfg_path.read_text(encoding="utf-8"))
    for secret in ("serpapi_key", "deepl_key"):
        config.pop(secret, None)
    out["config"] = config

json.dump(out, sys.stdout, ensure_ascii=False)
print(f"{len(jobs)} offres, {len(cvs)} CV exportés", file=sys.stderr)
