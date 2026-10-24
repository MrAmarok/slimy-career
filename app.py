"""Interface kanban : python app.py puis http://localhost:5000"""
import json
import re
import subprocess
import sys
from collections import deque
from pathlib import Path
from threading import Thread

from flask import Flask, abort, jsonify, render_template, request

import config
import db
import search_plan
import skills
from scrapers import SCRAPERS

app = Flask(__name__)
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.json.sort_keys = False  # garder l'ordre des catégories
ROOT = Path(__file__).parent
scrape_state = {"proc": None, "log": deque(maxlen=300), "start_count": 0, "stopped": False}
STEP_RE = re.compile(r"^\[(\d+)/(\d+)\] \[(\w+)\] « (.+) » à (.+) \.\.\.$")


def job_count():
    return db.connect().execute("SELECT COUNT(*) FROM jobs").fetchone()[0]


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/meta")
def meta():
    conn = db.connect()
    jobs = [db.row_to_dict(r) for r in conn.execute("SELECT tags, skills, source, location, country, categories, zones FROM jobs")]
    return jsonify(
        statuses=[{"key": k, "label": l} for k, l in db.STATUSES],
        tags=sorted({t for j in jobs for t in j["tags"]}),
        skills=sorted({s for j in jobs for s in j["skills"]}, key=str.lower),
        sources=sorted({j["source"].split(" ")[0] for j in jobs if j["source"]}),
        cities=sorted({j["city"] for j in jobs if j["city"]}, key=str.lower),
        countries=sorted({j["country"] for j in jobs if j["country"]}),
        zones=sorted({z for j in jobs for z in j["zones"]}, key=str.lower),
        categories=list(config.load()["categories"]),
    )


@app.get("/api/jobs")
def list_jobs():
    conn = db.connect()
    rows = conn.execute("SELECT * FROM jobs ORDER BY COALESCE(date_posted, created_at) DESC").fetchall()
    return jsonify([db.row_to_dict(r) for r in rows])


@app.patch("/api/jobs/<int:job_id>")
def update_job(job_id):
    data = request.get_json(force=True)
    fields = {}
    if "status" in data:
        if data["status"] not in db.STATUS_KEYS:
            abort(400, "statut inconnu")
        fields["status"] = data["status"]
    if "notes" in data:
        fields["notes"] = str(data["notes"])
    for key in ("tags", "skills"):
        if key in data:
            if not isinstance(data[key], list):
                abort(400, f"{key} doit être une liste")
            fields[key] = json.dumps(skills.merge([str(v).strip() for v in data[key] if str(v).strip()]), ensure_ascii=False)
    if not fields:
        abort(400, "rien à modifier")
    conn = db.connect()
    sets = ", ".join(f"{k} = ?" for k in fields)
    cur = conn.execute(
        f"UPDATE jobs SET {sets}, updated_at = datetime('now', 'localtime') WHERE id = ?",
        (*fields.values(), job_id),
    )
    conn.commit()
    if cur.rowcount == 0:
        abort(404)
    return jsonify(db.row_to_dict(conn.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone()))


def _ids(data):
    ids = data.get("ids")
    if not isinstance(ids, list) or not ids or not all(isinstance(i, int) for i in ids):
        abort(400, "ids doit être une liste d'entiers")
    return ids


@app.post("/api/jobs/bulk-status")
def bulk_status():
    data = request.get_json(force=True)
    ids = _ids(data)
    if data.get("status") not in db.STATUS_KEYS:
        abort(400, "statut inconnu")
    conn = db.connect()
    marks = ",".join("?" * len(ids))
    cur = conn.execute(
        f"UPDATE jobs SET status = ?, updated_at = datetime('now', 'localtime') WHERE id IN ({marks})",
        (data["status"], *ids),
    )
    conn.commit()
    return jsonify(updated=cur.rowcount)


@app.post("/api/jobs/bulk-delete")
def bulk_delete():
    ids = _ids(request.get_json(force=True))
    conn = db.connect()
    cur = conn.execute(f"DELETE FROM jobs WHERE id IN ({','.join('?' * len(ids))})", ids)
    conn.commit()
    return jsonify(deleted=cur.rowcount)


@app.delete("/api/jobs/<int:job_id>")
def delete_job(job_id):
    conn = db.connect()
    conn.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
    conn.commit()
    return "", 204


@app.get("/api/config")
def get_config():
    return jsonify(config=config.load(), available_sites=list(SCRAPERS))


