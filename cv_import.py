"""Import d'un CV existant (PDF texte) : extrait le texte et le range dans les sections de l'éditeur.

C'est une lecture par heuristiques (titres de sections connus, e-mail / téléphone / liens repérés,
lignes de dates = début d'une expérience) : le résultat est un brouillon à relire, pas une copie parfaite.
Un PDF scanné (image sans texte) ne peut pas être lu.
"""
import base64
import re
import unicodedata
from io import BytesIO

import cv_pdf

# ---------- titres de sections reconnus (comparés sans accents ni majuscules) ----------
HEADINGS = {
    "summary": ["profil", "profile", "resume", "summary", "a propos", "a propos de moi", "about", "about me", "objectif",
                "objective", "accroche", "presentation", "profil professionnel", "professional summary"],
    "experience": ["experience", "experiences", "experience professionnelle", "experiences professionnelles",
                   "parcours professionnel", "work experience", "professional experience", "employment", "employment history",
                   "stages", "stages et experiences", "experiences et stages", "alternance", "work history", "emplois"],
    "projects": ["projets", "projects", "projets personnels", "projets academiques", "personal projects", "realisations",
                 "projets realises", "side projects", "portfolio"],
    "education": ["formation", "formations", "education", "etudes", "diplomes", "diplomes et formations", "cursus",
                  "parcours academique", "parcours scolaire", "academic background", "formation academique", "scolarite"],
    "hard_skills": ["competences", "competences techniques", "skills", "technical skills", "hard skills", "outils",
                    "technologies", "informatique", "competences informatiques", "stack", "stack technique",
                    "outils et technologies", "connaissances", "connaissances techniques", "savoir-faire", "expertise"],
    "soft_skills": ["soft skills", "savoir-etre", "savoir etre", "qualites", "competences comportementales", "atouts",
                    "qualites personnelles", "personal skills", "competences personnelles", "aptitudes"],
    "languages": ["langues", "languages", "langues etrangeres", "language skills", "langues parlees"],
    "certifications": ["certifications", "certification", "certificats", "certificates", "licences et certifications",
                       "licenses & certifications", "licenses and certifications"],
    "interests": ["centres d'interet", "centres d interet", "centre d'interet", "loisirs", "interets", "hobbies",
                  "interests", "passions", "activites", "activites extra-professionnelles", "vie associative"],
}
HEADING_INDEX = {h: key for key, names in HEADINGS.items() for h in names}

MONTHS = {
    "jan": 1, "janv": 1, "janvier": 1, "january": 1, "feb": 2, "fev": 2, "fevr": 2, "fevrier": 2, "february": 2,
    "mar": 3, "mars": 3, "march": 3, "apr": 4, "avr": 4, "avril": 4, "april": 4, "mai": 5, "may": 5,
    "jun": 6, "juin": 6, "june": 6, "jul": 7, "juil": 7, "juillet": 7, "july": 7, "aou": 8, "aout": 8, "aug": 8,
    "august": 8, "sep": 9, "sept": 9, "septembre": 9, "september": 9, "oct": 10, "octobre": 10, "october": 10,
    "nov": 11, "novembre": 11, "november": 11, "dec": 12, "decembre": 12, "december": 12,
}
PRESENT = r"(?:aujourd'hui|aujourd’hui|present|présent|actuel(?:lement)?|en cours|now|current|ce jour|maintenant)"
MONTH_RE = r"(?:%s)\.?" % "|".join(sorted({re.escape(m) for m in MONTHS} | {"févr", "fév", "déc", "décembre", "août", "février"}, key=len, reverse=True))
DATE = rf"(?:{MONTH_RE}\s+\d{{4}}|\d{{1,2}}[/.-]\d{{4}}|\d{{4}}[/.-]\d{{1,2}}|\d{{4}})"
RANGE_RE = re.compile(rf"(?P<start>{DATE})\s*(?:[-–—]|à|a|to|au|>|->)\s*(?P<end>{DATE}|{PRESENT})|(?:depuis|since)\s+(?P<since>{DATE})|(?P<single>{DATE})", re.I)

