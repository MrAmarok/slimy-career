"""Recherche d'offres sur les sites d'emploi (LinkedIn, HelloWork, Indeed).

Ce script ne connaît ni la base ni aucune clé : il lit la demande en JSON sur l'entrée standard et écrit sur la
sortie standard, ligne par ligne :
- « @@START {json} » quand une recherche démarre ;
- « @@STEP {json} » quand elle se termine, avec les offres trouvées (ou « error ») : le serveur les enregistre
  (détection des compétences, catégories, pays, dédoublonnage par lien) ;
- toute autre ligne est un message affiché tel quel dans le journal de la recherche.

Demande attendue :
    {"queries": [{"query": "stage devops", "tag": "DevOps / Cloud"}], "locations": ["Bordeaux"],
     "sites": ["linkedin", "hellowork"], "limit": 25, "max_age_days": 30, "parallel_searches": 6, "per_site": 2,
     "country": "France", "radius_km": 30}

Pour essayer à la main :
    echo '{"queries": [{"query": "stage devops", "tag": "test"}], "locations": ["Bordeaux"], "sites": ["hellowork"], "limit": 3}' \
        | python scrape.py

Les recherches tournent en parallèle (`parallel_searches`), avec au plus `per_site` recherches simultanées
sur un même site pour ne pas se faire bloquer.
"""
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta

from scrapers import SCRAPERS

JOB_FIELDS = ("title", "company", "location", "description", "link", "source", "contract", "date_posted", "extra_skills")
MAX_DESCRIPTION = 100_000
_out_lock = threading.Lock()


def emit(line):
    """Écrit une ligne entière d'un coup : plusieurs threads écrivent en même temps."""
    with _out_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def event(kind, **data):
    emit(f"@@{kind} " + json.dumps(data, ensure_ascii=False))


def too_old(job, max_age_days):
    d = (job.get("date_posted") or "")[:10]
    try:
        return date.fromisoformat(d) < date.today() - timedelta(days=max_age_days)
    except ValueError:
        return False


def run_step(site, query, location, limit, max_age, cfg, slot):
    """Lance une recherche sur un site (dans un thread) et retourne les offres brutes."""
    with slot:
        event("START", site=site, query=query, location=location)
        return [job for job in SCRAPERS[site](query, location, limit, max_age, cfg)
                if job.get("title") and job.get("link") and not too_old(job, max_age)]


def slim(job):
    out = {k: job.get(k) for k in JOB_FIELDS if job.get(k) is not None}
    if isinstance(out.get("description"), str):
        out["description"] = out["description"][:MAX_DESCRIPTION]
    return out


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    cfg = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    queries = [(q["query"], q["tag"]) for q in cfg.get("queries") or []]
    locations = cfg.get("locations") or []
    sites = [s for s in cfg.get("sites") or [] if s in SCRAPERS]
    if not queries or not locations or not sites:
        sys.exit("Rien à chercher : il faut au moins une recherche, une zone et un site.")
    limit = max(1, min(int(cfg.get("limit") or 25), 200))
    max_age = max(1, int(cfg.get("max_age_days") or 30))
    per_site = max(1, min(int(cfg.get("per_site") or 2), 8))

    # site en boucle intérieure : les recherches lancées en même temps touchent des sites différents
    steps = [(site, query, tag, location) for query, tag in queries for location in locations for site in sites]
    slots = {site: threading.BoundedSemaphore(per_site) for site in sites}
    workers = max(1, min(int(cfg.get("parallel_searches") or 6), len(steps), 16))

    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(run_step, site, query, location, limit, max_age, cfg, slots[site]):
                   (site, query, tag, location) for site, query, tag, location in steps}
        for fut in as_completed(futures):
            site, query, tag, location = futures[fut]
            try:
                jobs = [slim(job) for job in fut.result()]
            except Exception as e:  # un site qui casse ne doit pas bloquer les autres
                event("STEP", site=site, query=query, tag=tag, location=location, error=repr(e)[:300])
                continue
            event("STEP", site=site, query=query, tag=tag, location=location, jobs=jobs)


if __name__ == "__main__":
    main()
