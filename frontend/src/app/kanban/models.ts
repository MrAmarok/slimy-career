/** Types renvoyés par l'API et petites fonctions partagées par les composants du tableau. */

export interface Status {
  key: string;
  label: string;
}

export interface Job {
  id: number;
  title: string;
  company: string | null;
  location: string | null;
  city: string | null;
  country: string | null;
  link: string;
  source: string | null;
  contract: string | null;
  status: string;
  notes: string | null;
  description: string | null;
  date_posted: string | null;
  created_at: string | null;
  skills: string[];
  tags: string[];
  categories: string[];
  zones: string[];
}

export interface Meta {
  statuses: Status[];
  tags: string[];
  skills: string[];
  cities: string[];
  zones: string[];
  categories: string[];
}

export interface SearchConfig {
  locations: string[];
  searches: string[];
  search_tags: string[];
  search_skills: string[];
  contract: string;
  skills: string[];
  categories: Record<string, string[]>;
  country: string;
  radius_km: number;
  results_per_search: number;
  max_age_days: number;
  parallel_searches: number;
  per_site: number;
  /** une clé SerpAPI est enregistrée pour ce compte (la clé elle-même ne sort jamais du serveur) */
  serpapi_key_set: boolean;
  /** Google Jobs utilisable : clé du compte ou clé commune du serveur */
  google_available: boolean;
  sites: string[];
}

export interface ConfigResponse {
  config: SearchConfig;
  available_sites: string[];
}

export interface ScrapeStep {
  index: number;
  total: number;
  site: string;
  query: string;
  location: string;
}

export interface ScrapeState {
  running: boolean;
  started: boolean;
  stopped: boolean;
  new_jobs: number;
  step: ScrapeStep | null;
  log: string[];
}

export interface ScrapePlan {
  queries: { query: string; tag: string }[];
  steps: number;
  locations: number;
  sites: number;
}

const STATUS_COLORS: Record<string, string> = {
  a_trier: '#9ca3af',
  interesse: '#3b82f6',
  postule: '#8b5cf6',
  en_attente: '#f59e0b',
  entretien: '#10b981',
  refuse: '#ef4444',
  pas_interesse: '#d1d5db',
};
export const statusColor = (key: string): string => STATUS_COLORS[key] || '#9ca3af';

const SITE_NAMES: Record<string, string> = { linkedin: 'LinkedIn', hellowork: 'HelloWork', indeed: 'Indeed', google: 'Google' };
export const siteName = (site: string): string => SITE_NAMES[site] || site;

export const collator = new Intl.Collator('fr', { sensitivity: 'base' });

export const companyOf = (j: Job): string => (j.company || '').trim() || '(inconnue)';

export const sourceOf = (j: Job): string => (j.source || '').split(' ')[0];

/** Date de publication si connue, sinon date d'ajout dans la base. */
export const dateKey = (j: Job): string => (j.date_posted || j.created_at || '').slice(0, 10);

export function relativeDate(d: string): string {
  if (!d) return '';
  const days = Math.round((new Date().setHours(0, 0, 0, 0) - new Date(d + 'T00:00:00').getTime()) / 864e5);
  if (Number.isNaN(days)) return '';
  if (days <= 0) return "aujourd'hui";
  if (days === 1) return 'hier';
  if (days < 30) return `il y a ${days} j`;
  return new Date(d).toLocaleDateString('fr-FR', { day: 'numeric', month: 'short' });
}

/** Nombre d'offres par valeur (une offre peut en porter plusieurs : tags, compétences…). */
export function countBy(jobs: readonly Job[], fn: (j: Job) => string | string[] | null | undefined): Map<string, number> {
  const m = new Map<string, number>();
  for (const j of jobs) {
    const values = fn(j);
    for (const v of Array.isArray(values) ? values : [values]) if (v) m.set(v, (m.get(v) || 0) + 1);
  }
  return m;
}

export const plural = (n: number): string => (n > 1 ? 's' : '');
