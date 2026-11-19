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
| Recherche d'offres | scripts Python lancés par le serveur | `scraper/` |
| Déploiement | Docker Compose + tunnel Cloudflare (HTTPS) | `Dockerfile`, `docker-compose.yml` |

## Déploiement

Il faut Docker, et pour l'accès depuis Internet : un nom de domaine géré par Cloudflare.

1. **Configuration** : `cp .env.example .env`, puis remplir `POSTGRES_PASSWORD` (`openssl rand -hex 24`) et
   `APP_ADMIN_PASSWORD` (mot de passe du premier compte, `admin` par défaut). Les clés `DEEPL_API_KEY`
   (traduction du CV) et `SERPAPI_KEY` (Google Jobs) sont facultatives.
2. **Démarrage local** : `docker compose up -d --build`, puis ouvrir http://localhost:8080. Le port n'est ouvert
   que sur la machine elle-même.
3. **Tunnel Cloudflare** : dans Zero Trust > Networks > Tunnels, créer un tunnel, choisir « Docker » et copier le
   jeton (la valeur après `--token`) dans `CLOUDFLARE_TUNNEL_TOKEN`. Ajouter un « public hostname » qui pointe vers
   le service `http://app:8080`. Puis : `docker compose --profile tunnel up -d --build`.
   Cloudflare fournit le certificat HTTPS ; aucun port n'est à ouvrir sur la box.
4. **Comptes** : se connecter avec le compte administrateur, puis « Mon compte » > « Comptes » pour créer les
   autres utilisateurs (il n'y a pas d'inscription libre). Chacun peut changer son mot de passe au même endroit.

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
| Google Jobs | API SerpAPI | clé gratuite sur serpapi.com (100 recherches/mois) |

Les recherches partent de l'adresse IP de la machine qui héberge l'application (pas de Cloudflare). Les sites
changent leur HTML de temps en temps : si un site renvoie 0 offre sur toutes les recherches, c'est probablement
son script dans `scraper/scrapers/` qui doit être mis à jour.

## Développement

```bash
# base de données
docker run -d --name candidatures-db -p 5432:5432 -e POSTGRES_DB=candidatures \
  -e POSTGRES_USER=candidatures -e POSTGRES_PASSWORD=candidatures postgres:17-alpine

# scripts de recherche
python3 -m venv .venv && .venv/bin/pip install -r scraper/requirements.txt

# serveur (http://localhost:8080) — Java 21 et Maven
cd backend && APP_ADMIN_PASSWORD=changeme-123 SCRAPER_PYTHON=../.venv/bin/python mvn spring-boot:run

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
| `auth` | comptes, connexion par session, CSRF, création des comptes par l'administrateur |
| `jobs` | offres, détection des compétences / catégories / pays, plan de recherche |
| `scrape` | lance `scraper/scrape.py`, suit sa progression, enregistre les offres trouvées |
| `settings` | paramètres de recherche de chaque utilisateur |
| `cv` | CV, import PDF, correcteur et traduction ; `cv/pdf` : moteur de mise en page et génération du PDF |
| `account` | export / import des données d'un compte |

Le schéma de la base est dans `backend/src/main/resources/db/migration/` (Flyway).
