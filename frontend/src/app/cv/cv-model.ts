/** Modèle du CV, constantes de l'éditeur et fonctions pures (comparaison avec une offre, contrôles ATS). */

export type SectionKey =
  | 'experience' | 'projects' | 'education' | 'hard_skills'
  | 'soft_skills' | 'languages' | 'certifications' | 'interests';

/** Sections dont les éléments sont des fiches dépliables décrites par un schéma. */
export type ListKey = 'experience' | 'projects' | 'education' | 'certifications';

export type Entry = Record<string, any>;
export interface SkillGroup { category: string; items: string[]; }
export interface Language { name: string; level: string; }

export interface CvData {
  lang?: string;
  lang_variant?: string;
  style?: string;
  accent?: string;
  max_pages?: number;
  show_photo?: boolean;
  photo?: string | null;
  section_order: SectionKey[];
  info: Record<string, string>;
  experience: Entry[];
  projects: Entry[];
  education: Entry[];
  certifications: Entry[];
  hard_skills: SkillGroup[];
  soft_skills: string[];
  languages: Language[];
  interests: string[];
}

export interface CvDoc { id: number; name: string; job_id: number | null; data: CvData; }
export interface CvSummary { id: number; name: string; job_id: number | null; updated_at?: string; }
export interface Job { id: number; title: string; company?: string; link?: string; status?: string; skills?: string[]; }

/** Faute signalée par le correcteur (`path` = chemin du champ dans les données du CV). */
export interface Issue {
  path: (string | number)[];
  label: string;
  offset: number;
  length: number;
  bad: string;
  message: string;
  replacements: string[];
  kind: string;
}

export interface Field {
  key: string;
  label: string;
  type?: 'month' | 'check' | 'textarea' | 'chips' | 'email';
  ph?: string;
  full?: boolean;
  rows?: number;
}

export interface Schema { add: string; title: (e: Entry) => [string, string]; fields: Field[]; }

export const SECTION_LABELS: Record<SectionKey, string> = {
  experience: 'Expérience professionnelle', projects: 'Projets', education: 'Formation',
  hard_skills: 'Compétences techniques (hard skills)', soft_skills: 'Compétences comportementales (soft skills)',
  languages: 'Langues', certifications: 'Certifications', interests: "Centres d'intérêt",
};
export const SECTION_KEYS = Object.keys(SECTION_LABELS) as SectionKey[];
export const LIST_KEYS: ListKey[] = ['experience', 'projects', 'education', 'certifications'];

/* ordres de sections tirés des modèles de CV informatiques les plus repris */
export const ORDER_PRESETS: { label: string; order: SectionKey[]; why: string }[] = [
  { label: 'Étudiant / stage', order: ['education', 'experience', 'projects', 'hard_skills', 'certifications', 'languages', 'soft_skills', 'interests'],
    why: "Formation d'abord : pour un stage ou une alternance, c'est ce que le recruteur regarde en premier. Puis expériences, projets, compétences." },
  { label: 'Junior tech', order: ['hard_skills', 'experience', 'projects', 'education', 'certifications', 'languages', 'soft_skills', 'interests'],
    why: "Compétences techniques en haut : les mots-clés (langages, outils) sautent aux yeux du recruteur et de l'ATS dès le premier coup d'œil." },
  { label: 'Expérimenté', order: ['experience', 'projects', 'hard_skills', 'education', 'certifications', 'languages', 'soft_skills', 'interests'],
    why: "Expérience d'abord : quand tu as déjà travaillé dans le métier, c'est ton meilleur argument ; la formation descend." },
];

/* champs des entrées de liste */
const DATES: Field[] = [
  { key: 'start', label: 'Début', type: 'month' },
  { key: 'end', label: 'Fin', type: 'month' },
  { key: 'current', label: 'En cours', type: 'check' },
];

