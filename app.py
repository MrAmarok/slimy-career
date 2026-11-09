"""Interface kanban : python app.py puis http://localhost:5000"""
import json
import re
import subprocess
import sys
import uuid
from collections import deque
from pathlib import Path
from threading import Thread

from flask import Flask, Response, abort, jsonify, render_template, request

import categories
import config
import cv_import
import cv_lang
import cv_pdf
import db
import geo
import search_plan
import skills
from scrapers import SCRAPERS

app = Flask(__name__)
app.config["TEMPLATES_AUTO_RELOAD"] = True
app.json.sort_keys = False  # garder l'ordre des catégories
app.config["MAX_CONTENT_LENGTH"] = 20 * 1024 * 1024  # import de CV en PDF
ROOT = Path(__file__).parent
scrape_state = {"proc": None, "log": deque(maxlen=300), "start_count": 0, "stopped": False}
STEP_RE = re.compile(r"^\[(\d+)/(\d+)\] \[(\w+)\] « (.+) » à (.+) \.\.\.$")


def job_count():
    return db.connect().execute("SELECT COUNT(*) FROM jobs").fetchone()[0]


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/cv")
def cv_page():
    return render_template("cv.html")


# ---------- CV ----------

def _cv_dict(row):
    d = dict(row)
    d["data"] = {**cv_pdf.empty_cv(), **json.loads(d["data"] or "{}")}
    return d


def _cv_payload():
    data = request.get_json(force=True)
    if not isinstance(data, dict):
        abort(400, "JSON attendu")
    if "data" in data and not isinstance(data["data"], dict):
        abort(400, "data doit être un objet")
    return data


@app.get("/api/cvs")
def list_cvs():
    rows = db.connect().execute("SELECT id, name, job_id, updated_at FROM cvs ORDER BY updated_at DESC, id DESC").fetchall()
    return jsonify([dict(r) for r in rows])


@app.post("/api/cvs")
def create_cv():
    """Nouveau CV : vide, ou copie d'un CV existant (from_id) pour l'adapter à une offre."""
    data = _cv_payload()
    conn = db.connect()
    content = cv_pdf.empty_cv()
    if data.get("from_id"):
        src = conn.execute("SELECT data FROM cvs WHERE id = ?", (data["from_id"],)).fetchone()
        if not src:
            abort(404)
        content = json.loads(src["data"])
    if isinstance(data.get("data"), dict):
        content = data["data"]
    name = str(data.get("name") or "").strip() or "Mon CV"
    cur = conn.execute("INSERT INTO cvs (name, data, job_id) VALUES (?, ?, ?)",
                       (name, json.dumps(content, ensure_ascii=False), data.get("job_id")))
    conn.commit()
    return jsonify(_cv_dict(conn.execute("SELECT * FROM cvs WHERE id = ?", (cur.lastrowid,)).fetchone())), 201


@app.post("/api/cvs/import")
def import_cv():
    """Crée un nouveau CV à partir d'un PDF existant (texte extrait et rangé dans les sections)."""
    file = request.files.get("file")
    if not file or not file.filename:
        abort(400, "aucun fichier")
    raw = file.read()
    if not raw.startswith(b"%PDF"):
        abort(400, "ce fichier n'est pas un PDF")
    try:
        data, warnings = cv_import.parse_pdf(raw)
    except Exception as e:
        abort(400, f"PDF illisible : {e}")
    name = "Import — " + (Path(file.filename).stem or "CV")
    conn = db.connect()
    cur = conn.execute("INSERT INTO cvs (name, data) VALUES (?, ?)", (name, json.dumps(data, ensure_ascii=False)))
    conn.commit()
    cv = _cv_dict(conn.execute("SELECT * FROM cvs WHERE id = ?", (cur.lastrowid,)).fetchone())
    return jsonify(cv=cv, warnings=warnings), 201


@app.get("/api/cvs/<int:cv_id>")
def get_cv(cv_id):
    row = db.connect().execute("SELECT * FROM cvs WHERE id = ?", (cv_id,)).fetchone()
    if not row:
        abort(404)
    return jsonify(_cv_dict(row))


@app.put("/api/cvs/<int:cv_id>")
def update_cv(cv_id):
    data = _cv_payload()
    fields = {}
    if "name" in data:
        fields["name"] = str(data["name"]).strip() or "Mon CV"
    if "data" in data:
        fields["data"] = json.dumps(data["data"], ensure_ascii=False)
    if "job_id" in data:
        fields["job_id"] = data["job_id"] if isinstance(data["job_id"], int) else None
    if not fields:
        abort(400, "rien à modifier")
    conn = db.connect()
    sets = ", ".join(f"{k} = ?" for k in fields)
    cur = conn.execute(f"UPDATE cvs SET {sets}, updated_at = datetime('now', 'localtime') WHERE id = ?", (*fields.values(), cv_id))
    conn.commit()
    if cur.rowcount == 0:
        abort(404)
    return jsonify(updated_at=conn.execute("SELECT updated_at FROM cvs WHERE id = ?", (cv_id,)).fetchone()[0])


