# Technical details

For an overview of the app, see the [README](../README.md). This document describes how it is built.

See also: [deployment](deployment.md), [running in development](development.md), [database map](database.md).

## Technologies

| Part | Technology | Folder |
|---|---|---|
| Interface | Angular 22 (standalone components, signals, CDK drag-drop), built with Bun | `frontend/` |
| Server | Spring Boot 4 (Java 21), Spring Security, JDBC + Flyway | `backend/` |
| Database | PostgreSQL 17 | — |
| Résumé PDF | Apache PDFBox (generation and import) | `backend/…/cv/` |
| Job search | isolated Python service (separate container, no secrets) | `scraper/` |
| Deployment | Docker Compose + Cloudflare tunnel (HTTPS) | `Dockerfile`, `docker-compose.yml` |

## Architecture

```
browser ──HTTPS──> Cloudflare ──tunnel──> cloudflared ──> app (Spring Boot + interface) ──> db (PostgreSQL)
                                                             │
                                                             ├──> scraper (Python) ──> LinkedIn, HelloWork, Indeed
                                                             └──> SerpAPI (Google Jobs), LanguageTool, DeepL
```

- **`app`** serves the built Angular interface and the API (`/api/…`). Authentication relies on a session (cookie)
  and a CSRF token.
- **`db`** is only reachable by `app`, on an internal Docker network with no Internet access.
- **`scraper`** receives from `app` the list of searches to run and streams the offers back as they come. It knows
  neither the database nor any secret.
- **`cloudflared`** opens an outbound connection to Cloudflare: no port is exposed to the Internet.

Each user has their own account: offers, résumés and settings are tied to `user_id` in every table.

## Job search

| Site | Method | Notes |
|---|---|---|
| LinkedIn | public "guest" endpoints | no account; LinkedIn rate limits (429), the script waits and retries |
| HelloWork | search page + JSON-LD of each posting | also provides skills |
| Indeed | `python-jobspy` library | |
| Google Jobs | SerpAPI, called by the Java server | free key on serpapi.com (100 searches/month) |

The search plan combines the keywords and exact searches from the settings with each location and each site
(`jobs/SearchPlan.java`). Offers are deduplicated by link: an offer already known keeps its status and notes, only
its tags and zones are completed. Skills, categories and countries are detected by the server from the text of the
offer.

Searches are sent from the IP address of the machine hosting the app (not from Cloudflare). Sites change their HTML
from time to time: if a site returns 0 offers on every search, its script in `scraper/scrapers/` probably needs
updating.

## Résumé

The PDF is produced by a layout engine written for the app (`cv/pdf/`), using the standard PDF fonts: the text
stays selectable and in reading order, which is what recruiting software (ATS) requires. If the content exceeds the
allowed number of pages, the layout is tightened automatically. Importing an existing PDF relies on a heuristic
analysis of its text (`cv/CvImport.java`).

The spell checker sends the written text to api.languagetool.org (or to an in-house LanguageTool server, variable
`LANGUAGETOOL_URL`). Translation uses the DeepL API with the user's own key.

## Security

- **Sessions**: changing a password closes every session of the account; a deleted account is logged out on its
  next request. Enabling or removing two-factor authentication closes the other sessions.
- **Two-factor authentication**: a 6-digit code (TOTP) is asked after the password; a code can only be used once.
  The secret is encrypted in the database with `APP_SECRET_KEY`. Lost phone: an administrator removes two-factor
  authentication from the account in "Comptes". If it is the only administrator:
  `docker compose exec db psql -U candidatures -c "UPDATE users SET totp_secret = NULL, session_version = session_version + 1 WHERE username = 'admin'"`
- **Login attempts**: 8 failures lock for 5 minutes (per address and username). The visitor's address is read from
  the `CF-Connecting-IP` header, written by Cloudflare (`APP_CLIENT_IP_HEADER`), never from `X-Forwarded-For`.
- **Limits**: request size (1 MB, 3 MB for a résumé, 30 MB for a backup import) and number of requests per minute,
  with lower caps for expensive operations (`RequestLimitFilter`).
- **Isolated job search**: the `scraper` service visits third-party sites, so it runs in a container with no
  secrets, no database access, and a read-only file system. Google Jobs, which needs a key, is queried by the
  server itself.
- **API keys** (DeepL, SerpAPI): encrypted in the database with `APP_SECRET_KEY`, never sent back to the browser
  nor included in backups.
- **Dependencies**: Python libraries pinned with their hashes (`scraper/requirements.txt`, generated from
  `requirements.in`), pinned Docker images, updates proposed by Dependabot (`.github/dependabot.yml`).
- **Browser**: Content-Security-Policy (no external or inline script), offer links restricted to `http(s)://`.

## Code layout

See [development.md](development.md#code-layout).
