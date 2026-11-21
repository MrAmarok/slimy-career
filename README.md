# Candidatures — recherche d'offres, kanban et éditeur de CV

Récupère les offres sur LinkedIn, HelloWork, Indeed et Google Jobs à partir de mots-clés, les range dans un
kanban de candidatures, et fournit un éditeur de CV avec export PDF lisible par les logiciels RH (ATS).

Chaque utilisateur a son compte : son tableau, ses CV et ses paramètres de recherche sont séparés.

| Partie | Technologie | Dossier |
|---|---|---|
| Interface | Angular 22 (composants autonomes, signaux, CDK drag-drop) | `frontend/` |
| Serveur | Spring Boot 4 (Java 21), Spring Security, JDBC + Flyway | `backend/` |
| Base | PostgreSQL 17 | — |
| PDF du CV | Apache PDFBox (génération et import) | `backend/…/cv/` |
| Recherche d'offres | service Python isolé (conteneur à part, sans secret) | `scraper/` |
| Déploiement | Docker Compose + tunnel Cloudflare (HTTPS) | `Dockerfile`, `docker-compose.yml` |

## Déploiement

Il faut Docker, et pour l'accès depuis Internet : un nom de domaine géré par Cloudflare.

1. **Configuration** : `cp .env.example .env`, puis remplir `POSTGRES_PASSWORD` (`openssl rand -hex 24`),
   `APP_ADMIN_PASSWORD` (mot de passe du premier compte, `admin` par défaut) et `APP_SECRET_KEY`
   (`openssl rand -hex 32`, à ne jamais changer ensuite : elle chiffre les secrets rangés en base). `SERPAPI_KEY` (Google Jobs) est facultative.
   La clé DeepL (traduction du CV) ne se met pas ici : chaque utilisateur saisit la sienne dans l'application.
2. **Démarrage local** : `docker compose up -d --build`, puis ouvrir http://localhost:8080. Le port n'est ouvert
   que sur la machine elle-même.
3. **Tunnel Cloudflare** : dans Zero Trust > Networks > Tunnels, créer un tunnel, choisir « Docker » et copier le
   jeton (la valeur après `--token`) dans `CLOUDFLARE_TUNNEL_TOKEN`. Ajouter un « public hostname » qui pointe vers
   le service `http://app:8080`. Puis : `docker compose --profile tunnel up -d --build`.
   Cloudflare fournit le certificat HTTPS ; aucun port n'est à ouvrir sur la box.
4. **Comptes** : se connecter avec le compte administrateur, puis « Mon compte » > « Comptes » pour créer les
   autres utilisateurs (il n'y a pas d'inscription libre). Chacun peut changer son mot de passe au même endroit.
5. **Double authentification** : dans « Mon compte », chacun peut l'activer en scannant un QR code avec Google
   Authenticator (ou une application équivalente). À faire au moins pour le compte administrateur.

Mise à jour : `git pull` puis `docker compose --profile tunnel up -d --build`. Les données sont dans le volume
Docker `db-data` et ne sont pas touchées.

### Reprendre les données de l'ancienne version (Flask + SQLite)

```bash
python3 tools/export_legacy.py jobs.db config.toml > sauvegarde-ancienne-version.json
```

Puis dans l'application : « Mon compte » > « Importer une sauvegarde ». Offres (statuts, notes), CV et paramètres de
recherche sont repris ; les clés d'API ne sont pas exportées (elles vont dans `.env`).

### Sauvegardes

- Par compte : « Mon compte » > « Télécharger une sauvegarde » (fichier JSON, réimportable).
- Toute la base : `docker compose exec db pg_dump -U candidatures candidatures > sauvegarde.sql`

## Sécurité

- **Sessions** : changer un mot de passe ferme toutes les sessions du compte ; un compte supprimé est déconnecté
  à sa requête suivante. Activer ou retirer la double authentification ferme les autres sessions.
- **Double authentification** : code à 6 chiffres (TOTP) demandé après le mot de passe ; un code ne sert qu'une fois.
  Le secret est chiffré en base avec `APP_SECRET_KEY`. Téléphone perdu : un administrateur retire la double
  authentification du compte dans « Comptes ». Si c'est le seul administrateur :
  `docker compose exec db psql -U candidatures -c "UPDATE users SET totp_secret = NULL, session_version = session_version + 1 WHERE username = 'admin'"`
- **Tentatives de connexion** : 8 échecs bloquent 5 minutes (par adresse et identifiant). L'adresse du visiteur est
  lue dans l'en-tête `CF-Connecting-IP`, écrit par Cloudflare (`APP_CLIENT_IP_HEADER`), jamais dans `X-Forwarded-For`.
- **Limites** : taille des requêtes (1 Mo, 3 Mo pour un CV, 30 Mo pour l'import d'une sauvegarde) et nombre de
  requêtes par minute, avec des plafonds plus bas pour les opérations coûteuses (`RequestLimitFilter`).