@app.delete("/api/cvs/<int:cv_id>")
def delete_cv(cv_id):
    conn = db.connect()
    conn.execute("DELETE FROM cvs WHERE id = ?", (cv_id,))
    conn.commit()
    return "", 204


@app.post("/api/cv/pdf")
def cv_to_pdf():
    """PDF du CV envoyé (pas besoin qu'il soit enregistré : sert aussi à l'aperçu en direct)."""
    data = request.get_json(force=True)
    meta = {}
    pdf, filename = cv_pdf.build(data if isinstance(data, dict) else {}, meta)
    disposition = "attachment" if request.args.get("download") else "inline"
    return Response(pdf, mimetype="application/pdf",
                    headers={"Content-Disposition": f'{disposition}; filename="{filename}"', "X-Filename": filename,
                             "X-Pages": str(meta["pages"]), "X-Compact": f'{meta["compact"]:.2f}',
                             "X-Overflow": "1" if meta["overflow"] else "0"})


@app.get("/api/cv/lang")
def cv_lang_status():
    """Indique si la traduction est prête (clé DeepL présente), sans renvoyer la clé."""
    return jsonify(deepl=bool(cv_lang.deepl_key()))


@app.post("/api/cv/check")
def cv_check():
    """Fautes d'orthographe et de grammaire du CV envoyé (LanguageTool)."""
    body = request.get_json(force=True)
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        abort(400, "data attendu")
    try:
        return jsonify(issues=cv_lang.check(data, body.get("ignore") or []), language=cv_lang.language_of(data))
    except cv_lang.LangError as e:
        return Response(str(e), status=502, mimetype="text/plain")


@app.post("/api/cv/translate")
def cv_translate():
    """Copie du CV envoyé, traduite en anglais (DeepL). Le CV d'origine n'est pas modifié."""
    body = request.get_json(force=True)
    data = body.get("data") if isinstance(body, dict) else None
    if not isinstance(data, dict):
        abort(400, "data attendu")
    try:
        translated, chars = cv_lang.translate(data, str(body.get("variant") or "en-GB"))
    except cv_lang.LangError as e:
        return Response(str(e), status=502, mimetype="text/plain")
    return jsonify(data=translated, chars=chars)


@app.post("/api/cv/text")
def cv_to_text():
    """Texte extrait du PDF généré : ce qu'un logiciel de recrutement va lire."""
    data = request.get_json(force=True)
    pdf, _ = cv_pdf.build(data if isinstance(data, dict) else {})
    return jsonify(text=cv_pdf.extract_text(pdf))


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


@app.post("/api/jobs")
def create_job():
    """Offre ajoutée à la main (trouvée ailleurs, candidature spontanée, bouche-à-oreille…).
    Compétences, catégories et pays sont détectés comme pour les offres trouvées par la recherche."""
    data = request.get_json(force=True)
    if not isinstance(data, dict):
        abort(400, "JSON attendu")
    text = {k: str(data.get(k) or "").strip() for k in ("title", "company", "location", "link", "contract", "description", "notes")}
    if not text["title"]:
        return Response("Le titre du poste est obligatoire.", status=400, mimetype="text/plain")
    status = data.get("status") or "interesse"
    if status not in db.STATUS_KEYS:
        abort(400, "statut inconnu")
    link = text["link"]
    if link and not re.match(r"^[a-z][a-z0-9+.-]*:", link, re.I):
        link = "https://" + link
    cfg = config.load()
    conn = db.connect()
    if link:
        same = conn.execute("SELECT title, company FROM jobs WHERE link = ?", (link,)).fetchone()
        if same:
            return Response(f"Cette offre est déjà dans le tableau : {same['title']} — {same['company'] or '?'}",
                            status=409, mimetype="text/plain")
    body = f"{text['title']}\n{text['description']}"
    job = {
        "title": text["title"], "company": text["company"] or None, "location": text["location"] or None,
        "description": text["description"] or None, "contract": text["contract"] or None,
        # la colonne link est obligatoire et unique : sans lien, un identifiant interne (jamais affiché)
        "link": link or f"manuel:{uuid.uuid4().hex}", "source": "manuel", "tags": [], "zones": [],
        "skills": skills.extract(body, skills.build_patterns(cfg.get("skills", []))),
        "country": geo.country_of(text["location"] or None, cfg.get("country")),
        "categories": categories.classify(text["title"], text["description"], categories.compile_categories(cfg.get("categories", {}))),
    }
    db.upsert_job(conn, job)
    conn.execute("UPDATE jobs SET status = ?, notes = ? WHERE link = ?", (status, text["notes"], job["link"]))
    conn.commit()
    return jsonify(db.row_to_dict(conn.execute("SELECT * FROM jobs WHERE link = ?", (job["link"],)).fetchone())), 201


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
