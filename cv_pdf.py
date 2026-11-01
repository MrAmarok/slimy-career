"""Génère le PDF d'un CV, pensé pour être bien lu par les logiciels RH (ATS) :
- une seule colonne, ordre de lecture = ordre visuel, aucun tableau ni zone de texte flottante ;
- vrai texte sélectionnable en police standard (Helvetica), aucune info importante dans une image ;
- titres de sections classiques (« Expérience professionnelle », « Formation », « Compétences »…) ;
- dates au format MM/AAAA, liens écrits en clair, métadonnées PDF (titre, auteur, mots-clés).
"""
import base64
import re
import unicodedata
from io import BytesIO
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase.pdfmetrics import stringWidth
from reportlab.platypus import Flowable, HRFlowable, KeepTogether, Paragraph, SimpleDocTemplate, Spacer

SECTIONS = ["experience", "projects", "education", "hard_skills", "soft_skills", "languages", "certifications", "interests"]

# Ordres de sections conseillés (modèles de CV tech les plus repris + conseils de recrutement en France) :
# - étudiant / stage : la formation d'abord (c'est ce que le recruteur regarde en premier pour un stage) ;
# - junior tech : les compétences techniques en haut, pour que les mots-clés sautent aux yeux ;
# - expérimenté : l'expérience d'abord, la formation descend.
ORDER_PRESETS = {
    "etudiant": ["education", "experience", "projects", "hard_skills", "certifications", "languages", "soft_skills", "interests"],
    "tech": ["hard_skills", "experience", "projects", "education", "certifications", "languages", "soft_skills", "interests"],
    "experimente": ["experience", "projects", "hard_skills", "education", "certifications", "languages", "soft_skills", "interests"],
}
STYLES = ("moderne", "tech", "classique")

HEADINGS = {
    "fr": {
        "summary": "Profil", "experience": "Expérience professionnelle", "projects": "Projets", "education": "Formation",
        "hard_skills": "Compétences techniques", "soft_skills": "Compétences comportementales", "languages": "Langues",
        "certifications": "Certifications", "interests": "Centres d'intérêt", "present": "Aujourd'hui",
        "tech": "Technologies", "cv": "CV",
    },
    "en": {
        "summary": "Summary", "experience": "Professional Experience", "projects": "Projects", "education": "Education",
        "hard_skills": "Technical Skills", "soft_skills": "Soft Skills", "languages": "Languages",
        "certifications": "Certifications", "interests": "Interests", "present": "Present",
        "tech": "Technologies", "cv": "Resume",
    },
}

DEFAULT_ACCENT = "#1f4e79"
PHOTO_SIZE = 30 * mm


def empty_cv():
    return {
        "lang": "fr", "style": "moderne", "accent": DEFAULT_ACCENT, "show_photo": True, "photo": None, "section_order": list(ORDER_PRESETS["etudiant"]),
        "info": {"first_name": "", "last_name": "", "title": "", "email": "", "phone": "", "city": "",
                 "linkedin": "", "github": "", "website": "", "availability": "", "extra": "", "summary": ""},
        "experience": [], "projects": [], "education": [], "certifications": [],
        "hard_skills": [], "soft_skills": [], "languages": [], "interests": [],
    }


# ---------- texte ----------

REPLACE = {"→": "->", "←": "<-", "⇒": "=>", "↔": "<->", "≥": ">=", "≤": "<=", "≠": "!=", "✓": "", "✔": "",
           "★": "*", "☆": "*", "▪": "-", "■": "-", "●": "-", "◦": "-", "\u00a0": " ", "\u202f": " "}


def clean(value):
    """Texte encodable dans la police standard (cp1252) : les caractères exotiques sont
    remplacés par leur équivalent simple ou retirés, pour éviter les carrés noirs à l'export."""
    out = []
    for ch in str(value or "").replace("\t", " ").replace("\r", ""):
        if ch in REPLACE:
            out.append(REPLACE[ch])
            continue
        try:
            ch.encode("cp1252")
            out.append(ch)
        except UnicodeEncodeError:
            simple = unicodedata.normalize("NFKD", ch)
            out.append("".join(c for c in simple if c.isascii()))
    return "".join(out).strip()


