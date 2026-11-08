"""Correcteur et traduction du texte d'un CV.

- correcteur : LanguageTool (orthographe, grammaire, accords), API publique gratuite et sans clé.
  Pour ne rien envoyer à l'extérieur, lancer un serveur LanguageTool local et mettre son adresse dans
  la variable d'environnement LANGUAGETOOL_URL (ex : http://localhost:8081/v2/check).
- traduction en anglais : DeepL (clé d'API gratuite, `deepl_key` dans config.toml ou variable DEEPL_API_KEY).

Seuls les textes rédigés sont envoyés (titre, profil, puces…) : ni les coordonnées, ni la photo, ni les liens.
"""
import copy
import html
import os
import re
import unicodedata

import requests

import config

LT_URL = os.environ.get("LANGUAGETOOL_URL") or "https://api.languagetool.org/v2/check"
LT_MAX_CHARS = 15000  # l'API publique refuse les textes de plus de 20 000 caractères
# Règles sans intérêt pour un CV : espaces insécables avant « : » (retirées à l'export PDF de toute façon),
# apostrophes typographiques, puces qui commencent par une minuscule.
LT_DISABLED = "FRENCH_WHITESPACE,WHITESPACE_RULE,APOS_TYP,UPPERCASE_SENTENCE_START,COMMA_PARENTHESIS_WHITESPACE"
ENGLISH = ("en-GB", "en-US")
BULLET = re.compile(r"^(\s*(?:[-•–·]|\*(?!\*))\s*)?(.*)$", re.S)


class LangError(Exception):
    """Erreur à montrer telle quelle à l'utilisateur (service injoignable, clé manquante, quota…)."""


def deepl_key():
    return (config.load().get("deepl_key") or os.environ.get("DEEPL_API_KEY") or "").strip()


def _items(cv, key):
    value = cv.get(key)
    return [(i, v) for i, v in enumerate(value)] if isinstance(value, list) else []


def _norm(text):
    return unicodedata.normalize("NFD", str(text or "").lower()).encode("ascii", "ignore").decode().strip()


# ---------- correcteur ----------

def _check_fields(cv):
    """(chemin dans le CV, libellé affiché, texte) de chaque champ rédigé."""
    info = cv.get("info") if isinstance(cv.get("info"), dict) else {}
    for key, label in (("title", "Titre / poste visé"), ("summary", "Profil"),
                       ("availability", "Disponibilité"), ("extra", "Autres infos")):
        yield ["info", key], label, info.get(key)
    for key, name, head, fields in (
        ("experience", "Expérience", "title", (("title", "intitulé"), ("description", "missions"))),
        ("projects", "Projet", "name", (("context", "contexte"), ("description", "description"))),
        ("education", "Formation", "degree", (("degree", "diplôme"), ("description", "détails"))),
    ):
        for i, item in _items(cv, key):
            if not isinstance(item, dict):
                continue
            who = str(item.get(head) or "").strip()
            for field, what in fields:
                yield [key, i, field], f"{name} {i + 1}{' — ' + who if who else ''} : {what}", item.get(field)
    for key, label in (("soft_skills", "Compétences comportementales"), ("interests", "Centres d'intérêt")):
        for i, value in _items(cv, key):
            yield [key, i], label, value


def _known_words(cv):
    """Noms propres et technologies du CV : un mot « inconnu » qui en fait partie n'est pas une faute."""
    info = cv.get("info") if isinstance(cv.get("info"), dict) else {}
    phrases = [info.get(k) for k in ("first_name", "last_name", "city")]
    for key, fields in (("experience", ("company", "location")), ("projects", ("name", "tech")),
                        ("education", ("school", "location")), ("certifications", ("name", "issuer")),
                        ("hard_skills", ("items",))):
        for _, item in _items(cv, key):
            if isinstance(item, dict):
                for field in fields:
                    value = item.get(field)
                    phrases += value if isinstance(value, list) else str(value or "").split(",")
    words = set()
    for phrase in phrases:
        phrase = str(phrase or "").strip().lower()
        if phrase:
            words.add(phrase)
            words.update(w for w in re.split(r"[\s/()]+", phrase) if w)
    return words


def _u16(text):
    """Longueur en unités UTF-16 : c'est ainsi que LanguageTool (et JavaScript) comptent les positions."""
    return len(text.encode("utf-16-le")) // 2


def _u16_slice(text, start, length):
    return text.encode("utf-16-le")[2 * start:2 * (start + length)].decode("utf-16-le", "ignore")


def _printable(text):
    try:
        text.encode("cp1252")
        return True
    except UnicodeEncodeError:
        return False


def language_of(cv):
    if cv.get("lang") != "en":
        return "fr"
    return cv.get("lang_variant") if cv.get("lang_variant") in ENGLISH else "en-US"


