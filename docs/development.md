# Running in development

Everything is started from the repository root, with [Bun](https://bun.sh):

```bash
bun run dev
```

This command starts the database, then the three services in the same terminal, each with its own prefix.
Open <http://localhost:4200> and log in with `admin` / `changeme-123`. `Ctrl+C` stops the services; the database
keeps running.

You work on port 4200: the interface reloads on every change and forwards `/api` calls to the server
(`frontend/proxy.conf.mjs`).

## Commands

| Command | Starts | Address |
|---|---|---|
| `bun run dev` | the database, then the three services | <http://localhost:4200> |
| `bun run database` | the PostgreSQL database (Docker container `candidatures-dev-db`) | `localhost:5433` |
| `bun run scraper` | the job search service | <http://localhost:8000> |
| `bun run backend` | the Spring Boot server | <http://localhost:8080> |
| `bun run frontend` | the Angular interface | <http://localhost:4200> |
| `bun run database:stop` | stops the database (data kept) | |
| `bun run database:reset` | recreates the database, **development data erased** | |
| `bun run test` | the server tests | |
| `bun run build` | builds the interface into `frontend/dist/` | |

To start the services separately, use one terminal per command: `database` first (it returns once the database is
ready), then `scraper`, `backend` and `frontend`. The search service is only needed to run job searches.

The scripts live in `tools/dev.ts`. On first run they install what is missing: the Python environment (`.venv`,
with hash checking) and the interface dependencies (`bun install`).

## Required tools

| Tool | Version | For |
|---|---|---|
| Bun | 1.4 | the launch commands and the interface |
| Docker | recent | the database |
| Java (JDK) | 21 or later | the server |
| Maven | 3.9 | the server |
| Python | 3.12 | the job search |

Node.js is not needed: Angular is built and served by Bun.

## Settings

With no configuration, everything works with development values. To change them, create a `.env.dev` file at the
root (not versioned):

| Variable | Default | Role |
|---|---|---|
| `DEV_DB_PORT` | `5433` | database port. Not 5432, which is often taken by another PostgreSQL database. After a change: `bun run database:reset` |
| `DEV_BACKEND_PORT` | `8080` | server port |
| `DEV_SCRAPER_PORT` | `8000` | search service port |
| `DEV_FRONTEND_PORT` | `4200` | interface port |
| `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` | `admin`, `changeme-123` | first account, created only when the database holds no account |
| `APP_SECRET_KEY` | `dev-seulement` | encryption of the secrets stored in the database; do not change it once secrets are saved |
| `SERPAPI_KEY` | empty | Google Jobs; without a key, Google is skipped |
| `LANGUAGETOOL_URL` | api.languagetool.org | résumé spell checker |

The deployment `.env` file is never read in development (`bunfig.toml` disables its loading by Bun): its secrets
must not reach the development services, especially the search service, which visits third-party sites.

## Interface: Bun instead of npm

In `frontend/`, dependencies are managed by Bun (`bun.lock`): `bun install`, `bun add <package>`, `bun update`.
The `bun run start` and `bun run build` scripts run Angular on Bun. Do not use `npm`, which would recreate a
`package-lock.json`.

## Tests

```bash
bun run test
```

They cover the detection of skills, categories and countries, PDF generation and two-factor codes. They do not need
the database.

## Without Java or Maven

The tests can run in a container:

```bash
docker run --rm -v "$PWD/backend":/w -w /w maven:3.9-eclipse-temurin-21 mvn test
```

To run the app with nothing installed but Docker, see the next section.

## Testing the full image

To check the app as it will be deployed (interface built and embedded in the server):

```bash
cp .env.example .env      # fill in POSTGRES_PASSWORD, APP_ADMIN_PASSWORD, APP_SECRET_KEY
docker compose up -d --build
```

The app is then on <http://localhost:8080>. See [deployment.md](deployment.md). If the development server already
uses port 8080, set `APP_PORT=8081` in `.env`.

## Code layout

```
backend/    Spring Boot 4 server (Java 21)
frontend/   Angular 22 interface
scraper/    Python job search service
tools/      launch scripts, export of the old version's data
docs/       documentation
```

Code comments, identifiers of the domain and interface texts are in French.

### Server (`backend/src/main/java/app/candidatures/`)

| Package | Role |
|---|---|
| `auth` | accounts, sessions and their revocation, two-factor authentication, CSRF, request limits |
| `jobs` | offers, detection of skills / categories / countries, search plan |
| `scrape` | calls the search service, queries Google Jobs, stores the offers found |
| `settings` | each user's search settings |
| `cv` | résumés, PDF import, spell checker and translation; `cv/pdf`: layout engine and PDF generation |
| `account` | export / import of an account's data |
| `common` | errors, JSON, encryption, visitor address |

### Interface (`frontend/src/app/`)

| Folder | Role |
|---|---|
| `core` | server calls, session, side navigation bar, light / dark theme |
| `login` | login page |
| `kanban` | offers board, search, settings, companies |
| `cv` | résumé editor |
| `account` | password, two-factor authentication, backups, account management |

## Common tasks

**Change the database schema.** Add a `V3__description.sql` file in `backend/src/main/resources/db/migration/`.
Never modify a migration that has already been applied: Flyway refuses to start if its content changed. Update
[database.md](database.md).

**Add or update a Python library.** Edit `scraper/requirements.in`, then regenerate the locked file:

```bash
docker run --rm -v "$PWD/scraper":/w -w /w python:3.12-slim sh -c \
  "pip install -q pip-tools && pip-compile --generate-hashes --strip-extras --no-header requirements.in -o requirements.txt"
```

**Fix a site that no longer returns offers.** Each site has its script in `scraper/scrapers/`. Google Jobs is
separate: it is queried by the server (`scrape/GoogleJobs.java`), because it needs a key and the search service
must not receive any secret.

**Add an API route.** Routes under `/api/` require a session, and a CSRF token for anything that is not a `GET`
(the interface sends it by itself). Per-route size and rate limits are in `auth/RequestLimitFilter.java`; without a
specific rule, a route gets the general limit (1 MB, 600 requests per minute).

**Call the API by hand.** The session and the CSRF token travel in cookies:

```bash
curl -s -c cookies.txt http://localhost:8080/api/auth/me
token=$(awk '$6 == "XSRF-TOKEN" { print $7 }' cookies.txt)
curl -s -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $token" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"changeme-123"}' http://localhost:8080/api/auth/login
curl -s -b cookies.txt http://localhost:8080/api/jobs
```