def markup(value):
    """Échappe le texte pour reportlab et transforme **gras** en gras."""
    return re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", escape(clean(value)))


def fmt_date(value):
    value = clean(value)
    m = re.fullmatch(r"(\d{4})-(\d{2})(?:-\d{2})?", value)
    return f"{m.group(2)}/{m.group(1)}" if m else value


def date_range(item, words):
    start = fmt_date(item.get("start"))
    end = words["present"] if item.get("current") else fmt_date(item.get("end"))
    if start and end:
        return start if start == end else f"{start} – {end}"
    return start or end


def bullet_lines(text):
    lines = []
    for line in clean(text).split("\n"):
        line = re.sub(r"^\s*[-•*–·]\s*", "", line).strip()
        if line:
            lines.append(line)
    return lines


def as_list(value):
    if isinstance(value, list):
        return [clean(v) for v in value if clean(v)]
    return [v.strip() for v in clean(value).split(",") if v.strip()]


# ---------- mise en page ----------

class LeftRight(Flowable):
    """Une ligne « texte à gauche ... dates à droite » sans tableau : le texte de gauche est écrit
    avant les dates, donc un logiciel lit « Poste — Entreprise 01/2024 – 06/2024 » dans le bon ordre.
    En style moderne, la date est posée sur une pastille colorée (simple forme dessinée)."""

    PAD = 4

    def __init__(self, left, right, right_style, tag_bg=None):
        super().__init__()
        self.left, self.right, self.rs, self.tag_bg = left, clean(right), right_style, tag_bg

    def wrap(self, avail_w, avail_h):
        self.right_w = stringWidth(self.right, self.rs.fontName, self.rs.fontSize) if self.right else 0
        if self.right and self.tag_bg is not None:
            self.right_w += 2 * self.PAD
        self.width = avail_w
        _, self.height = self.left.wrap(avail_w - self.right_w - (8 if self.right else 0), avail_h)
        return avail_w, self.height

    def draw(self):
        self.left.drawOn(self.canv, 0, 0)
        if self.right:
            c, fs = self.canv, self.rs.fontSize
            base = self.height - self.left.style.fontSize
            c.saveState()
            x = self.width
            if self.tag_bg is not None:
                c.setFillColor(self.tag_bg)
                c.roundRect(self.width - self.right_w, base - 2.6, self.right_w, fs + 4.2, 3, stroke=0, fill=1)
                x -= self.PAD
            c.setFont(self.rs.fontName, fs)
            c.setFillColor(self.rs.textColor)
            c.drawRightString(x, base, self.right)
            c.restoreState()


class SectionHeading(Flowable):
    """Titre de section du style moderne : barre de couleur, texte, puis filet fin jusqu'à la marge."""

    def __init__(self, text, style, line_color):
        super().__init__()
        self.text, self.style, self.line_color = text, style, line_color

    def wrap(self, avail_w, avail_h):
        self.width, self.height = avail_w, self.style.fontSize + 9
        return self.width, self.height

    def getSpaceBefore(self):
        return self.style.spaceBefore

    def draw(self):
        c, st = self.canv, self.style
        base = 6
        c.saveState()
        c.setFillColor(st.textColor)
        c.roundRect(0, base - 1.5, 3.2, st.fontSize + 2, 1.4, stroke=0, fill=1)
        c.setFont(st.fontName, st.fontSize)
        c.drawString(9, base, self.text)
        end = 9 + stringWidth(self.text, st.fontName, st.fontSize) + 8
        c.setStrokeColor(self.line_color)
        c.setLineWidth(0.6)
        c.line(end, base + st.fontSize * 0.35, self.width, base + st.fontSize * 0.35)
        c.restoreState()


