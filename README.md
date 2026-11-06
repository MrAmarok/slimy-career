# Recherche d'offres + kanban

Récupère les offres sur LinkedIn, HelloWork, Indeed et Google Jobs à partir de mots-clés,
les stocke dans une base SQLite (`jobs.db`) et les affiche dans un kanban.

## Installation

```bash
uv venv .venv && uv pip install -r requirements.txt   # ou: python -m venv .venv && .venv/bin/pip install -r requirements.txt
```

## Utilisation

1. Règle les recherches dans l'onglet « Paramètres » du kanban (ou directement dans `config.toml`) :
   recherches (`searches`), villes / régions (`locations`), rayon, sites, compétences, catégories.
   Chaque recherche est lancée dans chaque ville / région.
2. Récupère les offres :
   ```bash
   .venv/bin/python scrape.py                          # toutes les recherches de la config
   .venv/bin/python scrape.py -s "stage rust" -n 10    # recherche ponctuelle
   .venv/bin/python scrape.py --sites linkedin hellowork
   .venv/bin/python scrape.py -l Toulouse -l "Île-de-France"   # villes / régions ponctuelles
   .venv/bin/python scrape.py -j 10 --per-site 3              # plus de recherches en parallèle
   ```
3. Lance le kanban : `.venv/bin/python app.py` puis ouvre http://localhost:5000
   - glisse-dépose les cartes entre colonnes (À trier, Intéressé, Postulé, En attente, Entretien, Refusé, Pas intéressé)
   - clic sur une carte : description, lien, notes, statut, tags et compétences modifiables, suppression
   - onglet « Paramètres » : modifier les tags de recherche, les compétences, les catégories automatiques et les options (écrit dans `config.toml`)
   - barre latérale de filtres : catégories automatiques (Cybersécurité, DevOps / Cloud, Data / IA…), texte, pays → ville, entreprise, site, tags de recherche, zone de recherche, compétences
   - cartes triées par défaut de la plus récente à la plus ancienne ; autres tris : pays puis ville, entreprise, ville, titre
   - onglet « Entreprises » : nombre d'offres et de candidatures par entreprise (clic = filtre le kanban)
   - bouton « Chercher des offres » pour lancer le scraping depuis l'interface

## Éditeur de CV (onglet « CV », http://localhost:5000/cv)

- sections : informations (+ photo), profil, expérience pro, projets, formation, compétences techniques
  (par catégorie), soft skills, langues, certifications, centres d'intérêt ; ordre des sections réglable
- aperçu PDF en direct, enregistrement automatique (table `cvs` de `jobs.db`), plusieurs CV possibles
- « Adapter à une offre » : compare le CV aux compétences d'une offre du tableau, ajoute celles qui
  manquent en un clic et crée une copie du CV dédiée à cette offre (bouton « Adapter mon CV » dans le détail d'une offre)
- « Exporter en PDF » : PDF généré par `cv_pdf.py` (reportlab), pensé pour les logiciels RH (ATS) :
  une colonne, vrai texte en police standard, aucun tableau, titres de sections classiques, dates MM/AAAA,
  liens écrits en clair, métadonnées remplies. La photo est une image à part, ignorée par les ATS.
  Trois styles (barre d'aperçu) : « Moderne » (bandeau de couleur, titres avec barre, dates en pastille),
  « Tech » (inspiré des CV informatiques une page type Jake's Resume : police à empattements, en-tête centré,
  titres en capitales soulignés, compétences en lignes « Catégorie : … ») et « Classique » (sobre). Les décorations
  sont des formes dessinées : le texte lu par les ATS est le même. Le nombre de pages est affiché (viser 1 page pour un stage).
- ordres de sections conseillés (Mise en page) : « Étudiant / stage » (formation d'abord, par défaut),
  « Junior tech » (compétences d'abord), « Expérimenté » (expérience d'abord) ; catégories de compétences
  courantes en informatique ajoutables en un clic (Langages, Frameworks, Bases de données, DevOps / Cloud…)
- « Importer un PDF » (ou glisser-déposer un PDF sur la page) : crée un nouveau CV à partir d'un CV existant
  (`cv_import.py` : titres de sections reconnus en FR / EN, coordonnées, périodes = début d'une expérience,
  photo de la 1re page). Lecture automatique à relire ; un PDF scanné (image sans texte) ne peut pas être lu.
- « Vérifier la lecture ATS » : affiche le texte réellement extrait du PDF + une liste de vérifications

Les recherches tournent en parallèle : `parallel_searches` à la fois (6 par défaut), dont au plus
`per_site` sur un même site (2 par défaut) pour éviter les blocages (429). Réglable dans `config.toml`
ou dans l'onglet « Paramètres ».

Relancer le scraping n'écrase rien : une offre déjà connue (même lien) garde son statut et ses notes,
seuls ses tags sont complétés.

## Sources

| Site | Méthode | Remarques |
|---|---|---|
| LinkedIn | endpoints publics "guest" | sans compte ; LinkedIn limite le débit (429), le script attend et réessaie |
| HelloWork | page de recherche + JSON-LD de chaque offre | fournit aussi des compétences |
| Indeed | librairie `python-jobspy` | |
| Google Jobs | API SerpAPI | Google bloque le scraping direct : clé gratuite sur serpapi.com (100 recherches/mois) dans `config.toml` ou `SERPAPI_KEY` |

Les sites changent leur HTML de temps en temps : si un site renvoie 0 offre, c'est probablement
le scraper correspondant dans `scrapers/` qui doit être mis à jour.

## Base de données

Table `jobs` : `title, company, location, description, skills (JSON), tags (JSON), link (unique),
source, contract, date_posted, status, notes, created_at, updated_at`.

```bash
sqlite3 jobs.db "SELECT title, company, status FROM jobs WHERE status = 'postule'"
```
