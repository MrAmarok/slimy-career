# Déploiement

L'application se déploie avec Docker Compose : une base PostgreSQL, le serveur (qui sert aussi l'interface),
le service de recherche d'offres, et un tunnel Cloudflare pour l'accès depuis Internet en HTTPS.

```
Internet ──HTTPS──> Cloudflare ──tunnel sortant──> cloudflared ──> app:8080 ──> db (PostgreSQL)
                                                                      └──────> scraper:8000 ──> sites d'offres
```

Aucun port n'est à ouvrir sur la box : c'est `cloudflared` qui se connecte à Cloudflare, pas l'inverse.

## 1. Ce qu'il faut

| Besoin | Détail |
|---|---|
| Une machine allumée en permanence | Linux de préférence ; 2 Go de mémoire suffisent |
| Docker Engine avec Compose v2 | `docker compose version` doit répondre |
| Git | pour récupérer le code et le mettre à jour |
| Un nom de domaine géré par Cloudflare | seulement pour l'accès depuis Internet |

## 2. Récupérer le code

```bash
git clone https://github.com/MrAmarok/personnal-shit.git candidatures
cd candidatures
```

## 3. Configurer

```bash
cp .env.example .env
chmod 600 .env
```

Puis remplir `.env` :

| Variable | Obligatoire | Valeur |
|---|---|---|
| `POSTGRES_PASSWORD` | oui | mot de passe de la base : `openssl rand -hex 24` |
| `APP_ADMIN_USERNAME` | oui | identifiant du premier compte (`admin`) |
| `APP_ADMIN_PASSWORD` | oui | mot de passe du premier compte, long et unique |
| `APP_SECRET_KEY` | oui | clé de chiffrement : `openssl rand -hex 32` |
| `CLOUDFLARE_TUNNEL_TOKEN` | pour Internet | jeton du tunnel (étape 5) |
| `SERPAPI_KEY` | non | clé SerpAPI commune à tous les comptes, pour Google Jobs |
| `APP_PORT` | non | port local, `8080` par défaut |
| `TZ` | non | fuseau horaire, `Europe/Paris` par défaut |

À savoir :

- **`APP_SECRET_KEY` ne doit jamais changer ni être perdue.** Elle chiffre les doubles authentifications et les
  clés d'API rangées en base ; sans elle, ces données deviennent illisibles. Garde-en une copie hors de la machine
  (gestionnaire de mots de passe).
- `APP_ADMIN_USERNAME` et `APP_ADMIN_PASSWORD` ne servent qu'au tout premier démarrage, quand la base ne contient
  aucun compte. Les changer ensuite n'a aucun effet : le mot de passe se change dans l'application.
- `POSTGRES_PASSWORD` est lui aussi fixé au premier démarrage de la base. Le changer dans `.env` après coup empêche
  le serveur de se connecter.
- La clé DeepL (traduction du CV) ne se met pas dans `.env` : chaque utilisateur saisit la sienne dans l'application.

## 4. Démarrer en local

```bash
docker compose up -d --build
docker compose ps
```

La première construction prend plusieurs minutes. Quand `app` est démarré, ouvrir <http://localhost:8080> et se
connecter avec le compte administrateur.

Le port n'est ouvert que sur la machine elle-même (`127.0.0.1`). Pour y accéder depuis un autre poste sans passer
par Internet, un tunnel SSH suffit : `ssh -L 8080:localhost:8080 utilisateur@machine`.

## 5. Ouvrir l'accès depuis Internet (tunnel Cloudflare)

1. Dans le tableau de bord Cloudflare : **Zero Trust > Networks > Tunnels > Create a tunnel**, type « Cloudflared ».
2. Choisir l'environnement « Docker ». La commande affichée contient `--token eyJ…` : copier la valeur du jeton dans
   `CLOUDFLARE_TUNNEL_TOKEN`.
3. Dans l'onglet **Public Hostname** du tunnel, ajouter un nom (par exemple `candidatures.mondomaine.fr`) avec le
   service de type **HTTP** et l'adresse **`app:8080`**.
4. Démarrer avec le profil `tunnel` :

```bash
docker compose --profile tunnel up -d --build
docker compose logs -f cloudflared     # « Registered tunnel connection » : le tunnel est établi
```

Cloudflare fournit et renouvelle le certificat HTTPS. À partir de là, **toutes les commandes `docker compose`
doivent garder `--profile tunnel`**, sinon le conteneur `cloudflared` est ignoré.

### Recommandé : Cloudflare Access devant l'application

Sans cela, la page de connexion est visible par tout Internet. Dans **Zero Trust > Access > Applications**, ajouter
une application « Self-hosted » sur le nom de domaine, avec une règle qui n'autorise que les adresses e-mail des
utilisateurs. Cloudflare demande alors un code reçu par e-mail avant même d'afficher l'application. C'est gratuit
jusqu'à 50 utilisateurs.