class Bullet(Flowable):
    """Ligne à puce : la puce est un petit disque dessiné, pas un caractère, pour que le texte
    lu par les logiciels RH ne contienne pas de symbole parasite."""

    def __init__(self, para, dot_color=None):
        super().__init__()
        self.para, self.dot_color = para, dot_color

    def wrap(self, avail_w, avail_h):
        self.width, self.height = avail_w, self.para.wrap(avail_w, avail_h)[1]
        return self.width, self.height

    def getSpaceBefore(self):
        return self.para.getSpaceBefore()

    def draw(self):
        st = self.para.style
        self.canv.saveState()
        self.canv.setFillColor(self.dot_color or st.textColor)
        self.canv.circle(st.bulletIndent + 1.6, self.height - st.fontSize * 0.68, 1.35 if self.dot_color else 1.25, stroke=0, fill=1)
        self.canv.restoreState()
        self.para.drawOn(self.canv, 0, 0)


def bullet(text, style):
    return Bullet(Paragraph(text, style), getattr(style, "dot_color", None))


def mix(a, b, t):
    """Couleur entre a (t=0) et b (t=1)."""
    return colors.Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t)


def is_light(c):
    return 0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue > 0.6


def styles(accent, template="moderne"):
    modern = template == "moderne"
    text, muted = colors.HexColor("#1f2328"), colors.HexColor("#555b63")
    white = colors.white
    band = accent
    if is_light(accent):  # couleur trop claire pour du texte sur fond blanc : version assombrie
        accent = mix(accent, text, 0.6)
    base = dict(fontName="Helvetica", fontSize=9.6, leading=12.6, textColor=text, alignment=TA_LEFT)
    st = {
        "name": ParagraphStyle("name", **{**base, "fontName": "Helvetica-Bold", "fontSize": 21, "leading": 25}),
        "title": ParagraphStyle("title", **{**base, "fontName": "Helvetica-Bold", "fontSize": 12, "leading": 16, "textColor": accent}),
        "contact": ParagraphStyle("contact", **{**base, "fontSize": 9, "leading": 12.5, "textColor": muted}),
        "body": ParagraphStyle("body", **base),
        "heading": ParagraphStyle("heading", **{**base, "fontName": "Helvetica-Bold", "fontSize": 11, "leading": 14,
                                                "textColor": accent, "spaceBefore": 9}),
        "entry": ParagraphStyle("entry", **{**base, "fontSize": 10, "leading": 13}),
        "date": ParagraphStyle("date", **{**base, "fontSize": 9, "textColor": muted}),
        "sub": ParagraphStyle("sub", **{**base, "fontName": "Helvetica-Oblique", "fontSize": 9, "leading": 12, "textColor": muted}),
        "bullet": ParagraphStyle("bullet", **{**base, "leftIndent": 11, "bulletIndent": 2, "spaceBefore": 1.2}),
    }
    st["modern"] = modern
    st["template"] = template
    st["org_color"] = None
    st["org_italic"] = False
    st["skill_bullets"] = True
    st["center_header"] = False
    st["sep"] = "|"
    if template == "tech":
        # inspiré des CV tech « une page » (type Jake's Resume) : police à empattements, en-tête centré,
        # titres en capitales soulignés d'un filet, entreprise en italique, compétences en lignes « Catégorie : … »
        ink = colors.HexColor("#111111")
        serif = dict(fontName="Times-Roman", textColor=ink)
        st["name"] = ParagraphStyle("name", parent=st["name"], fontName="Times-Bold", fontSize=25, leading=29, textColor=ink)
        st["title"] = ParagraphStyle("title", parent=st["title"], fontName="Times-Italic", fontSize=12, leading=15, textColor=accent)
        st["contact"] = ParagraphStyle("contact", parent=st["contact"], **serif, fontSize=10, leading=13)
        st["body"] = ParagraphStyle("body", parent=st["body"], **serif, fontSize=10.5, leading=13)
        st["heading"] = ParagraphStyle("heading", parent=st["heading"], fontName="Times-Roman", fontSize=12.5, leading=15,
                                       textColor=accent, spaceBefore=8)
        st["entry"] = ParagraphStyle("entry", parent=st["entry"], **serif, fontSize=11, leading=13.5)
        st["date"] = ParagraphStyle("date", parent=st["date"], **serif, fontSize=10.5)
        st["sub"] = ParagraphStyle("sub", parent=st["sub"], fontName="Times-Italic", fontSize=10, leading=12.5, textColor=ink)
        st["bullet"] = ParagraphStyle("bullet", parent=st["bullet"], **serif, fontSize=10.5, leading=12.8, leftIndent=14,
                                      bulletIndent=5, spaceBefore=0.6)
        st["bullet"].dot_color = ink
        st["skill"] = ParagraphStyle("skill", parent=st["body"], spaceBefore=1)
        st["org_italic"] = True
        st["skill_bullets"] = False
        st["center_header"] = True
        st["rule"] = ink
    if modern:
        # en-tête sur bandeau de couleur : texte clair (ou foncé si la couleur choisie est claire)
        on_band = text if is_light(band) else white
        st["name"] = ParagraphStyle("name", parent=st["name"], fontSize=24, leading=28, textColor=on_band)
        st["title"] = ParagraphStyle("title", parent=st["title"], textColor=mix(on_band, band, 0.18), spaceBefore=1)
        st["contact"] = ParagraphStyle("contact", parent=st["contact"], textColor=mix(on_band, band, 0.1), leading=13)
        st["sep"] = f'<font color="{mix(on_band, band, 0.5).hexval().replace("0x", "#")}">|</font>'
        st["heading"] = ParagraphStyle("heading", parent=st["heading"], fontSize=11.5, spaceBefore=11)
        dark_accent = mix(accent, text, 0.25)
        st["org_color"] = dark_accent.hexval().replace("0x", "#")
        st["date"] = ParagraphStyle("date", parent=st["date"], fontName="Helvetica-Bold", fontSize=8.3, textColor=dark_accent)
        st["date_bg"] = mix(accent, white, 0.88)
        st["line"] = mix(accent, white, 0.7)
        st["bullet"].dot_color = accent
        st["band"] = band
    return st


