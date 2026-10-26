"""Construit la liste des requêtes envoyées aux sites à partir des recherches, tags et compétences."""


def build_queries(searches, contract="", tags=(), skills=(), categories=None):
    """Retourne une liste de (requête, tag) sans doublons.

    - recherches complètes : envoyées telles quelles, tag = la recherche
    - tags : "<contrat> <tag>" ; un nom de catégorie "DevOps / Cloud" donne "<contrat> DevOps" et "<contrat> Cloud",
      tag = le nom du tag
    - compétences : "<contrat> <compétence>", tag = la compétence
    """
    categories = categories or {}
    contract = (contract or "").strip()
    out, seen = [], set()

    def add(query, tag):
        query = " ".join(query.split())
        if query and query.lower() not in seen:
            seen.add(query.lower())
            out.append((query, tag))

    for s in filter(str.strip, searches):
        add(s, s.strip())
    for t in filter(str.strip, tags):
        parts = [p.strip() for p in t.split("/")] if t in categories else [t]
        for part in filter(None, parts):
            add(f"{contract} {part}", t.strip())
    for s in filter(str.strip, skills):
        add(f"{contract} {s}", s.strip())
    return out
