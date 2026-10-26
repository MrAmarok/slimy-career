"""Catégories automatiques (Cybersécurité, DevOps…) à partir de mots-clés configurables.

Une offre entre dans une catégorie si son titre contient un des mots-clés,
ou si sa description en contient au moins DESC_MIN_HITS différents."""
import skills

DESC_MIN_HITS = 3


def compile_categories(categories):
    return {name: skills.build_patterns(kws) for name, kws in categories.items()}


def classify(title, description, compiled):
    out = []
    for name, patterns in compiled.items():
        if skills.extract(title, patterns) or len(skills.extract(description, patterns)) >= DESC_MIN_HITS:
            out.append(name)
    return out