EMAIL_RE = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
PHONE_RE = re.compile(r"(?:\+\d{1,3}[\s.]?)?\(?\d{1,4}\)?(?:[\s.-]?\d{2,4}){3,5}")
URL_RE = re.compile(r"(?:https?://)?(?:www\.)?[\w-]+(?:\.[\w-]+)+(?:/[^\s|,]*)?", re.I)
BULLET_RE = re.compile(r"^\s*[•●▪■◦○►▸‣⁃\-–*·\x7f]+\s*")
SEP_RE = re.compile(r"\s+[—–|@·]\s+|\s+-\s+|\s+chez\s+|\s+at\s+")


def norm(s):
    s = unicodedata.normalize("NFD", s.lower())
    return re.sub(r"\s+", " ", "".join(c for c in s if unicodedata.category(c) != "Mn")).strip()


# ---------- point d'entrée ----------

def parse_pdf(pdf_bytes):
    """Retourne (données du CV, liste d'avertissements)."""
    from pypdf import PdfReader
    reader = PdfReader(BytesIO(pdf_bytes))
    text = "\n".join(page.extract_text() or "" for page in reader.pages)
    cv = parse_text(text)
    warnings = []
    if len(text.strip()) < 40:
        warnings.append("Presque aucun texte trouvé : ce PDF est sans doute une image scannée, il ne peut pas être importé.")
    photo = _photo(reader)
    if photo:
        cv["photo"], cv["show_photo"] = photo, True
    return cv, warnings


def parse_text(text):
    cv = cv_pdf.empty_cv()
    lines = _lines(text)
    # découpe en blocs : en-tête (avant le premier titre connu) puis une liste par section
    blocks, current, order = {"header": []}, "header", []
    for line in lines:
        key = _heading(line)
        if key:
            current = key
            blocks.setdefault(key, [])
            if key not in order:
                order.append(key)
        else:
            blocks[current].append(line)

    _header(cv, blocks["header"])
    if blocks.get("summary"):
        cv["info"]["summary"] = _join(blocks["summary"])
    cv["experience"] = [_entry(e, "experience") for e in _split_entries(blocks.get("experience", []))]
    cv["projects"] = [_entry(e, "projects") for e in _split_entries(blocks.get("projects", []))]
    cv["education"] = [_entry(e, "education") for e in _split_entries(blocks.get("education", []))]
    cv["certifications"] = _certifications(blocks.get("certifications", []))
    cv["hard_skills"] = _skill_groups(blocks.get("hard_skills", []))
    cv["soft_skills"] = _items(blocks.get("soft_skills", []))
    cv["interests"] = _items(blocks.get("interests", []))
    cv["languages"] = _languages(blocks.get("languages", []))
    # garde l'ordre des sections du CV d'origine
    cv["section_order"] = [k for k in order if k in cv_pdf.SECTIONS] + [k for k in cv_pdf.SECTIONS if k not in order]
    return cv


# ---------- lignes ----------

def _lines(text):
    out = []
    for raw in text.replace("\r", "\n").split("\n"):
        line = re.sub(r"[ \t ]+", " ", raw).strip()
        if not line:
            continue
        bullet = bool(BULLET_RE.match(line)) and not RANGE_RE.match(line)
        line = BULLET_RE.sub("", line).strip() if bullet else line
        if not line:
            continue
        # une phrase coupée en fin de ligne continue en minuscule sur la suivante : on recolle
        if out and not bullet and line[0].islower() and len(out[-1]["text"]) >= 45 \
                and not out[-1]["text"].endswith((".", ":", ";")) and not re.search(r"@|\||https?:|www\.", line) \
                and not _heading(out[-1]["text"]):
            out[-1]["text"] += " " + line
            continue
        out.append({"text": line, "bullet": bullet})
    return [l["text"] if not l["bullet"] else "•" + l["text"] for l in out]


def _heading(line):
    if line.startswith("•") or len(line) > 45:
        return None
    return HEADING_INDEX.get(norm(line).rstrip(" :").strip())