def build(cv, meta=None):
    """Retourne (pdf_bytes, nom_de_fichier). Si `meta` est un dict, y met le nombre de pages."""
    cv = {**empty_cv(), **(cv or {})}
    info = {**empty_cv()["info"], **(cv.get("info") or {})}
    words = HEADINGS.get(cv.get("lang"), HEADINGS["fr"])
    try:
        accent = colors.HexColor(cv.get("accent") or DEFAULT_ACCENT)
    except ValueError:
        accent = colors.HexColor(DEFAULT_ACCENT)
    template = cv.get("style") if cv.get("style") in STYLES else "moderne"
    modern = template == "moderne"
    st = styles(accent, template)

    full_name = " ".join(filter(None, [clean(info["first_name"]), clean(info["last_name"])])) or "CV"
    photo = _photo(cv) if cv.get("show_photo") else None
    page_w = A4[0] - 32 * mm
    right_pad = PHOTO_SIZE + 6 * mm if photo else 0
    if st["center_header"] and not photo:
        for k in ("name", "title", "contact"):
            st[k] = ParagraphStyle(k + "c", parent=st[k], alignment=TA_CENTER)

    story = []
    # ---- en-tête : nom, poste visé, coordonnées (dans le corps du document, pas en en-tête de page) ----
    head = [Paragraph(markup(full_name), ParagraphStyle("n2", parent=st["name"], rightIndent=right_pad))]
    if info["title"]:
        head.append(Paragraph(markup(info["title"]), ParagraphStyle("t2", parent=st["title"], rightIndent=right_pad)))
    contacts = [info["email"], info["phone"], info["city"]]
    links = [info["linkedin"], info["github"], info["website"]]
    sep = f" {st['sep']} "
    lines = [sep.join(markup(c) for c in contacts if clean(c)),
             sep.join(_link(l) for l in links if clean(l)),
             sep.join(markup(c) for c in (info["availability"], info["extra"]) if clean(c))]
    if st["center_header"]:  # coordonnées + liens sur une ligne (comme les CV tech), disponibilité en dessous
        lines = [sep.join(l for l in lines[:2] if l), lines[2]]
    cstyle = ParagraphStyle("c2", parent=st["contact"], rightIndent=right_pad)
    head += [Paragraph(l, cstyle) for l in lines if l]
    story += head
    head_h = sum(p.wrap(page_w, 1000)[1] + p.getSpaceBefore() for p in head)
    content_h = max(head_h, PHOTO_SIZE if photo else 0)
    band_pad = 7 * mm
    if content_h > head_h:
        story.append(Spacer(1, content_h - head_h))
    if modern:
        story.append(Spacer(1, band_pad + 1 * mm))   # sortir du bandeau
    elif template == "tech":
        story.append(Spacer(1, 1 * mm))

    if clean(info["summary"]):
        story += _section(words["summary"], [[Paragraph(markup(info["summary"]).replace("\n", "<br/>"), st["body"])]], st)

    order = [s for s in cv.get("section_order") or [] if s in SECTIONS]
    order += [s for s in SECTIONS if s not in order]
    for key in order:
        entries = _entries(key, cv, words, st)
        if entries:
            story += _section(words[key], entries, st)

    buf = BytesIO()
    keywords = [s for g in cv.get("hard_skills") or [] for s in as_list(g.get("items"))]
    doc = SimpleDocTemplate(
        buf, pagesize=A4, leftMargin=16 * mm, rightMargin=16 * mm, topMargin=14 * mm, bottomMargin=14 * mm,
        title=f"{full_name} - {clean(info['title']) or words['cv']}", author=full_name,
        subject=clean(info["title"]), keywords=", ".join(keywords), creator="Candidatures",
    )

    top = 14 * mm

    def first_page(canvas, doc_):
        if modern:  # bandeau de couleur derrière l'en-tête (forme dessinée : invisible pour les ATS)
            canvas.saveState()
            canvas.setFillColor(st["band"])
            canvas.rect(0, A4[1] - top - content_h - band_pad, A4[0], top + content_h + band_pad + 1, stroke=0, fill=1)
            canvas.restoreState()
        if photo:
            y = A4[1] - top - content_h / 2 - PHOTO_SIZE / 2
            _draw_photo(canvas, photo, A4[0] - 16 * mm - PHOTO_SIZE, y, ring=colors.white if modern else None)

    doc.build(story, onFirstPage=first_page)
    if meta is not None:
        meta["pages"] = doc.page
    filename = re.sub(r"[^A-Za-z0-9_-]+", "_", unicodedata.normalize("NFKD", f"CV_{full_name}").encode("ascii", "ignore").decode()).strip("_")
    return buf.getvalue(), f"{filename or 'CV'}.pdf"