def check(cv, ignore=()):
    """Fautes trouvées dans le CV : liste de dicts {path, label, offset, length, bad, message, replacements, kind}.
    `offset` et `length` sont relatifs au champ, en unités UTF-16 (utilisables tels quels en JavaScript)."""
    language = language_of(cv)
    known = _known_words(cv) | {str(w).strip().lower() for w in ignore or ()}
    # une ligne (= une puce) par paragraphe, sinon deux puces qui se suivent sont lues comme une seule phrase
    lines = []  # (chemin, libellé, texte de la ligne, position de la ligne dans son champ)
    for path, label, text in _check_fields(cv):
        base = 0
        for line in (text.replace("\r", "") if isinstance(text, str) else "").split("\n"):
            if line.strip():
                lines.append((path, label, line, base))
            base += _u16(line) + 1
    chunks, size = [[]], 0
    for line in lines:
        if size and size + len(line[2]) > LT_MAX_CHARS:
            chunks.append([])
            size = 0
        chunks[-1].append(line)
        size += len(line[2]) + 2

    issues, seen = [], []
    for chunk in chunks:
        if not chunk:
            continue
        spans, pos = [], 0
        for path, label, text, base in chunk:
            spans.append((pos, _u16(text), path, label, text, base))
            pos += _u16(text) + 2
        params = {"text": "\n\n".join(c[2] for c in chunk), "language": language, "disabledRules": LT_DISABLED}
        if language != "fr":
            params["motherTongue"] = "fr"  # repère aussi les faux amis d'un francophone
        try:
            r = requests.post(LT_URL, data=params, timeout=30)
        except requests.RequestException:
            raise LangError("Correcteur injoignable (LanguageTool) : vérifie ta connexion et réessaie.")
        if r.status_code == 429:
            raise LangError("Trop de vérifications d'affilée : LanguageTool demande d'attendre une minute.")
        if r.status_code != 200:
            raise LangError(f"Le correcteur a répondu une erreur ({r.status_code}) : {r.text[:200]}")
        for m in r.json().get("matches", []):
            span = next((s for s in spans if s[0] <= m["offset"] and m["offset"] + m["length"] <= s[0] + s[1]), None)
            if not span or not m["length"]:
                continue
            start, _, path, label, text, base = span
            bad = _u16_slice(text, m["offset"] - start, m["length"])
            offset, replacements = m["offset"] - start + base, [x.get("value") for x in m.get("replacements", [])[:5]]
            issue_type = (m.get("rule") or {}).get("issueType") or ""
            kind = "orthographe" if issue_type == "misspelling" else "style" if issue_type in ("style", "typographical") else "grammaire"
            if kind == "orthographe" and (bad.strip().lower() in known or not replacements):
                continue  # nom propre ou technologie du CV, ou mot étranger sans proposition
            usable = [v for v in replacements if v is not None and _printable(v)]
            if replacements and not usable:
                continue  # correction purement typographique (« 5ᵉ ») que la police du PDF ne sait pas écrire
            replacements = usable
            if any(p == path and o < offset + m["length"] and offset < o + n for p, o, n in seen):
                continue  # deux règles signalent le même passage : garder la première
            seen.append((path, offset, m["length"]))
            issues.append({
                "path": path, "label": label, "offset": offset, "length": m["length"], "bad": bad, "kind": kind,
                "message": m.get("message") or "", "rule": (m.get("rule") or {}).get("id") or "",
                "replacements": [v for v in replacements if v is not None],
            })
    return issues


# ---------- traduction ----------

# Formules de CV courtes : traduites par une table fixe plutôt que par la machine (« Courant » ne doit
# jamais devenir « Current »).
LANG_NAMES = {
    "francais": "French", "anglais": "English", "espagnol": "Spanish", "allemand": "German", "italien": "Italian",
    "portugais": "Portuguese", "arabe": "Arabic", "chinois": "Chinese", "mandarin": "Mandarin", "japonais": "Japanese",
    "coreen": "Korean", "russe": "Russian", "neerlandais": "Dutch", "polonais": "Polish", "turc": "Turkish",
    "basque": "Basque", "catalan": "Catalan", "occitan": "Occitan", "breton": "Breton", "corse": "Corsican",
}
LANG_LEVELS = {
    "langue maternelle": "Native", "natif": "Native", "native": "Native", "bilingue": "Bilingual", "courant": "Fluent",
    "professionnel": "Professional working proficiency", "avance": "Advanced", "intermediaire": "Intermediate",
    "notions": "Basic", "debutant": "Beginner", "scolaire": "Basic",
}
SKILL_CATEGORIES = {
    "langages": "Languages", "frameworks": "Frameworks", "bases de donnees": "Databases", "devops / cloud": "DevOps / Cloud",
    "systemes / reseaux": "Systems / Networking", "cybersecurite": "Security", "outils": "Developer Tools",
    "methodes": "Methodologies", "web": "Web",
}
CONTEXTS = {
    "title": "Intitulés de postes et de diplômes sur le CV d'un candidat.",
    "prose": "Texte du CV d'un candidat : profil et puces décrivant ses expériences professionnelles, projets et formations.",
    "list": "Listes courtes sur un CV : compétences comportementales, centres d'intérêt, catégories de compétences.",
    "language": "Langues parlées et niveau de langue, sur un CV.",
}


