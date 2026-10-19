"""Indeed via la librairie python-jobspy."""
import math

from jobspy import scrape_jobs


def _clean(v):
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return None
    return str(v)


def search(query, location, limit, max_age_days, cfg):
    df = scrape_jobs(
        site_name=["indeed"],
        search_term=query,
        location=location,
        country_indeed=cfg.get("country") or "France",
        distance=round(cfg.get("radius_km", 30) / 1.609),  # en miles
        results_wanted=limit,
        hours_old=max_age_days * 24,
        description_format="markdown",
        verbose=0,
    )
    for _, row in df.iterrows():
        yield {
            "title": _clean(row.get("title")),
            "company": _clean(row.get("company")),
            "location": _clean(row.get("location")),
            "description": _clean(row.get("description")),
            "link": _clean(row.get("job_url")),
            "contract": _clean(row.get("job_type")),
            "date_posted": _clean(row.get("date_posted")),
            "source": "indeed",
        }
