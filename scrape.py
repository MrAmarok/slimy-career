"""Récupère les offres sur les sites configurés et les stocke dans jobs.db.

    python scrape.py                         # toutes les recherches de config.toml
    python scrape.py -s "stage rust" -n 10   # recherche ponctuelle
    python scrape.py --sites linkedin hellowork
    python scrape.py -l Toulouse -l "Île-de-France"   # zones ponctuelles
    python scrape.py -t Cybersécurité -k Python -k Docker   # tags / compétences ("stage cybersécurité", "stage python"...)
    python scrape.py -j 10                   # 10 recherches en parallèle

Si -s, -t ou -k est donné, seuls ces éléments sont cherchés ; sinon tout ce qui est dans config.toml.

Les recherches tournent en parallèle (`parallel_searches`), avec au plus `per_site` recherches
simultanées sur un même site pour ne pas se faire bloquer. Seul le thread principal écrit dans la base.
"""
import argparse
import sys
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, timedelta

import categories
import config
import db
import geo
import search_plan
import skills
from scrapers import SCRAPERS


def too_old(job, max_age_days):
    d = (job.get("date_posted") or "")[:10]
    try:
        return date.fromisoformat(d) < date.today() - timedelta(days=max_age_days)
    except ValueError:
        return False


def run_step(site, query, location, limit, max_age, cfg, slot):
    """Lance une recherche sur un site (dans un thread) et retourne les offres brutes."""
    with slot:
        print(f"  ▶ [{site}] « {query} » à {location}", flush=True)
        return [job for job in SCRAPERS[site](query, location, limit, max_age, cfg)
                if job.get("title") and job.get("link") and not too_old(job, max_age)]


def main():
    cfg = config.load()
    ap = argparse.ArgumentParser()
    ap.add_argument("-s", "--search", action="append", help="requête (remplace celles de la config)")
    ap.add_argument("--sites", nargs="+", choices=SCRAPERS, default=cfg.get("sites", list(SCRAPERS)))
    ap.add_argument("-n", "--limit", type=int, default=cfg.get("results_per_search", 25))
    ap.add_argument("-t", "--tag", action="append", help="tag / domaine cherché avec le type de contrat")
    ap.add_argument("-k", "--skill", action="append", help="compétence cherchée avec le type de contrat")
    ap.add_argument("-c", "--contract", default=None, help="type de contrat ajouté devant les tags et compétences")
    ap.add_argument("-l", "--location", action="append", help="ville / région (remplace celles de la config)")
    ap.add_argument("-j", "--jobs", type=int, default=cfg.get("parallel_searches", 6), help="recherches en parallèle")
    ap.add_argument("--per-site", type=int, default=cfg.get("per_site", 2), help="recherches simultanées max par site")
    args = ap.parse_args()

    if args.search or args.tag or args.skill:
        searches, tags, wanted_skills = args.search or [], args.tag or [], args.skill or []
    else:
        searches, tags, wanted_skills = cfg["searches"], cfg["search_tags"], cfg["search_skills"]
    contract = cfg["contract"] if args.contract is None else args.contract
    queries = search_plan.build_queries(searches, contract, tags, wanted_skills, cfg.get("categories", {}))
    if not queries:
        sys.exit("Rien à chercher : ajoute une recherche, un tag ou une compétence.")
    locations = args.location or cfg["locations"]
    max_age = cfg.get("max_age_days", 30)
    patterns = skills.build_patterns(cfg.get("skills", []))
    compiled_cats = categories.compile_categories(cfg.get("categories", {}))
    conn = db.connect()

    # site en boucle intérieure : les recherches lancées en même temps touchent des sites différents
    steps = [(site, query, tag, location)
             for query, tag in queries for location in locations for site in args.sites]
    slots = {site: threading.BoundedSemaphore(max(1, args.per_site)) for site in args.sites}
    workers = max(1, min(args.jobs, len(steps)))
    print(f"{len(steps)} recherches, {workers} en parallèle (max {args.per_site} par site)", flush=True)

    total_new = done = 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(run_step, site, query, location, args.limit, max_age, cfg, slots[site]):
                   (site, query, tag, location) for site, query, tag, location in steps}
        for fut in as_completed(futures):
            site, query, tag, location = futures[fut]
            done += 1
            # ligne lue par l'interface (STEP_RE dans app.py) : index = nombre de recherches terminées
            print(f"[{done}/{len(steps)}] [{site}] « {query} » à {location} ...", flush=True)
            try:
                jobs = fut.result()
            except Exception as e:  # un site qui casse ne doit pas bloquer les autres
                print(f"  erreur {site}: {e!r}", file=sys.stderr, flush=True)
                continue
            default_country = geo.normalize_country(location) or cfg.get("country")
            new = 0
            for job in jobs:
                text = f"{job['title']}\n{job.get('description') or ''}"
                job["skills"] = skills.merge(skills.extract(text, patterns), job.pop("extra_skills", []))
                job["tags"] = [tag]
                job["zones"] = [location]
                job["country"] = geo.country_of(job.get("location"), default_country)
                job["categories"] = categories.classify(job["title"], job.get("description") or "", compiled_cats)
                if db.upsert_job(conn, job):
                    new += 1
            conn.commit()
            print(f"  {len(jobs)} offres, {new} nouvelles", flush=True)
            total_new += new

    count = conn.execute("SELECT COUNT(*) FROM jobs").fetchone()[0]
    print(f"\nTerminé : {total_new} nouvelles offres, {count} au total dans {db.DB_PATH.name}")


if __name__ == "__main__":
    main()