export const SCHEMAS: Record<ListKey, Schema> = {
  experience: {
    add: '+ Ajouter une expérience', title: (e) => [e['title'], e['company']],
    fields: [
      { key: 'title', label: 'Intitulé du poste', ph: 'Développeur full stack (alternance)' },
      { key: 'company', label: 'Entreprise', ph: "Nom de l'entreprise" },
      { key: 'location', label: 'Lieu', ph: 'Bordeaux' }, ...DATES,
      { key: 'description', label: 'Missions et résultats (une ligne = une puce, **gras** possible)', type: 'textarea', full: true,
        ph: "Mise en place d'une CI/CD GitLab pour 12 microservices\nRéduction du temps de déploiement de 40 %" },
    ],
  },
  projects: {
    add: '+ Ajouter un projet', title: (p) => [p['name'], p['context']],
    fields: [
      { key: 'name', label: 'Nom du projet', ph: 'Homelab Kubernetes' },
      { key: 'context', label: 'Contexte', ph: "Projet personnel, projet d'école…" },
      { key: 'tech', label: 'Technologies', type: 'chips', full: true, ph: 'Docker, Terraform…' },
      { key: 'link', label: 'Lien', ph: 'github.com/moi/projet', full: true }, ...DATES,
      { key: 'description', label: 'Description (une ligne = une puce)', type: 'textarea', full: true },
    ],
  },
  education: {
    add: '+ Ajouter une formation', title: (e) => [e['degree'], e['school']],
    fields: [
      { key: 'degree', label: 'Diplôme', ph: 'Master Informatique, BUT…' },
      { key: 'school', label: 'Établissement', ph: 'Université de Bordeaux' },
      { key: 'location', label: 'Lieu', ph: 'Bordeaux' }, ...DATES,
      { key: 'description', label: 'Détails (spécialité, mention, cours clés — une ligne = une puce)', type: 'textarea', full: true },
    ],
  },
  certifications: {
    add: '+ Ajouter une certification', title: (c) => [c['name'], c['issuer']],
    fields: [
      { key: 'name', label: 'Certification', ph: 'AWS Cloud Practitioner' },
      { key: 'issuer', label: 'Organisme', ph: 'Amazon Web Services' },
      { key: 'date', label: "Date d'obtention", type: 'month' },
    ],
  },
};

export const INFO_FIELDS: Field[] = [
  { key: 'first_name', label: 'Prénom' }, { key: 'last_name', label: 'Nom' },
  { key: 'title', label: 'Titre / poste visé', ph: 'Stage DevOps — 6 mois à partir de mars 2027', full: true },
  { key: 'email', label: 'E-mail', type: 'email' }, { key: 'phone', label: 'Téléphone', ph: '06 12 34 56 78' },
  { key: 'city', label: 'Ville', ph: 'Bordeaux (mobile en France)' }, { key: 'linkedin', label: 'LinkedIn', ph: 'linkedin.com/in/…' },
  { key: 'github', label: 'GitHub / GitLab', ph: 'github.com/…' }, { key: 'website', label: 'Site / portfolio' },
  { key: 'availability', label: 'Disponibilité', ph: 'Disponible dès mars 2027' }, { key: 'extra', label: 'Autres infos', ph: 'Permis B, véhiculé' },
  { key: 'summary', label: "Profil / accroche (3-4 lignes, avec les mots-clés de l'offre)", type: 'textarea', full: true, rows: 4 },
];

// catégories qu'on retrouve dans la plupart des CV informatiques (ordre habituel)
export const SKILL_CATEGORIES = {
  fr: ['Langages', 'Frameworks', 'Bases de données', 'DevOps / Cloud', 'Systèmes / Réseaux', 'Cybersécurité', 'Outils', 'Méthodes'],
  en: ['Languages', 'Frameworks', 'Databases', 'DevOps / Cloud', 'Systems / Networking', 'Security', 'Developer Tools', 'Methodologies'],
};

export const SWATCHES = ['#1f4e79', '#0f766e', '#334155', '#7c2d12', '#6d28d9', '#9f1239'];

