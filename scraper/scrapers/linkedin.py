"""LinkedIn via les endpoints publics "guest" (pas besoin de compte)."""
import html
import re

from bs4 import BeautifulSoup

from .http import get

SEARCH_URL = "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
DETAIL_URL = "https://www.linkedin.com/jobs-guest/jobs/api/jobPosting/{}"


def _text(el):
    # LinkedIn double-échappe certains caractères (&amp;amp;)
    return html.unescape(el.get_text(" ", strip=True)) if el else None


def search(query, location, limit, max_age_days, cfg):
    seen = 0
    start = 0
    while seen < limit:
        r = get(SEARCH_URL, params={
            "keywords": query,
            "location": location,
            "distance": round(cfg.get("radius_km", 30) / 1.609),  # en miles
            "f_TPR": f"r{max_age_days * 86400}",
            "start": start,
        })
        if not r or not r.text.strip():
            return
        cards = BeautifulSoup(r.text, "lxml").select("div.base-card[data-entity-urn]")
        if not cards:
            return
        for card in cards:
            job_id = card["data-entity-urn"].rsplit(":", 1)[-1]
            time_el = card.select_one("time")
            job = {
                "title": _text(card.select_one(".base-search-card__title")),
                "company": _text(card.select_one(".base-search-card__subtitle")),
                "location": _text(card.select_one(".job-search-card__location")),
                "link": f"https://www.linkedin.com/jobs/view/{job_id}",
                "date_posted": time_el.get("datetime") if time_el else None,
                "source": "linkedin",
            }
            job.update(_details(job_id))
            yield job
            seen += 1
            if seen >= limit:
                return
        start += len(cards)


def _details(job_id):
    r = get(DETAIL_URL.format(job_id))
    if not r:
        return {}
    soup = BeautifulSoup(r.text, "lxml")
    desc = soup.select_one(".show-more-less-html__markup")
    out = {"description": desc.get_text("\n", strip=True) if desc else None}
    for item in soup.select(".description__job-criteria-item"):
        label = _text(item.select_one("h3")) or ""
        if re.search(r"employment type|type d.emploi", label, re.I):
            out["contract"] = _text(item.select_one("span"))
    return out