def _strip_bullet(line):
    return line[1:] if line.startswith("•") else line


def _join(lines):
    return " ".join(_strip_bullet(l) for l in lines).strip()


# ---------- en-tête : nom, titre, coordonnées ----------

def _header(cv, lines):
    info = cv["info"]
    rest = []
    for line in lines:
        line = _strip_bullet(line)
        leftovers = []
        for part in re.split(r"\s*[|•·◆♦\x7f\uf0b7]\s*|\s{3,}", line):
            part = part.strip(" ,;")
            if not part:
                continue
            low = norm(part)
            if m := EMAIL_RE.search(part):
                info["email"] = info["email"] or m.group(0)
            elif "linkedin" in low:
                info["linkedin"] = info["linkedin"] or _url(part)
            elif "github" in low or "gitlab" in low:
                info["github"] = info["github"] or _url(part)
            elif PHONE_RE.fullmatch(re.sub(r"^(tel|tél|phone|mobile)\s*:?\s*", "", part, flags=re.I)) and len(re.sub(r"\D", "", part)) >= 9:
                info["phone"] = info["phone"] or re.sub(r"^(tel|tél|phone|mobile)\s*:?\s*", "", part, flags=re.I)
            elif URL_RE.fullmatch(part) and "." in part and " " not in part:
                info["website"] = info["website"] or part
            elif re.search(r"disponib|available|a partir de|à partir de", low):
                info["availability"] = info["availability"] or part
            elif re.search(r"permis|vehicule|driving", low):
                info["extra"] = ", ".join(filter(None, [info["extra"], part]))
            elif re.search(r"\b\d{5}\b", part) or re.search(r"^(adresse|address)\b", low):
                info["city"] = info["city"] or re.sub(r"^(adresse|address)\s*:?\s*", "", part, flags=re.I)
            else:
                leftovers.append(part)
        if leftovers:
            rest.append(" ".join(leftovers))
    # nom = première ligne courte, titre = la suivante, une ville seule ensuite
    for line in rest:
        words = line.split()
        if not info["first_name"] and len(words) <= 5 and not any(c.isdigit() for c in line):
            first, last = _split_name(words)
            info["first_name"], info["last_name"] = first, last
        elif not info["title"] and len(line) <= 120:
            info["title"] = line
        elif not info["city"] and len(words) <= 4 and not any(c.isdigit() for c in line):
            info["city"] = line
        else:
            info["summary"] = " ".join(filter(None, [info["summary"], line]))


def _split_name(words):
    if len(words) == 1:
        return words[0], ""
    # NOM en majuscules = nom de famille, où qu'il soit
    upper = [w for w in words if w.isupper() and len(w) > 1]
    if upper and len(upper) < len(words):
        return " ".join(w for w in words if w not in upper), " ".join(w.title() if w.isupper() else w for w in upper)
    return " ".join(words[:-1]), words[-1]


def _url(part):
    m = URL_RE.search(part.split(":", 1)[-1] if re.match(r"^\s*\w+\s*:", part) and "://" not in part else part)
    return m.group(0) if m else part


# ---------- dates ----------

def _to_month(value):
    v = norm(value).rstrip(".")
    if re.fullmatch(PRESENT, v, re.I) or re.fullmatch(PRESENT, value.strip(), re.I):
        return None
    if m := re.fullmatch(r"(\d{1,2})[/.-](\d{4})", v):
        return f"{m.group(2)}-{int(m.group(1)):02d}"
    if m := re.fullmatch(r"(\d{4})[/.-](\d{1,2})", v):
        return f"{m.group(1)}-{int(m.group(2)):02d}"
    if m := re.fullmatch(r"([a-z]+)\.?\s+(\d{4})", v):
        month = MONTHS.get(m.group(1)) or MONTHS.get(m.group(1)[:4]) or MONTHS.get(m.group(1)[:3])
        return f"{m.group(2)}-{month:02d}" if month else m.group(2)
    return v if re.fullmatch(r"\d{4}", v) else value.strip()


