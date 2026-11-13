"""HelloWork : page de recherche + données JSON-LD (schema.org JobPosting) de chaque offre."""
import json
from urllib.parse import urljoin

from bs4 import BeautifulSoup

from .http import get, html_to_text

BASE = "https://www.hellowork.com"
SEARCH_URL = BASE + "/fr-fr/emploi/recherche.html"


def search(query, location, limit, max_age_days, cfg):
    seen = 0
    page = 1
    while seen < limit:
        r = get(SEARCH_URL, params={"k": query, "l": location, "p": page})
        if not r:
            return
        cards = BeautifulSoup(r.text, "lxml").select('[data-cy="serpCard"]')
        if not cards:
            return
        for card in cards:
            a = card.select_one('a[data-cy="offerTitle"]')
            if not a:
                continue
            ps = a.select("p")
            job = {
                "title": ps[0].get_text(strip=True) if ps else a.get("title"),
                "company": ps[1].get_text(strip=True) if len(ps) > 1 else None,
                "location": _text(card.select_one('[data-cy="localisationCard"]')),
                "contract": _text(card.select_one('[data-cy="contractCard"]')),
                "link": urljoin(BASE, a["href"].split("?")[0]),
                "source": "hellowork",
            }
            job.update(_details(job["link"]))
            yield job
            seen += 1
            if seen >= limit:
                return
        page += 1


def _text(el):
    return el.get_text(" ", strip=True) if el else None


def _details(url):
    r = get(url)
    if not r:
        return {}
    for script in BeautifulSoup(r.text, "lxml").select('script[type="application/ld+json"]'):
        try:
            data = json.loads(script.string or "")
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict) and data.get("@type") == "JobPosting":
            desc = html_to_text(data.get("description"))
            quals = html_to_text(data.get("qualifications"))
            skills = data.get("skills") or []
            return {
                "title": data.get("title"),
                "description": "\n\n".join(p for p in (desc, quals) if p),
                "date_posted": (data.get("datePosted") or "")[:10] or None,
                "extra_skills": skills if isinstance(skills, list) else [],
            }
    return {}
