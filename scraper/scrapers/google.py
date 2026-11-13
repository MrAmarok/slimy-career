"""Google Jobs via SerpAPI (Google bloque le scraping direct). Nécessite une clé."""
import os
import sys

import requests

API_URL = "https://serpapi.com/search.json"


def search(query, location, limit, max_age_days, cfg):
    key = cfg.get("serpapi_key") or os.environ.get("SERPAPI_KEY")  # la clé est transmise par le serveur
    if not key:
        print("  [google] pas de clé SerpAPI -> ignoré", file=sys.stderr)
        return
    # la zone va dans la requête : le paramètre "location" de SerpAPI refuse les noms qu'il ne connaît pas
    params = {"engine": "google_jobs", "q": f"{query} {location}", "hl": "fr", "gl": "fr", "api_key": key}
    seen = 0
    while seen < limit:
        r = requests.get(API_URL, params=params, timeout=30)
        if r.status_code != 200:
            print(f"  [google] erreur SerpAPI {r.status_code}: {r.text[:200]}", file=sys.stderr)
            return
        data = r.json()
        for j in data.get("jobs_results", []):
            options = j.get("apply_options") or []
            link = options[0]["link"] if options else j.get("share_link")
            if not link:
                continue
            ext = j.get("detected_extensions") or {}
            yield {
                "title": j.get("title"),
                "company": j.get("company_name"),
                "location": j.get("location"),
                "description": j.get("description"),
                "link": link,
                "contract": ext.get("schedule_type"),
                "source": "google" + (f" ({j['via'].removeprefix('via ')})" if j.get("via") else ""),
            }
            seen += 1
            if seen >= limit:
                return
        token = (data.get("serpapi_pagination") or {}).get("next_page_token")
        if not token:
            return
        params["next_page_token"] = token
