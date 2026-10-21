"""Détection des compétences dans le texte des offres."""
import re
import unicodedata

# Mots courants en français : on exige la casse exacte pour éviter les faux positifs
CASE_SENSITIVE = {"Vue", "Go", "Excel", "Swift", "Spring", "Rust"}


def fold(text):
    """Enlève les accents : "Cybersecurité", "CYBERSÉCURITÉ" et "cybersécurité" doivent correspondre."""
    return "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))


def build_patterns(skills):
    patterns = []
    for s in skills:
        flags = 0 if len(s) <= 3 or s in CASE_SENSITIVE else re.I
        # une seule lettre (C, R) : on exclut aussi "R&D", "R.2311"...
        after = r"[\w+#'’&.]" if len(s) == 1 else r"[\w+#'’]"
        patterns.append((s, re.compile(rf"(?<![\w+#.]){re.escape(fold(s))}(?!{after})", flags)))
    return patterns


def extract(text, patterns):
    if not text:
        return []
    text = fold(text)
    return [name for name, p in patterns if p.search(text)]


def merge(*lists):
    """Union sans doublons à la casse près (JavaScript / Javascript)."""
    out = {}
    for lst in lists:
        for s in lst:
            out.setdefault(s.lower(), s)
    return sorted(out.values(), key=str.lower)