def _find_range(line):
    """(début, fin, en_cours, texte sans la date) si la ligne contient une période, sinon None.
    Une année seule ne compte que si la ligne est courte (sinon « +30 % en 2024 » serait pris pour une date)."""
    for m in RANGE_RE.finditer(line):
        if m.group("single") and (len(line) > 60 or not re.search(r"\d{4}\s*$", line)):
            continue
        rest = (line[:m.start()] + " " + line[m.end():]).strip(" ,;:()|—–-")
        if m.group("single") and rest and len(rest.split()) > 8:
            continue
        if m.group("since"):
            return _to_month(m.group("since")), "", True, rest
        if m.group("single"):
            return _to_month(m.group("single")), "", False, rest
        end = m.group("end")
        current = bool(re.fullmatch(PRESENT, end.strip(), re.I))
        return _to_month(m.group("start")), "" if current else _to_month(end), current, rest
    return None


# ---------- expériences, projets, formations ----------

def _split_entries(lines):
    """Une entrée commence à chaque ligne qui contient une période (ou à la ligne juste avant une date seule)."""
    if not lines:
        return []
    dates = [i for i, l in enumerate(lines) if not l.startswith("\u2022") and _find_range(l) is not None]
    # mise en page « intitulé / organisme / lieu puis date » : plusieurs lignes avant la première date
    dates_last = bool(dates) and dates[0] >= 2 and not _find_range(lines[dates[0]])[3] \
        and not any(l.startswith("\u2022") for l in lines[:dates[0]])
    # mise en page « Organisme  Lieu » puis « Intitulé  Dates » : la ligne d'avant appartient à l'entrée
    org_first = bool(dates) and dates[0] == 1 and bool(_find_range(lines[1])[3]) and not lines[0].startswith("\u2022") \
        and not SEP_RE.search(_find_range(lines[1])[3])
    starts = []
    for n, i in enumerate(dates):
        prev = dates[n - 1] if n else -1
        if org_first and i - 1 > prev and not lines[i - 1].startswith("\u2022"):
            head = i - 1
        elif _find_range(lines[i])[3]:          # la date est sur la ligne de l'intitulé
            head = i
        elif dates_last:                      # tout ce qui suit les puces de l'entrée précédente
            head = 0 if n == 0 else dates[n - 1] + 1
            while head < i and (lines[head].startswith("\u2022") or head == dates[n - 1] + 1 and len(lines[head]) > 60):
                head += 1
            head = min(head, i)
        else:                                 # date seule juste sous l'intitulé
            head = max(i - 1, starts[-1][1] + 1 if starts else 0)
            if lines[head].startswith("\u2022"):
                head = i
        starts.append((head, i))
    if not starts:
        # pas de dates : chaque ligne non-puce suivie de puces est une entrée
        starts = [(i, None) for i, l in enumerate(lines) if not l.startswith("•") and (i == 0 or lines[i - 1].startswith("•"))]
        if not starts:
            starts = [(0, None)]
    entries = []
    for n, (head, date_i) in enumerate(starts):
        end = starts[n + 1][0] if n + 1 < len(starts) else len(lines)
        start = 0 if n == 0 else head
        entries.append({"lines": lines[start:end], "head": head - start, "date": None if date_i is None else date_i - start})
    return entries