def _link(url):
    url = clean(url)
    href = url if re.match(r"^[a-z]+:", url, re.I) else "https://" + url
    # l'adresse est écrite en entier : un logiciel qui ne lit que le texte la récupère quand même
    shown = re.sub(r"^https?://(www\.)?", "", url)
    return f'<a href="{escape(href)}">{escape(shown)}</a>'


def _section(title, entries, st):
    """Titre + trait + entrées ; le titre reste toujours collé à la première entrée."""
    if st["modern"]:
        heading = [SectionHeading(clean(title).upper(), st["heading"], st["line"]), Spacer(1, 3)]
    elif st["template"] == "tech":
        heading = [Paragraph(escape(clean(title).upper()), st["heading"]),
                   HRFlowable(width="100%", thickness=0.6, color=st["rule"], spaceBefore=0, spaceAfter=3)]
    else:
        heading = [Paragraph(escape(title.upper()), st["heading"]),
                   HRFlowable(width="100%", thickness=0.7, color=st["heading"].textColor, spaceBefore=1, spaceAfter=4)]
    return [KeepTogether(heading + entries[0])] + [KeepTogether(e) for e in entries[1:]]


def _entry(st, title, org, dates, sub, bullets):
    left = f"<b>{markup(title)}</b>" if clean(title) else ""
    if clean(org):
        org_txt = f'<font color="{st["org_color"]}">{markup(org)}</font>' if st["org_color"] else markup(org)
        if st["org_italic"]:
            org_txt = f"<i>{org_txt}</i>"
        left += (" — " if left else "") + org_txt
    out = []
    if left or dates:
        out.append(LeftRight(Paragraph(left, st["entry"]), dates, st["date"], st.get("date_bg")))
    if sub:
        out.append(Paragraph(sub, st["sub"]))
    out += [bullet(markup(b), st["bullet"]) for b in bullets]
    if out:
        out.append(Spacer(1, 4 if st["template"] == "tech" else 5))
    return out


