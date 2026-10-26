"""Ville et pays à partir des formats de localisation des différents sites."""
import re

# nom ou code -> nom affiché
COUNTRIES = {
    "france": "France", "fr": "France",
    "belgique": "Belgique", "belgium": "Belgique", "be": "Belgique",
    "suisse": "Suisse", "switzerland": "Suisse", "ch": "Suisse",
    "luxembourg": "Luxembourg", "lu": "Luxembourg",
    "monaco": "Monaco", "mc": "Monaco",
    "canada": "Canada", "ca": "Canada",
    "allemagne": "Allemagne", "germany": "Allemagne", "deutschland": "Allemagne", "de": "Allemagne",
    "espagne": "Espagne", "spain": "Espagne", "es": "Espagne",
    "italie": "Italie", "italy": "Italie", "it": "Italie",
    "royaume-uni": "Royaume-Uni", "united kingdom": "Royaume-Uni", "uk": "Royaume-Uni", "gb": "Royaume-Uni",
    "pays-bas": "Pays-Bas", "netherlands": "Pays-Bas", "nl": "Pays-Bas",
    "irlande": "Irlande", "ireland": "Irlande", "ie": "Irlande",
    "portugal": "Portugal", "pt": "Portugal",
    "maroc": "Maroc", "morocco": "Maroc", "ma": "Maroc",
    "tunisie": "Tunisie", "tunisia": "Tunisie", "tn": "Tunisie",
    "états-unis": "États-Unis", "united states": "États-Unis", "usa": "États-Unis", "us": "États-Unis",
}


def normalize_country(name):
    return COUNTRIES.get((name or "").strip().lower())


def country_of(location, default=None):
    """Pays si la localisation l'indique ("Lyon, ARA, France", "Lyon - 69"), sinon `default`."""
    if location:
        parts = [p.strip() for p in location.split(",")]
        if len(parts) > 1 and (c := normalize_country(parts[-1])):
            return c
        if c := normalize_country(location):
            return c
        if re.search(r"\s-\s(\d{2}|2[AB]|97\d)$", location):  # département français (HelloWork)
            return "France"
    return default


def city_of(location):
    """"Toulouse, Occitanie, France" / "Lyon - 69" / "Paris 8e" / "Paris et périphérie" -> ville."""
    if not location:
        return None
    city = location.split(",")[0]
    if normalize_country(city):
        return None
    city = re.sub(r"\s+-\s+\d+[AB]?$", "", city)              # " - 69" (département HelloWork)
    city = re.sub(r"\s+\d{1,2}(er|e|ème)$", "", city)           # arrondissement
    city = re.sub(r"\s+et périphérie$", "", city, flags=re.I)
    return city.strip() or None
