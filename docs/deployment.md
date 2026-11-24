# Deployment

The app is deployed with Docker Compose: a PostgreSQL database, the server (which also serves the interface), the
job search service, and a Cloudflare tunnel for HTTPS access from the Internet.

```
Internet ──HTTPS──> Cloudflare ──outbound tunnel──> cloudflared ──> app:8080 ──> db (PostgreSQL)
                                                                       └──────> scraper:8000 ──> job sites
```

No port needs to be opened on your router: `cloudflared` connects out to Cloudflare, not the other way round.

## 1. Requirements

| Need | Details |
|---|---|
| A machine that stays on | Linux preferably; 2 GB of memory is enough |
| Docker Engine with Compose v2 | `docker compose version` must answer |
| Git | to fetch the code and update it |
| A domain name managed by Cloudflare | only for access from the Internet |

## 2. Get the code

```bash
git clone https://github.com/MrAmarok/personnal-shit.git slimy-career
cd slimy-career
```

## 3. Configure

```bash
cp .env.example .env
chmod 600 .env
```

Then fill in `.env`:

| Variable | Required | Value |
|---|---|---|
| `POSTGRES_PASSWORD` | yes | database password: `openssl rand -hex 24` |
| `APP_ADMIN_USERNAME` | yes | username of the first account (`admin`) |
| `APP_ADMIN_PASSWORD` | yes | password of the first account, long and unique |
| `APP_SECRET_KEY` | yes | encryption key: `openssl rand -hex 32` |
| `CLOUDFLARE_TUNNEL_TOKEN` | for Internet access | tunnel token (step 5) |
| `SERPAPI_KEY` | no | SerpAPI key shared by all accounts, for Google Jobs |
| `APP_PORT` | no | local port, `8080` by default |
| `TZ` | no | time zone, `Europe/Paris` by default |

Good to know:

- **`APP_SECRET_KEY` must never change or be lost.** It encrypts the two-factor secrets and the API keys stored in
  the database; without it, that data becomes unreadable. Keep a copy off the machine (password manager).
- `APP_ADMIN_USERNAME` and `APP_ADMIN_PASSWORD` are only used on the very first start, when the database holds no
  account. Changing them afterwards has no effect: the password is changed in the app.
- `POSTGRES_PASSWORD` is also fixed when the database first starts. Changing it in `.env` afterwards prevents the
  server from connecting.
- The DeepL key (résumé translation) does not go in `.env`: each user enters their own in the app.

## 4. Start locally

```bash
docker compose up -d --build
docker compose ps
```

The first build takes several minutes. Once `app` is up, open <http://localhost:8080> and log in with the
administrator account.

The port is only open on the machine itself (`127.0.0.1`). To reach it from another computer without going through
the Internet, an SSH tunnel is enough: `ssh -L 8080:localhost:8080 user@machine`.

## 5. Open access from the Internet (Cloudflare tunnel)

1. In the Cloudflare dashboard: **Zero Trust > Networks > Tunnels > Create a tunnel**, type "Cloudflared".
2. Choose the "Docker" environment. The command shown contains `--token eyJ…`: copy the token value into
   `CLOUDFLARE_TUNNEL_TOKEN`.
3. In the tunnel's **Public Hostname** tab, add a name (for example `career.mydomain.com`) with a service of type
   **HTTP** and the address **`app:8080`**.
4. Start with the `tunnel` profile:

```bash
docker compose --profile tunnel up -d --build
docker compose logs -f cloudflared     # "Registered tunnel connection": the tunnel is up
```

Cloudflare provides and renews the HTTPS certificate. From then on, **every `docker compose` command must keep
`--profile tunnel`**, otherwise the `cloudflared` container is ignored.

### Recommended: Cloudflare Access in front of the app

Without it, the login page is visible to the whole Internet. In **Zero Trust > Access > Applications**, add a
"Self-hosted" application on the domain name, with a rule that only allows the users' email addresses. Cloudflare
then asks for a code received by email before even showing the app. It is free for up to 50 users.