def _entries(key, cv, words, st):
    items = cv.get(key) or []
    out = []
    if key == "experience":
        for e in items:
            out.append(_entry(st, e.get("title"), e.get("company"), date_range(e, words),
                              markup(e.get("location")), bullet_lines(e.get("description"))))
    elif key == "projects":
        for p in items:
            sub = " | ".join(filter(None, [
                f"{words['tech']} : {markup(', '.join(as_list(p.get('tech'))))}" if as_list(p.get("tech")) else "",
                _link(p["link"]) if clean(p.get("link")) else "",
            ]))
            out.append(_entry(st, p.get("name"), p.get("context"), date_range(p, words), sub, bullet_lines(p.get("description"))))
    elif key == "education":
        for e in items:
            out.append(_entry(st, e.get("degree"), e.get("school"), date_range(e, words),
                              markup(e.get("location")), bullet_lines(e.get("description"))))
    elif key == "certifications":
        for c in items:
            out.append(_entry(st, c.get("name"), c.get("issuer"), fmt_date(c.get("date")), "", []))
    elif key == "hard_skills":
        rows = []
        for g in items:
            skills = as_list(g.get("items"))
            if skills:
                cat = clean(g.get("category"))
                label = f'<font color="{st["org_color"]}">{escape(cat)}</font>' if st["org_color"] else escape(cat)
                text = (f"<b>{label} :</b> " if cat else "") + markup(", ".join(skills))
                rows.append(bullet(text, st["bullet"]) if st["skill_bullets"] else Paragraph(text, st["skill"]))
        if rows:
            out.append(rows + [Spacer(1, 3)])
    elif key == "languages":
        langs = [f"<b>{markup(l.get('name'))}</b>" + (f" : {markup(l.get('level'))}" if clean(l.get("level")) else "")
                 for l in items if clean(l.get("name"))]
        if langs:
            out.append([Paragraph(f" {st['sep']} ".join(langs), st["body"]), Spacer(1, 3)])
    elif key in ("soft_skills", "interests"):
        values = as_list(items)
        if values:
            out.append([Paragraph(markup(", ".join(values)), st["body"]), Spacer(1, 3)])
    return [e for e in out if e]


# ---------- photo ----------

def _photo(cv):
    data = cv.get("photo") or ""
    m = re.match(r"^data:image/(png|jpe?g|webp);base64,(.+)$", data, re.S)
    if not m:
        return None
    try:
        reader = ImageReader(BytesIO(base64.b64decode(m.group(2))))
        reader.getSize()
        return reader
    except Exception:
        return None


def _draw_photo(canvas, img, x, y, ring=None):
    if ring is not None:
        canvas.saveState()
        canvas.setFillColor(ring)
        canvas.circle(x + PHOTO_SIZE / 2, y + PHOTO_SIZE / 2, PHOTO_SIZE / 2 + 1.8, stroke=0, fill=1)
        canvas.restoreState()
    canvas.saveState()
    path = canvas.beginPath()
    path.circle(x + PHOTO_SIZE / 2, y + PHOTO_SIZE / 2, PHOTO_SIZE / 2)
    canvas.clipPath(path, stroke=0, fill=0)
    iw, ih = img.getSize()
    scale = PHOTO_SIZE / min(iw, ih)  # recadrage centré
    w, h = iw * scale, ih * scale
    canvas.drawImage(img, x - (w - PHOTO_SIZE) / 2, y - (h - PHOTO_SIZE) / 2, w, h)
    canvas.restoreState()


def extract_text(pdf_bytes):
    """Texte tel qu'un logiciel RH le lit dans le PDF (pour vérifier le rendu)."""
    from pypdf import PdfReader
    return "\n\n".join(page.extract_text() or "" for page in PdfReader(BytesIO(pdf_bytes)).pages)
