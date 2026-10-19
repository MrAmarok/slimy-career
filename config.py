"""Lecture / écriture de config.toml (modifiable aussi depuis l'interface, onglet Paramètres)."""
import json
import tomllib
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config.toml"

DEFAULTS = {
    "locations": ["France"],
    "country": "France",
    "radius_km": 30,
    "results_per_search": 25,
    "max_age_days": 30,
    "parallel_searches": 6,
    "per_site": 2,
    "searches": [],
    "contract": "stage",
    "search_tags": [],
    "search_skills": [],
    "sites": ["linkedin", "hellowork", "indeed", "google"],
    "serpapi_key": "",
    "skills": [],
    "categories": {
        "Cybersécurité": ["cybersécurité", "cyber-sécurité", "cybersecurity", "cyber", "CyberSOC", "cyberdéfense",
                          "DevSecOps", "sécurité informatique", "sécurité des systèmes d'information", "RSSI", "SOC", "SIEM", "pentest",
                          "test d'intrusion", "GRC", "ISO 27001", "forensic", "vulnérabilité", "cryptographie", "IAM", "EDR"],
        "DevOps / Cloud": ["DevOps", "DevSecOps", "SRE", "cloud", "Kubernetes", "Docker", "Terraform", "Ansible", "CI/CD",
                           "AWS", "Azure", "GCP", "infrastructure as code", "MLOps", "GitOps"],
        "Data / IA": ["data science", "datascience", "analyse de données", "machine learning", "deep learning", "IA", "intelligence artificielle", "IA générative", "LLM",
                      "data scientist", "data engineer", "data analyst", "BI", "NLP", "computer vision", "big data"],
        "Développement web": ["web", "full stack", "fullstack", "full-stack", "frontend", "front-end", "backend", "back-end",
                              "React", "Angular", "Vue.js", "Node.js", "Django", "Symfony", "Laravel"],
        "Développement logiciel": ["développement logiciel", "développeur logiciel", "ingénieur logiciel", "logiciel",
                                   "software", "Python", "Java", "C++", "C#", ".NET", "Rust", "Go"],
        "Mobile": ["mobile", "Android", "iOS", "Flutter", "React Native", "Kotlin", "SwiftUI"],
        "Embarqué": ["embarqué", "embedded", "firmware", "temps réel", "microcontrôleur", "FPGA", "RTOS", "électronique"],
        "Réseaux / Systèmes": ["réseau", "réseaux", "network", "administrateur système", "sysadmin", "Cisco",
                               "Windows Server", "Active Directory", "supervision", "infrastructure"],
    },
}

TEMPLATE = """\
# Fichier modifiable à la main ou depuis l'interface (onglet Paramètres).

# Villes / régions où chercher : chaque recherche est lancée dans chaque zone ("Paris", "Lyon", "Occitanie", "France"…).
# Les offres gardent la zone qui les a trouvées (filtre "Zone de recherche" du kanban).
locations = {locations}
country = {country}              # pays des recherches (Indeed) et pays par défaut des offres
radius_km = {radius_km}                # rayon autour d'une ville (LinkedIn et Indeed)

results_per_search = {results_per_search}          # par site, par recherche et par zone
max_age_days = {max_age_days}                # ignore les offres plus vieilles (quand le site donne la date)
parallel_searches = {parallel_searches}           # recherches lancées en même temps
per_site = {per_site}                    # recherches simultanées max sur un même site (trop haut = blocage 429)

# Recherches complètes, envoyées telles quelles à tous les sites (servent aussi de tag sur les offres trouvées).
searches = {searches}

# Recherches "larges" : chaque tag et chaque compétence devient une petite recherche séparée,
# précédée du type de contrat (ex : "stage devops", "stage python"). Une offre n'a pas besoin de tout cocher :
# il suffit qu'elle corresponde à une de ces recherches. Un tag qui est un nom de catégorie ("DevOps / Cloud")
# est découpé sur les "/" ("stage DevOps", "stage Cloud").
contract = {contract}
search_tags = {search_tags}
search_skills = {search_skills}

# Sites activés : linkedin, hellowork, indeed, google
sites = {sites}

# Google Jobs n'est accessible que via SerpAPI (100 recherches/mois gratuites : https://serpapi.com)
# Mets ta clé ici ou dans la variable d'environnement SERPAPI_KEY. Sans clé, Google est ignoré.
serpapi_key = {serpapi_key}

# Compétences détectées automatiquement dans les descriptions
skills = {skills}

# Catégories automatiques : une offre y entre si son titre contient un des mots-clés,
# ou si sa description en contient au moins 3 différents.
[categories]
{categories}
"""


def _toml(value):
    # json produit des chaînes / entiers / listes compatibles TOML
    if isinstance(value, list):
        if not value:
            return "[]"
        return "[\n" + "".join(f"  {json.dumps(v, ensure_ascii=False)},\n" for v in value) + "]"
    return json.dumps(value, ensure_ascii=False)


def _toml_table(table):
    return "\n".join(f"{json.dumps(k, ensure_ascii=False)} = {_toml(v)}" for k, v in table.items())


def load():
    cfg = dict(DEFAULTS)
    if CONFIG_PATH.exists():
        cfg.update(tomllib.loads(CONFIG_PATH.read_text()))
    if "location" in cfg:  # ancienne version : une seule localisation
        old = cfg.pop("location")
        if old and cfg["locations"] == DEFAULTS["locations"]:
            cfg["locations"] = [old]
    return cfg


def _clean_list(values):
    out, seen = [], set()
    for v in values or []:
        v = str(v).strip()
        if v and v.lower() not in seen:
            seen.add(v.lower())
            out.append(v)
    return out


def save(data):
    """Valide, écrit config.toml et retourne la config enregistrée."""
    cfg = load()
    for key in ("country", "serpapi_key", "contract"):
        if key in data:
            cfg[key] = str(data[key]).strip()
    for key in ("results_per_search", "max_age_days", "radius_km", "parallel_searches", "per_site"):
        if key in data:
            cfg[key] = max(1, int(data[key]))
    for key in ("searches", "locations", "sites", "skills", "search_tags", "search_skills"):
        if key in data:
            cfg[key] = _clean_list(data[key])
    if not cfg["locations"]:
        cfg["locations"] = [cfg["country"] or "France"]
    if "categories" in data:
        cats = {}
        for name, kws in dict(data["categories"]).items():
            name = str(name).strip()
            if name and (kws := _clean_list(kws)):
                cats[name] = kws
        cfg["categories"] = cats
    values = {k: _toml(cfg[k]) for k in DEFAULTS if k != "categories"}
    CONFIG_PATH.write_text(TEMPLATE.format(**values, categories=_toml_table(cfg["categories"])))
    return cfg