def _fixed(table, text):
    """Traduction par table, en gardant une précision entre parenthèses : « Courant (C1) » -> « Fluent (C1) »."""
    m = re.fullmatch(r"(.*?)(\s*\(.*\))?", str(text or "").strip(), re.S)
    found = table.get(_norm(m.group(1)))
    return found + (m.group(2) or "") if found else None


def _to_deepl(text):
    return re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", html.escape(text, quote=False))


def _from_deepl(text):
    return html.unescape(re.sub(r"</?b>", "**", text))


def _deepl(texts, target, context, key):
    url = "https://api-free.deepl.com/v2/translate" if key.endswith(":fx") else "https://api.deepl.com/v2/translate"
    out = []
    for i in range(0, len(texts), 50):
        body = {"text": [_to_deepl(t) for t in texts[i:i + 50]], "source_lang": "FR", "target_lang": target.upper(),
                "context": context, "tag_handling": "xml", "split_sentences": "nonewlines", "preserve_formatting": True,
                "model_type": "prefer_quality_optimized"}
        try:
            r = requests.post(url, json=body, headers={"Authorization": f"DeepL-Auth-Key {key}"}, timeout=45)
        except requests.RequestException:
            raise LangError("DeepL injoignable : vérifie ta connexion et réessaie.")
        if r.status_code in (401, 403):
            raise LangError("Clé DeepL refusée : vérifie qu'elle est copiée en entier (les clés gratuites finissent par « :fx »).")
        if r.status_code == 456:
            raise LangError("Quota DeepL du mois épuisé (500 000 caractères sur l'offre gratuite).")
        if r.status_code == 429:
            raise LangError("DeepL est surchargé : réessaie dans quelques secondes.")
        if r.status_code != 200:
            raise LangError(f"DeepL a répondu une erreur ({r.status_code}) : {r.text[:200]}")
        got = [_from_deepl(t["text"]) for t in r.json().get("translations", [])]
        if len(got) != len(body["text"]):
            raise LangError("Réponse incomplète de DeepL : rien n'a été modifié, réessaie.")
        out += got
    return out


def translate(cv, variant="en-GB", key=None):
    """Copie du CV traduite du français vers l'anglais. Retourne (cv_traduit, nombre_de_caractères_envoyés).
    Ne sont pas traduits : noms, coordonnées, liens, entreprises, écoles, lieux, noms de projets, technologies."""
    key = key or deepl_key()
    if not key:
        raise LangError("Clé d'API DeepL manquante.")
    variant = variant if variant in ENGLISH else "en-GB"
    out = copy.deepcopy(cv)
    groups = {name: [] for name in CONTEXTS}  # nom -> [(texte, fonction qui range la traduction)]
    finish = []

    def add(group, holder, key_, table=None):
        text = holder[key_] if isinstance(holder, list) or key_ in holder else None
        if not isinstance(text, str) or not text.strip():
            return
        fixed = _fixed(table, text) if table else None
        if fixed:
            holder[key_] = fixed
        else:
            groups[group].append((text.strip(), lambda t: holder.__setitem__(key_, t)))

    def add_lines(holder, key_):
        """Texte à puces : chaque ligne est traduite à part, sa puce est conservée."""
        text = holder.get(key_)
        if not isinstance(text, str) or not text.strip():
            return
        lines = text.replace("\r", "").split("\n")
        for i, line in enumerate(lines):
            prefix, body = BULLET.match(line).groups()
            if body.strip():
                groups["prose"].append((body.strip(), lambda t, i=i, p=prefix or "": lines.__setitem__(i, p + t)))
        finish.append(lambda: holder.__setitem__(key_, "\n".join(lines)))

    info = out.get("info") if isinstance(out.get("info"), dict) else {}
    add("title", info, "title")
    add_lines(info, "summary")
    add("prose", info, "availability")
    add("prose", info, "extra")
    for key_, title_field, extra in (("experience", "title", None), ("projects", None, "context"), ("education", "degree", None)):
        for _, item in _items(out, key_):
            if isinstance(item, dict):
                if title_field:
                    add("title", item, title_field)
                if extra:
                    add("list", item, extra)
                add_lines(item, "description")
    for _, group in _items(out, "hard_skills"):
        if isinstance(group, dict):
            add("list", group, "category", SKILL_CATEGORIES)
    for key_ in ("soft_skills", "interests"):
        values = out.get(key_)
        if isinstance(values, list):
            for i in range(len(values)):
                add("list", values, i)
    for _, lang in _items(out, "languages"):
        if isinstance(lang, dict):
            add("language", lang, "name", LANG_NAMES)
            add("language", lang, "level", LANG_LEVELS)

    chars = 0
    for name, entries in groups.items():
        if entries:
            texts = [t for t, _ in entries]
            chars += sum(len(t) for t in texts)
            for (_, store), translated in zip(entries, _deepl(texts, variant, CONTEXTS[name], key)):
                store(translated)
    for done in finish:
        done()
    out["lang"], out["lang_variant"] = "en", variant
    return out, chars
