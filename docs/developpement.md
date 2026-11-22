# Lancement en développement

Tout se lance depuis la racine du dépôt, avec [Bun](https://bun.sh) :

```bash
bun run dev
```

Cette commande démarre la base, puis les trois services dans le même terminal, chacun avec son préfixe.
Ouvrir <http://localhost:4200> et se connecter avec `admin` / `changeme-123`. `Ctrl+C` arrête les services ; la base
continue de tourner.

On travaille sur le port 4200 : l'interface se recharge à chaque modification et relaie les appels `/api` vers le
serveur (`frontend/proxy.conf.mjs`).

## Commandes

| Commande | Lance | Adresse |
|---|---|---|
| `bun run dev` | la base, puis les trois services | <http://localhost:4200> |
| `bun run database` | la base PostgreSQL (conteneur Docker `candidatures-dev-db`) | `localhost:5433` |
| `bun run scraper` | le service de recherche d'offres | <http://localhost:8000> |
| `bun run backend` | le serveur Spring Boot | <http://localhost:8080> |
| `bun run frontend` | l'interface Angular | <http://localhost:4200> |
| `bun run database:stop` | arrête la base (données conservées) | |
| `bun run database:reset` | recrée la base, **données de développement effacées** | |
| `bun run test` | les tests du serveur | |
| `bun run build` | compile l'interface dans `frontend/dist/` | |

Pour lancer les services séparément, un terminal par commande : `database` d'abord (elle rend la main une fois la
base prête), puis `scraper`, `backend` et `frontend`. Le service de recherche n'est nécessaire que pour lancer des
recherches d'offres.

Les scripts sont dans `tools/dev.ts`. Au premier lancement, ils installent ce qui manque : l'environnement Python
(`.venv`, avec vérification des empreintes) et les dépendances de l'interface (`bun install`).

## Outils nécessaires

| Outil | Version | Pour |
|---|---|---|
| Bun | 1.4 | les commandes de lancement et l'interface |
| Docker | récente | la base de données |
| Java (JDK) | 21 ou plus | le serveur |
| Maven | 3.9 | le serveur |
| Python | 3.12 | la recherche d'offres |

Node.js n'est pas nécessaire : Angular est compilé et servi par Bun.

## Réglages

Sans rien configurer, tout fonctionne avec des valeurs de développement. Pour les changer, créer un fichier
`.env.dev` à la racine (non versionné) :

| Variable | Défaut | Rôle |
|---|---|---|
| `DEV_DB_PORT` | `5433` | port de la base. Ce n'est pas 5432, souvent déjà pris par une autre base PostgreSQL. Après un changement : `bun run database:reset` |
| `DEV_BACKEND_PORT` | `8080` | port du serveur |
| `DEV_SCRAPER_PORT` | `8000` | port du service de recherche |
| `DEV_FRONTEND_PORT` | `4200` | port de l'interface |
| `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD` | `admin`, `changeme-123` | premier compte, créé seulement quand la base ne contient aucun compte |
| `APP_SECRET_KEY` | `dev-seulement` | chiffrement des secrets rangés en base ; à ne pas changer une fois des secrets enregistrés |
| `SERPAPI_KEY` | vide | Google Jobs ; sans clé, Google est ignoré |
| `LANGUAGETOOL_URL` | api.languagetool.org | correcteur du CV |

Le fichier `.env` du déploiement n'est jamais lu en développement (`bunfig.toml` désactive son chargement par
Bun) : ses secrets ne doivent pas arriver dans les services de développement, en particulier dans le service de
recherche, qui visite des sites tiers.

## Interface : Bun à la place de npm

Dans `frontend/`, les dépendances sont gérées par Bun (`bun.lock`) : `bun install`, `bun add <paquet>`,
`bun update`. Les scripts `bun run start` et `bun run build` font tourner Angular sur Bun. Ne pas utiliser `npm`,
qui recréerait un `package-lock.json`.

## Tests

```bash
bun run test
```

Ils couvrent la détection des compétences, catégories et pays, la génération du PDF et les codes de double
authentification. Ils n'ont pas besoin de la base.

