# Détails techniques

Présentation de l'application : [README](../README.md). Ce document décrit comment elle est construite.

Voir aussi : [déploiement](deploiement.md), [lancement en développement](developpement.md),
[carte de la base de données](base-de-donnees.md).

## Technologies

| Partie | Technologie | Dossier |
|---|---|---|
| Interface | Angular 22 (composants autonomes, signaux, CDK drag-drop), compilée avec Bun | `frontend/` |
| Serveur | Spring Boot 4 (Java 21), Spring Security, JDBC + Flyway | `backend/` |
| Base | PostgreSQL 17 | — |
| PDF du CV | Apache PDFBox (génération et import) | `backend/…/cv/` |
| Recherche d'offres | service Python isolé (conteneur à part, sans secret) | `scraper/` |
| Déploiement | Docker Compose + tunnel Cloudflare (HTTPS) | `Dockerfile`, `docker-compose.yml` |

## Architecture

```
navigateur ──HTTPS──> Cloudflare ──tunnel──> cloudflared ──> app (Spring Boot + interface) ──> db (PostgreSQL)
                                                                │
                                                                ├──> scraper (Python) ──> LinkedIn, HelloWork, Indeed
                                                                └──> SerpAPI (Google Jobs), LanguageTool, DeepL
```

- **`app`** sert l'interface Angular compilée et l'API (`/api/…`). L'authentification repose sur une session
  (cookie) et un jeton CSRF.
- **`db`** n'est joignable que par `app`, sur un réseau Docker interne sans accès à Internet.
- **`scraper`** reçoit de `app` la liste des recherches à faire et renvoie les offres au fil de l'eau. Il ne
  connaît ni la base ni aucun secret.
- **`cloudflared`** ouvre une connexion sortante vers Cloudflare : aucun port n'est exposé sur Internet.

Chaque utilisateur a son compte : offres, CV et paramètres sont rattachés à `user_id` dans toutes les tables.

## Recherche d'offres

| Site | Méthode | Remarques |
|---|---|---|
| LinkedIn | endpoints publics « guest » | sans compte ; LinkedIn limite le débit (429), le script attend et réessaie |
| HelloWork | page de recherche + JSON-LD de chaque offre | fournit aussi des compétences |
| Indeed | librairie `python-jobspy` | |
| Google Jobs | API SerpAPI, appelée par le serveur Java | clé gratuite sur serpapi.com (100 recherches/mois) |

Le plan de recherche combine les recherches, tags et compétences des paramètres avec chaque zone et chaque site
(`jobs/SearchPlan.java`). Les offres sont dédoublonnées par lien : une offre déjà connue garde son statut et ses
notes, seuls ses tags et zones sont complétés. Compétences, catégories et pays sont détectés par le serveur à
partir du texte de l'offre.

Les recherches partent de l'adresse IP de la machine qui héberge l'application (pas de Cloudflare). Les sites
changent leur HTML de temps en temps : si un site renvoie 0 offre sur toutes les recherches, c'est probablement
son script dans `scraper/scrapers/` qui doit être mis à jour.

## CV

Le PDF est produit par un moteur de mise en page écrit pour l'application (`cv/pdf/`), avec les polices standard
du format PDF : le texte reste sélectionnable et dans l'ordre de lecture, ce que demandent les logiciels RH (ATS).
Si le contenu dépasse le nombre de pages autorisé, la mise en page est resserrée automatiquement. L'import d'un
PDF existant repose sur une analyse heuristique du texte (`cv/CvImport.java`).

Le correcteur envoie le texte rédigé à api.languagetool.org (ou à un serveur LanguageTool interne, variable
`LANGUAGETOOL_URL`). La traduction utilise l'API DeepL avec la clé de l'utilisateur.

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

## Organisation du code

Voir [developpement.md](developpement.md#organisation-du-code).