@app.put("/api/config")
def put_config():
    data = request.get_json(force=True)
    if "sites" in data and not set(data["sites"]) <= set(SCRAPERS):
        abort(400, "site inconnu")
    before = config.load()["categories"]
    try:
        saved = config.save(data)
    except (TypeError, ValueError) as e:
        abort(400, str(e))
    if saved["categories"] != before:
        db.recategorize(db.connect())
    return jsonify(config=saved, available_sites=list(SCRAPERS))


@app.post("/api/redetect-skills")
def redetect_skills():
    """Ajoute aux offres existantes les compétences de la liste actuelle trouvées dans leur texte
    (n'enlève rien, pour garder les modifications manuelles)."""
    patterns = skills.build_patterns(config.load()["skills"])
    conn = db.connect()
    changed = 0
    for row in conn.execute("SELECT id, title, description, skills FROM jobs").fetchall():
        current = json.loads(row["skills"] or "[]")
        merged = skills.merge(current, skills.extract(f"{row['title']}\n{row['description'] or ''}", patterns))
        if len(merged) != len(current):
            conn.execute("UPDATE jobs SET skills = ? WHERE id = ?", (json.dumps(merged, ensure_ascii=False), row["id"]))
            changed += 1
    conn.commit()
    return jsonify(updated=changed)


def _search_request(data):
    """Paramètres de recherche envoyés par le panneau, complétés par la config."""
    cfg = config.load()
    clean = lambda key: [str(v).strip() for v in (data.get(key) if key in data else cfg[key]) or [] if str(v).strip()]
    req = {
        "searches": clean("searches"), "search_tags": clean("search_tags"), "search_skills": clean("search_skills"),
        "locations": clean("locations") or cfg["locations"], "sites": clean("sites") or cfg["sites"],
        "contract": str(data.get("contract", cfg["contract"])).strip(),
    }
    if not set(req["sites"]) <= set(SCRAPERS):
        abort(400, "site inconnu")
    req["queries"] = search_plan.build_queries(req["searches"], req["contract"], req["search_tags"],
                                               req["search_skills"], cfg["categories"])
    return req


@app.post("/api/scrape/plan")
def scrape_plan():
    """Aperçu : requêtes qui seront lancées et nombre total de recherches."""
    req = _search_request(request.get_json(silent=True) or {})
    return jsonify(
        queries=[{"query": q, "tag": t} for q, t in req["queries"]],
        steps=len(req["queries"]) * len(req["locations"]) * len(req["sites"]),
        locations=len(req["locations"]), sites=len(req["sites"]),
    )


@app.post("/api/scrape")
def start_scrape():
    proc = scrape_state["proc"]
    if proc and proc.poll() is None:
        abort(409, "une recherche tourne déjà")
    data = request.get_json(silent=True) or {}
    req = _search_request(data)
    if not req["queries"]:
        abort(400, "rien à chercher : ajoute une recherche, un tag ou une compétence")
    if data.get("remember"):
        config.save({k: req[k] for k in ("searches", "search_tags", "search_skills", "locations", "sites", "contract")})
    cmd = [sys.executable, "-u", str(ROOT / "scrape.py"), "-c", req["contract"], "--sites", *req["sites"]]
    for flag, key in (("-s", "searches"), ("-t", "search_tags"), ("-k", "search_skills"), ("-l", "locations")):
        for v in req[key]:
            cmd += [flag, v]
    scrape_state["log"].clear()
    scrape_state["start_count"] = job_count()
    scrape_state["stopped"] = False
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    scrape_state["proc"] = proc
    Thread(target=lambda: [scrape_state["log"].append(line.rstrip()) for line in proc.stdout], daemon=True).start()
    return jsonify(running=True)


@app.get("/api/scrape")
def scrape_status():
    """État courant : étape, site, recherche, zone et nombre d'offres ajoutées depuis le lancement."""
    proc = scrape_state["proc"]
    log = list(scrape_state["log"])
    step = None
    for line in reversed(log):
        if m := STEP_RE.match(line):
            step = dict(zip(("index", "total", "site", "query", "location"), m.groups()))
            step["index"], step["total"] = int(step["index"]), int(step["total"])
            break
    return jsonify(
        running=bool(proc and proc.poll() is None),
        started=proc is not None,
        stopped=scrape_state["stopped"],
        new_jobs=job_count() - scrape_state["start_count"] if proc else 0,
        step=step,
        log=log,
    )


@app.post("/api/scrape/stop")
def stop_scrape():
    proc = scrape_state["proc"]
    if proc and proc.poll() is None:
        scrape_state["stopped"] = True
        proc.terminate()
    return jsonify(running=False)


if __name__ == "__main__":
    db.connect()
    app.run(debug=False, port=5000)
