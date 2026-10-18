import random
import threading
import time

import requests
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36",
    "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
}

_local = threading.local()  # une session par thread : les recherches tournent en parallèle


def _session():
    if not hasattr(_local, "session"):
        _local.session = requests.Session()
        _local.session.headers.update(HEADERS)
    return _local.session


def get(url, params=None, retries=4):
    """GET avec pause aléatoire et backoff sur 429/5xx. Retourne None si échec."""
    for attempt in range(retries):
        time.sleep(random.uniform(0.8, 1.8))
        try:
            r = _session().get(url, params=params, timeout=20)
        except requests.RequestException:
            r = None
        if r is not None and r.status_code == 200:
            return r
        if r is not None and r.status_code not in (429, 500, 502, 503):
            return None
        time.sleep(5 * 2**attempt)
    return None


def html_to_text(html):
    if not html:
        return ""
    return BeautifulSoup(html, "lxml").get_text("\n", strip=True)