## 6. After the first start

1. Log in with the administrator account.
2. **Mon compte > Double authentification** (My account > Two-factor authentication): enable it by scanning the QR
   code with Google Authenticator or an equivalent app.
3. **Mon compte > Comptes** (Accounts): create the other users' accounts. There is no open sign-up. Each person
   then changes their password and enables their own two-factor authentication.
4. To bring over the data from the old version (Flask + SQLite):
   `python3 tools/export_legacy.py jobs.db config.toml > sauvegarde-ancienne-version.json`, then
   **Mon compte > Importer une sauvegarde** (Import a backup).

## 7. Update

```bash
git pull
docker compose --profile tunnel up -d --build
```

The data lives in the `db-data` Docker volume and is not touched. Schema changes are applied automatically at
startup (Flyway).

## 8. Back up and restore

Backup of the whole database:

```bash
docker compose exec -T db pg_dump -U candidatures candidatures | gzip > backup-$(date +%F).sql.gz
```

A backup is only usable with the matching `APP_SECRET_KEY`: keep both, separately.

Restore on a fresh install (empty database, server stopped):

```bash
docker compose up -d db
gunzip -c backup-2026-11-24.sql.gz | docker compose exec -T db psql -U candidatures candidatures
docker compose --profile tunnel up -d
```

Each user can also download their own data from **Mon compte > Télécharger une sauvegarde** (a JSON file that can
be imported back; API keys are not included).

## 9. Useful commands

| Action | Command |
|---|---|
| Container status | `docker compose ps` |
| Server logs | `docker compose logs -f app` |
| Job search logs | `docker compose logs -f scraper` |
| Restart the server | `docker compose restart app` |
| Stop everything (data kept) | `docker compose --profile tunnel down` |
| Stop everything **and erase the database** | `docker compose --profile tunnel down -v` |
| Open an SQL console | `docker compose exec db psql -U candidatures` |

## 10. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `app` restarts in a loop, "password authentication failed" in its logs | `POSTGRES_PASSWORD` changed after the database was created | put the old value back |
| Cannot log in with the password from `.env` | the account already existed: `.env` is only read at creation | use the current password, or have an administrator change it |
| "Trop de tentatives" (too many attempts) at login | 8 failures lock for 5 minutes | wait |
| Error 503 when enabling two-factor authentication or saving an API key | `APP_SECRET_KEY` is empty | fill it in, then `docker compose up -d` |
| Error 502 on the domain | wrong "public hostname", or `app` stopped | check `http://app:8080` in the tunnel and `docker compose ps` |
| `cloudflared` does not start | profile forgotten or empty token | `--profile tunnel` and `CLOUDFLARE_TUNNEL_TOKEN` |
| A site returns 0 offers on every search | the site changed its HTML, or is rate limiting | see `docker compose logs scraper`; the script is in `scraper/scrapers/` |
| Two-factor phone lost | | an administrator removes 2FA in **Comptes**; for the only administrator, see below |

Remove two-factor authentication from the only administrator:

```bash
docker compose exec db psql -U candidatures -c \
  "UPDATE users SET totp_secret = NULL, session_version = session_version + 1 WHERE username = 'admin'"
```

## Advanced options

These variables are set in the `environment` section of the `app` service in `docker-compose.yml`.

| Variable | Role |
|---|---|
| `APP_CLIENT_IP_HEADER` | header carrying the visitor's address. `CF-Connecting-IP` by default, to keep behind Cloudflare. Behind another proxy, use the header that proxy writes itself; without a proxy, leave it empty. |
| `LANGUAGETOOL_URL` | the résumé spell checker sends text to api.languagetool.org. To keep everything in-house, run a LanguageTool server and put its address here (`http://…/v2/check`). |

Job searches are sent from the IP address of the machine hosting the app, not from Cloudflare.