## 6. Après le premier démarrage

1. Se connecter avec le compte administrateur.
2. **Mon compte > Double authentification** : l'activer en scannant le QR code avec Google Authenticator ou une
   application équivalente.
3. **Mon compte > Comptes** : créer les comptes des autres utilisateurs. Il n'y a pas d'inscription libre. Chaque
   personne change ensuite son mot de passe et active sa double authentification.
4. Pour reprendre les données de l'ancienne version (Flask + SQLite) :
   `python3 tools/export_legacy.py jobs.db config.toml > sauvegarde-ancienne-version.json`, puis
   **Mon compte > Importer une sauvegarde**.

## 7. Mettre à jour

```bash
git pull
docker compose --profile tunnel up -d --build
```

Les données sont dans le volume Docker `db-data` et ne sont pas touchées. Les changements de schéma sont appliqués
automatiquement au démarrage (Flyway).

## 8. Sauvegarder et restaurer

Sauvegarde de toute la base :

```bash
docker compose exec -T db pg_dump -U candidatures candidatures | gzip > sauvegarde-$(date +%F).sql.gz
```

Une sauvegarde n'est utilisable qu'avec la `APP_SECRET_KEY` correspondante : conserver les deux, séparément.

Restauration sur une installation neuve (base vide, serveur arrêté) :

```bash
docker compose up -d db
gunzip -c sauvegarde-2026-11-24.sql.gz | docker compose exec -T db psql -U candidatures candidatures
docker compose --profile tunnel up -d
```

Chaque utilisateur peut aussi télécharger ses propres données dans **Mon compte > Télécharger une sauvegarde**
(fichier JSON, réimportable ; les clés d'API n'y figurent pas).

## 9. Commandes utiles

| Action | Commande |
|---|---|
| État des conteneurs | `docker compose ps` |
| Journaux du serveur | `docker compose logs -f app` |
| Journaux de la recherche d'offres | `docker compose logs -f scraper` |
| Redémarrer le serveur | `docker compose restart app` |
| Tout arrêter (données conservées) | `docker compose --profile tunnel down` |
| Tout arrêter **et effacer la base** | `docker compose --profile tunnel down -v` |
| Ouvrir une console SQL | `docker compose exec db psql -U candidatures` |

## 10. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `app` redémarre en boucle, « password authentication failed » dans ses journaux | `POSTGRES_PASSWORD` modifié après la création de la base | remettre l'ancienne valeur |
| Impossible de se connecter avec le mot de passe de `.env` | le compte existait déjà : `.env` n'est lu qu'à la création | utiliser le mot de passe actuel, ou le faire changer par un administrateur |
| « Trop de tentatives » à la connexion | 8 échecs bloquent 5 minutes | attendre |
| Erreur 503 en activant la double authentification ou en enregistrant une clé d'API | `APP_SECRET_KEY` vide | la remplir, puis `docker compose up -d` |
| Erreur 502 sur le domaine | « public hostname » mal réglé, ou `app` arrêté | vérifier `http://app:8080` dans le tunnel et `docker compose ps` |
| `cloudflared` ne démarre pas | profil oublié ou jeton vide | `--profile tunnel` et `CLOUDFLARE_TUNNEL_TOKEN` |
| Un site renvoie 0 offre sur toutes les recherches | le site a changé son HTML, ou limite le débit | voir `docker compose logs scraper` ; le script est dans `scraper/scrapers/` |
| Téléphone de double authentification perdu | | un administrateur retire la 2FA dans **Comptes** ; pour le seul administrateur, voir ci-dessous |

Retirer la double authentification du seul administrateur :

```bash
docker compose exec db psql -U candidatures -c \
  "UPDATE users SET totp_secret = NULL, session_version = session_version + 1 WHERE username = 'admin'"
```

## Options avancées

Ces variables se règlent dans la section `environment` du service `app` de `docker-compose.yml`.

| Variable | Rôle |
|---|---|
| `APP_CLIENT_IP_HEADER` | en-tête qui porte l'adresse du visiteur. `CF-Connecting-IP` par défaut, à garder derrière Cloudflare. Derrière un autre proxy, mettre l'en-tête que ce proxy écrit lui-même ; sans proxy, laisser vide. |
| `LANGUAGETOOL_URL` | le correcteur du CV envoie le texte à api.languagetool.org. Pour tout garder en interne, lancer un serveur LanguageTool et indiquer ici son adresse (`http://…/v2/check`). |

Les recherches d'offres partent de l'adresse IP de la machine qui héberge l'application, pas de Cloudflare.