## Sans Java ni Maven

Les tests peuvent tourner dans un conteneur :

```bash
docker run --rm -v "$PWD/backend":/w -w /w maven:3.9-eclipse-temurin-21 mvn test
```

Pour faire tourner l'application sans rien installer d'autre que Docker, voir la section suivante.

## Tester l'image complète

Pour vérifier l'application telle qu'elle sera déployée (interface compilée, embarquée dans le serveur) :

```bash
cp .env.example .env      # remplir POSTGRES_PASSWORD, APP_ADMIN_PASSWORD, APP_SECRET_KEY
docker compose up -d --build
```

L'application est alors sur <http://localhost:8080>. Voir [deploiement.md](deploiement.md). Si le serveur de
développement occupe déjà le port 8080, mettre `APP_PORT=8081` dans `.env`.

## Organisation du code

```
backend/    serveur Spring Boot 4 (Java 21)
frontend/   interface Angular 22
scraper/    service Python de recherche d'offres
tools/      export des données de l'ancienne version
docs/       documentation
```

### Serveur (`backend/src/main/java/app/candidatures/`)

| Paquet | Rôle |
|---|---|
| `auth` | comptes, sessions et leur révocation, double authentification, CSRF, limites de requêtes |
| `jobs` | offres, détection des compétences / catégories / pays, plan de recherche |
| `scrape` | appelle le service de recherche, interroge Google Jobs, enregistre les offres trouvées |
| `settings` | paramètres de recherche de chaque utilisateur |
| `cv` | CV, import PDF, correcteur et traduction ; `cv/pdf` : moteur de mise en page et génération du PDF |
| `account` | export / import des données d'un compte |
| `common` | erreurs, JSON, chiffrement, adresse du visiteur |

### Interface (`frontend/src/app/`)

| Dossier | Rôle |
|---|---|
| `core` | appels au serveur, session, barre de navigation latérale, thème clair / sombre |
| `login` | page de connexion |
| `kanban` | tableau des offres, recherche, paramètres, entreprises |
| `cv` | éditeur de CV |
| `account` | mot de passe, double authentification, sauvegardes, gestion des comptes |

## Tâches courantes

**Modifier le schéma de la base.** Ajouter un fichier `V3__description.sql` dans
`backend/src/main/resources/db/migration/`. Ne jamais modifier une migration déjà appliquée : Flyway refuse de
démarrer si son contenu a changé. Mettre à jour [base-de-donnees.md](base-de-donnees.md).

**Ajouter ou mettre à jour une bibliothèque Python.** Modifier `scraper/requirements.in`, puis régénérer le fichier
verrouillé :

```bash
docker run --rm -v "$PWD/scraper":/w -w /w python:3.12-slim sh -c \
  "pip install -q pip-tools && pip-compile --generate-hashes --strip-extras --no-header requirements.in -o requirements.txt"
```

**Corriger un site qui ne renvoie plus d'offres.** Chaque site a son script dans `scraper/scrapers/`. Google Jobs
est à part : il est interrogé par le serveur (`scrape/GoogleJobs.java`), car il demande une clé et le service de
recherche ne doit recevoir aucun secret.

**Ajouter une route d'API.** Les routes sous `/api/` exigent une session, et un jeton CSRF pour tout ce qui n'est
pas un `GET` (l'interface l'envoie d'elle-même). Les limites de taille et de débit par route sont dans
`auth/RequestLimitFilter.java` ; sans règle particulière, une route reçoit la limite générale (1 Mo, 600 requêtes
par minute).

**Appeler l'API à la main.** La session et le jeton CSRF passent par des cookies :

```bash
curl -s -c cookies.txt http://localhost:8080/api/auth/me
jeton=$(awk '$6 == "XSRF-TOKEN" { print $7 }' cookies.txt)
curl -s -b cookies.txt -c cookies.txt -H "X-XSRF-TOKEN: $jeton" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"changeme-123"}' http://localhost:8080/api/auth/login
curl -s -b cookies.txt http://localhost:8080/api/jobs
```