- **Recherche d'offres isolée** : le service `scraper` visite des sites tiers, donc il tourne dans un conteneur
  sans aucun secret, sans accès à la base, en lecture seule. Google Jobs, qui demande une clé, est interrogé par le
  serveur lui-même.
- **Clés d'API** (DeepL, SerpAPI) : chiffrées en base avec `APP_SECRET_KEY`, jamais renvoyées au navigateur ni
  incluses dans les sauvegardes.
- **Dépendances** : bibliothèques Python figées avec leurs empreintes (`scraper/requirements.txt`, généré depuis
  `requirements.in`), images Docker épinglées, mises à jour proposées par Dependabot (`.github/dependabot.yml`).
- **Navigateur** : Content-Security-Policy (aucun script externe ou en ligne), liens d'offres limités à
  `http(s)://`.

## Utilisation

- **Tableau** : glisser-déposer les cartes entre colonnes (À trier, Intéressé, Postulé, En attente, Entretien,
  Refusé, Pas intéressé) ; clic sur une carte pour la description, le lien, les notes, les tags et compétences ;
  filtres par catégorie, texte, pays, ville, entreprise, site, tags, zone et compétences ; sélection multiple.
- **Chercher des offres** : lance la recherche sur les sites choisis, avec suivi en direct. Relancer n'écrase rien :
  une offre déjà connue (même lien) garde son statut et ses notes, seuls ses tags et zones sont complétés.
- **Ajouter une offre** : saisie manuelle (seul le titre est obligatoire) ; compétences, catégorie et pays sont détectés.
- **Entreprises** : nombre d'offres et de candidatures par entreprise.
- **Paramètres** : zones, recherches, sites, compétences à détecter, catégories automatiques (par utilisateur).
- **CV** : sections réordonnables, trois styles (Moderne, Tech, Classique), aperçu PDF en direct, une page par
  défaut (mise en page resserrée automatiquement si besoin), import d'un CV PDF existant, vérification de la lecture
  ATS, correcteur (LanguageTool), traduction en anglais (DeepL), adaptation du CV à une offre du tableau.

Le correcteur envoie le texte rédigé à api.languagetool.org ; pour tout garder en interne, lancer un serveur
LanguageTool et mettre son adresse dans la variable `LANGUAGETOOL_URL` du service `app`.

## Sources d'offres

| Site | Méthode | Remarques |
|---|---|---|
| LinkedIn | endpoints publics « guest » | sans compte ; LinkedIn limite le débit (429), le script attend et réessaie |
| HelloWork | page de recherche + JSON-LD de chaque offre | fournit aussi des compétences |
| Indeed | librairie `python-jobspy` | |
| Google Jobs | API SerpAPI, appelée par le serveur Java | clé gratuite sur serpapi.com (100 recherches/mois) |

Les recherches partent de l'adresse IP de la machine qui héberge l'application (pas de Cloudflare). Les sites
changent leur HTML de temps en temps : si un site renvoie 0 offre sur toutes les recherches, c'est probablement
son script dans `scraper/scrapers/` qui doit être mis à jour.

## Développement

```bash
# base de données
docker run -d --name candidatures-db -p 5432:5432 -e POSTGRES_DB=candidatures \
  -e POSTGRES_USER=candidatures -e POSTGRES_PASSWORD=candidatures postgres:17-alpine

# service de recherche d'offres (http://localhost:8000)
python3 -m venv .venv && .venv/bin/pip install -r scraper/requirements.txt
.venv/bin/python scraper/server.py

# serveur (http://localhost:8080) — Java 21 et Maven
cd backend && APP_ADMIN_PASSWORD=changeme-123 APP_SECRET_KEY=dev-seulement mvn spring-boot:run

# interface (http://localhost:4200, les appels /api sont relayés vers le serveur)
cd frontend && npm install && npm start
```

Tests du serveur : `cd backend && mvn test`. Sans Java ni Maven sur la machine :

```bash
docker run --rm -v "$PWD/backend":/w -w /w maven:3.9-eclipse-temurin-21 mvn test
```

### Organisation du serveur (`backend/src/main/java/app/candidatures/`)

| Paquet | Rôle |
|---|---|
| `auth` | comptes, sessions et leur révocation, double authentification, CSRF, limites de requêtes |
| `jobs` | offres, détection des compétences / catégories / pays, plan de recherche |
| `scrape` | appelle le service de recherche, interroge Google Jobs, enregistre les offres trouvées |
| `settings` | paramètres de recherche de chaque utilisateur |
| `cv` | CV, import PDF, correcteur et traduction ; `cv/pdf` : moteur de mise en page et génération du PDF |
| `account` | export / import des données d'un compte |

Le schéma de la base est dans `backend/src/main/resources/db/migration/` (Flyway).