def _entry(e, kind):
    lines = e["lines"]
    item = {}
    head_parts, desc = [], []
    date_rest, pre = "", []
    for i, line in enumerate(lines):
        plain = _strip_bullet(line)
        if i == e["date"]:
            start, end, current, rest = _find_range(plain)
            item.update(start=start or "", end=end or "", current=current)
            date_rest = rest
            if rest:
                head_parts.append(rest)
        elif e["date"] is not None and i < e["date"] and i >= e["head"] and not line.startswith("\u2022"):
            pre.append(plain)
            head_parts.append(plain)
        elif e["date"] is not None and i == e["date"] + 1 and date_rest and not pre and kind != "projects" \
                and not line.startswith("\u2022") and not SEP_RE.search(date_rest) and len(plain.split()) <= 8 \
                and not plain.endswith("."):
            # « Intitulé  Dates » puis « Organisme  Lieu » sur la ligne suivante
            org, loc = _org_location(plain)
            item["_org"] = org
            if loc:
                item["location"] = loc
        elif i < e["head"] or (i == e["head"] and not line.startswith("•")) \
                or (e["date"] is not None and e["head"] < i < e["date"] and not line.startswith("•")):
            head_parts.append(plain)
        elif not line.startswith("•") and not desc and len(plain.split()) <= 4 and not plain.endswith(".") \
                and kind in ("experience", "education") and not item.get("location") and i <= (e["date"] or 0) + 2:
            item["location"] = plain   # ligne courte juste sous l'intitulé : le lieu
        else:
            desc.append(plain)

    title, org = _split_title(" ".join(head_parts))
    if item.get("_org"):
        title, org = date_rest, item["_org"]
    elif len(pre) == 1 and date_rest and not SEP_RE.search(date_rest) and not SEP_RE.search(pre[0]):
        # « Organisme  Lieu » puis « Intitulé  Dates »
        org, loc = _org_location(pre[0])
        title = date_rest
        if loc and kind != "projects":
            item["location"] = item.get("location") or loc
    elif len(head_parts) >= 2 and not org:   # intitulé et organisme sur deux lignes séparées
        title, org = head_parts[0], head_parts[1]
        extra = " ".join(head_parts[2:])
        if extra and len(extra.split()) <= 4 and kind != "projects":
            item["location"] = item.get("location") or extra
        elif extra:
            desc.insert(0, extra)
    if kind == "projects":
        tech_lines = [d for d in desc if re.match(r"^(technologies|techno|stack|outils|tech)\s*:", d, re.I)]
        tech = []
        for d in tech_lines:
            body = d.split(":", 1)[1]
            for piece in re.split(r"\s*\|\s*", body):
                if URL_RE.fullmatch(piece.strip()) and "." in piece:
                    item["link"] = piece.strip()
                else:
                    tech += [t.strip() for t in re.split(r"[,;/]", piece) if t.strip()]
        desc = [d for d in desc if d not in tech_lines]
        for d in list(desc):
            if (m := URL_RE.fullmatch(d.strip())) and "." in d and not item.get("link"):
                item["link"] = m.group(0)
                desc.remove(d)
        if not tech and org and len([t for t in re.split(r"\s*,\s*", org) if t]) >= 2:
            tech, org = [t.strip() for t in org.split(",") if t.strip()], ""   # « Nom | Python, Flask, … »
        item.update(name=title, context=org, tech=tech)
    elif kind == "education":
        item.update(degree=title, school=org)
    else:
        item.update(title=title, company=org)
    item.pop("_org", None)
    item["description"] = "\n".join(desc)
    return item


ORG_WORDS = re.compile(r"\b(university|universite|université|college|collège|school|ecole|école|institute|institut|iut|lycee|lycée|"
                       r"academy|academie|académie|inc\.?|ltd|llc|sas|sarl|sa|gmbh|group|groupe|labs?|bank|banque|corp\.?|"
                       r"company|technologies|solutions|consulting|agency|agence)$", re.I)


def _org_location(text):
    """« Texas A&M University College Station, TX » -> (organisme, lieu) ; « ACME - Toulouse » aussi."""
    text = text.strip()
    if m := re.match(r"^(.+?)\s+(?:[—–|-]|·)\s+(.+)$", text):
        return m.group(1).strip(), m.group(2).strip()
    # « Organisme Ville, XX » : la ville fait 1 ou 2 mots ; on garde la découpe dont l'organisme
    # se termine par un mot typique (University, College, École, SAS…)
    tail = re.match(r"^(.+?),\s*([A-ZÀ-Ý][\w.]+)$", text)
    if tail:
        words, region = tail.group(1).split(), tail.group(2)
        options = [(" ".join(words[:-n]), " ".join(words[-n:])) for n in (2, 1) if len(words) > n]
        options = [(o, c) for o, c in options if all(w[:1].isupper() for w in c.split())]
        for org, city in options:
            if ORG_WORDS.search(org):
                return org, f"{city}, {region}"
        if options:
            org, city = options[-1]
            return org, f"{city}, {region}"
    return text, ""