/** Préférences locales (mêmes clés que l'ancienne page) ; silencieux si le stockage est indisponible. */
export const prefs = {
  get(key: string): string | null {
    try { return localStorage.getItem(key); } catch { return null; }
  },
  set(key: string, value: string) {
    try { localStorage.setItem(key, value); } catch { /* stockage indisponible */ }
  },
};

const toList = (v: unknown): string[] =>
  Array.isArray(v) ? v : String(v || '').split(',').map((x) => x.trim()).filter(Boolean);

/** Complète un CV reçu du serveur pour que l'éditeur puisse compter sur toutes les listes. */
export function normalizeCv(d: CvData) {
  d.info ||= {};
  d.section_order = [...new Set([...(d.section_order || []), ...SECTION_KEYS])].filter((k) => SECTION_LABELS[k]);
  for (const k of SECTION_KEYS) if (!Array.isArray(d[k])) (d as any)[k] = [];
  d.hard_skills.forEach((g) => (g.items = toList(g.items)));
  d.projects.forEach((p) => (p['tech'] = toList(p['tech'])));
}

/* ---------- comparaison avec une offre ---------- */
export const norm = (s: unknown) => String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

export function cvText(data: CvData): string {
  const { photo, accent, section_order, max_pages, lang_variant, ...rest } = data;
  return norm(JSON.stringify(rest).replace(/\\n/g, ' '));
}

export function hasSkill(text: string, skill: string): boolean {
  const s = norm(skill).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[^a-z0-9+#])${s}($|[^a-z0-9+#])`).test(text);
}

export const jobLabel = (j: Job) => `${j.title} — ${j.company || '?'} (#${j.id})`;

/* ---------- contrôles « lecture ATS » ---------- */
export interface AtsCheck { cls: 'ok' | 'warn' | 'info'; msg: string; }

export function atsChecks(data: CvData, text: string, job: Job | null): AtsCheck[] {
  const i = data.info, out: AtsCheck[] = [];
  const add = (ok: unknown, msg: string) => out.push({ cls: ok ? 'ok' : 'warn', msg: (ok ? '✓ ' : '! ') + msg });
  add(i['first_name'] && i['last_name'], 'Nom et prénom en haut du CV');
  add(/\S+@\S+\.\S+/.test(i['email'] || ''), 'Adresse e-mail valide');
  add(/\d{6,}/.test((i['phone'] || '').replace(/\D/g, '')), 'Numéro de téléphone');
  add(i['title'], "Titre du poste visé sous le nom (les recruteurs et les ATS s'en servent pour classer)");
  add((i['summary'] || '').length > 60, 'Profil / accroche rédigé');
  const exp = data.experience;
  add(exp.length || data.projects.length, 'Au moins une expérience ou un projet');
  add(exp.every((e) => e['start'] && (e['end'] || e['current'])), 'Toutes les expériences ont des dates');
  add([...exp, ...data.projects].every((e) => (e['description'] || '').trim()), 'Chaque expérience / projet décrit tes missions');
  add(data.hard_skills.some((g) => g.items?.length), 'Compétences techniques renseignées');
  add(data.languages.length, 'Langues indiquées');
  const missingText = [i['email'], i['phone'], i['last_name']].filter((v) => v && !norm(text).includes(norm(v).trim()));
  add(!missingText.length, missingText.length ? `Introuvable dans le texte extrait : ${missingText.join(', ')}` : 'Coordonnées bien lisibles dans le texte extrait');
  if (job) {
    const full = cvText(data);
    const miss = (job.skills || []).filter((s) => !hasSkill(full, s));
    add(!miss.length, miss.length ? `Mots-clés de l'offre absents : ${miss.join(', ')}` : "Tous les mots-clés de l'offre sont présents");
  }
  out.push({ cls: 'info', msg: 'Format : PDF texte sur une colonne, police standard, sans tableau ni zone de texte, titres de sections classiques.' });
  return out;
}