def _split_title(text):
    parts = [p.strip(" ,") for p in SEP_RE.split(text, maxsplit=1) if p.strip(" ,")]
    if len(parts) == 1 and ", " in text:
        parts = [p.strip() for p in text.split(", ", 1)]
    return (parts + ["", ""])[0], (parts + ["", ""])[1]


def _certifications(lines):
    out = []
    for line in lines:
        plain = _strip_bullet(line)
        r = _find_range(plain)
        if r and not r[3] and out:          # date seule sur sa ligne : appartient à la certif précédente
            out[-1]["date"] = r[0]
            continue
        name, issuer = _split_title(r[3] if r else plain)
        out.append({"name": name, "issuer": issuer, "date": r[0] if r else ""})
    return out


# ---------- listes ----------

def _items(lines):
    out = []
    for line in lines:
        for v in re.split(r"\s*[,;|•·\x7f]\s*|\s{3,}", _strip_bullet(line)):
            v = v.strip(" .")
            if v and v.lower() not in {x.lower() for x in out}:
                out.append(v)
    return out


def _skill_groups(lines):
    groups, loose = [], []
    for line in lines:
        plain = _strip_bullet(line)
        m = re.match(r"^([^:]{2,40})\s*:\s*(.+)$", plain)
        if m:
            groups.append({"category": m.group(1).strip(), "items": _items([m.group(2)])})
        elif groups and plain[:1].islower():
            groups[-1]["items"] += _items([plain])
        else:
            loose += _items([plain])
    if loose:
        groups.append({"category": "", "items": loose})
    return groups


def _languages(lines):
    out = []
    for line in lines:
        # « Français : natif Anglais : B2 » : coupe aussi devant chaque nom de langue suivi de « : » ou « ( »
        for part in re.split(r"\s*[|•·;\x7f]\s*|\s{3,}|\s+(?=[A-ZÀ-Ý][a-zà-ÿ]{2,}\s*[:(])", _strip_bullet(line)):
            part = part.strip(" ,.")
            if not part:
                continue
            m = (re.match(r"^([^:]+?)\s*:\s*(.+)$", part) or re.match(r"^([^(]+?)\s*\((.+)\)$", part)
                 or re.match(r"^(.+?)\s+[-–—]\s+(.+)$", part))
            if m:
                out.append({"name": m.group(1).strip(), "level": m.group(2).strip()})
            else:
                words = part.split()
                if len(words) > 1 and re.search(r"\b([ABC][12]|natif|native|courant|fluent|bilingue|notions|scolaire|intermediaire|intermédiaire|maternelle)\b", part, re.I):
                    out.append({"name": words[0], "level": " ".join(words[1:])})
                else:
                    out += [{"name": w.strip(), "level": ""} for w in part.split(",") if w.strip()]
    return out


# ---------- photo ----------

def _photo(reader):
    """Plus grande image (à peu près carrée ou portrait) de la première page, recadrée en carré."""
    try:
        from PIL import Image
        best = None
        for img in reader.pages[0].images:
            try:
                im = Image.open(BytesIO(img.data))
                w, h = im.size
            except Exception:
                continue
            if w < 60 or h < 60 or not (0.5 <= w / h <= 1.6):
                continue
            if best is None or w * h > best.size[0] * best.size[1]:
                best = im
        if best is None:
            return None
        im = best.convert("RGBA")
        bg = Image.new("RGB", im.size, (255, 255, 255))
        bg.paste(im, mask=im.getchannel("A"))
        side = min(bg.size)
        left, top = (bg.width - side) // 2, max(0, int((bg.height - side) / 2.6))
        bg = bg.crop((left, top, left + side, top + side)).resize((min(500, side),) * 2)
        buf = BytesIO()
        bg.save(buf, "JPEG", quality=88)
        return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
    except Exception:
        return None
