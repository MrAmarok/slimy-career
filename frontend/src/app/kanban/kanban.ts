import { CdkDrag, CdkDragDrop, CdkDropList, CdkDropListGroup } from '@angular/cdk/drag-drop';
import { Component, ElementRef, WritableSignal, afterRenderEffect, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { UserMenu } from '../core/user-menu';
import { AddJobDialog } from './add-job-dialog';
import { CompaniesTable, CompanySort } from './companies-table';
import { JobDrawer } from './job-drawer';
import { KanbanStore, safeStorage } from './kanban-store';
import { Job, collator, companyOf, countBy, dateKey, plural, relativeDate, siteName, sourceOf, statusColor } from './models';
import { SearchDrawer } from './search-drawer';
import { SettingsTab } from './settings-tab';
import { DOT_CSS, PILL_CSS, TAG_CSS } from './shared-styles';

type View = 'kanban' | 'entreprises' | 'parametres';
type SortKey = 'date' | 'country-city' | 'city' | 'company' | 'company-desc' | 'title';
type StringSet = ReadonlySet<string>;

const byDate = (a: Job, b: Job) => dateKey(b).localeCompare(dateKey(a)) || (b.created_at || '').localeCompare(a.created_at || '') || b.id - a.id;
const SORTS: Record<SortKey, (a: Job, b: Job) => number> = {
  date: byDate,
  'country-city': (a, b) => collator.compare(a.country || '~', b.country || '~') || collator.compare(a.city || '~', b.city || '~') || byDate(a, b),
  city: (a, b) => collator.compare(a.city || '~', b.city || '~') || byDate(a, b),
  company: (a, b) => collator.compare(companyOf(a), companyOf(b)) || byDate(a, b),
  'company-desc': (a, b) => collator.compare(companyOf(b), companyOf(a)) || byDate(a, b),
  title: (a, b) => collator.compare(a.title, b.title),
};

interface Option {
  value: string;
  n: number;
}

interface Card {
  job: Job;
  meta: string;
  categories: string[];
  skills: string[];
  date: string;
}

interface Column {
  key: string;
  label: string;
  color: string;
  cards: Card[];
}

interface ActiveFilter {
  label: string;
  value: string;
  clear: () => void;
}

/** Options d'une liste déroulante : « valeur (nombre) », par ordre alphabétique. */
const options = (counts: Map<string, number>): Option[] =>
  [...counts.entries()].sort((a, b) => collator.compare(a[0], b[0])).map(([value, n]) => ({ value, n }));

/** Cases à cocher d'un filtre : les plus fréquentes d'abord. */
function checklist(counts: Map<string, number>, chosen: StringSet, filter = '', selectedFirst = false): Option[] {
  return [...counts.entries()]
    .filter(([v]) => !filter || v.toLowerCase().includes(filter) || chosen.has(v))
    .sort((a, b) => (selectedFirst ? Number(chosen.has(b[0])) - Number(chosen.has(a[0])) : 0) || b[1] - a[1] || collator.compare(a[0], b[0]))
    .map(([value, n]) => ({ value, n }));
}

function toCard(job: Job): Card {
  const place = [job.city || job.location, job.country && job.country !== 'France' ? job.country : ''].filter(Boolean).join(', ');
  return {
    job,
    meta: [job.company, place].filter(Boolean).join(' · '),
    categories: job.categories.slice(0, 2),
    skills: job.skills.slice(0, 3),
    date: relativeDate(dateKey(job)),
  };
}

/** Page principale : tableau des candidatures, entreprises et paramètres de recherche. */
@Component({
  selector: 'app-kanban',
  imports: [FormsModule, RouterLink, CdkDropListGroup, CdkDropList, CdkDrag, UserMenu, JobDrawer, SearchDrawer, SettingsTab, AddJobDialog, CompaniesTable],
  providers: [KanbanStore],
  templateUrl: './kanban.html',
  styleUrl: './kanban.css',
  styles: [PILL_CSS, TAG_CSS, DOT_CSS],
  host: {
    '[class.picking]': 'picked().size > 0',
    '(document:keydown)': 'onKeydown($event)',
  },
})
export class Kanban {
  protected store = inject(KanbanStore);
  private router = inject(Router);
  private fragment = toSignal(inject(ActivatedRoute).fragment);

  protected readonly color = statusColor;
  protected readonly siteName = siteName;
  protected readonly plural = plural;
  /** sur mobile, il faut appuyer un instant sur une carte avant de la déplacer (sinon on ne peut plus faire défiler) */
  protected readonly dragDelay = { touch: 150, mouse: 0 };

  /* ---------- onglet affiché (fragment de l'URL : #kanban, #entreprises, #parametres) ---------- */
  protected readonly view = computed<View>(() => {
    const f = this.fragment();
    return f === 'entreprises' || f === 'parametres' ? f : 'kanban';
  });

  /* ---------- filtres ---------- */
  protected readonly q = signal('');
  protected readonly country = signal('');
  protected readonly city = signal('');
  protected readonly company = signal('');
  protected readonly source = signal('');
  protected readonly skillSearch = signal('');
  protected readonly cats = signal<StringSet>(new Set());
  protected readonly zones = signal<StringSet>(new Set());
  protected readonly tags = signal<StringSet>(new Set());
  protected readonly skills = signal<StringSet>(new Set());
  /** toujours les plus récentes en premier à l'ouverture */
  protected readonly sort = signal<SortKey>('date');
  protected readonly companySort = signal<CompanySort>({ key: 'count', desc: true });
  protected readonly filtersOpen = signal(false);

  private readonly countryCounts = computed(() => countBy(this.store.jobs(), (j) => j.country));
  private readonly cityCounts = computed(() => {
    const country = this.country();
    return countBy(this.store.jobs().filter((j) => !country || j.country === country), (j) => j.city);
  });
  private readonly companyCounts = computed(() => countBy(this.store.jobs(), companyOf));
  private readonly sourceCounts = computed(() => countBy(this.store.jobs(), sourceOf));
  private readonly catCounts = computed(() => countBy(this.store.jobs(), (j) => j.categories));
  private readonly zoneCounts = computed(() => countBy(this.store.jobs(), (j) => j.zones));
  private readonly tagCounts = computed(() => countBy(this.store.jobs(), (j) => j.tags));
  private readonly skillCounts = computed(() => countBy(this.store.jobs(), (j) => j.skills));

  protected readonly countryOptions = computed(() => options(this.countryCounts()));
  protected readonly cityOptions = computed(() => options(this.cityCounts()));
  protected readonly companyOptions = computed(() => options(this.companyCounts()));
  protected readonly sourceOptions = computed(() => options(this.sourceCounts()));
  /** catégories : boutons en haut, dans l'ordre des paramètres */
  protected readonly catPills = computed<Option[]>(() => {
    const counts = this.catCounts();
    const order = this.store.meta().categories;
    const names = [...order.filter((c) => counts.has(c)), ...[...counts.keys()].filter((c) => !order.includes(c))];
    return names.map((value) => ({ value, n: counts.get(value)! }));
  });
  protected readonly zoneList = computed(() => checklist(this.zoneCounts(), this.zones()));
  protected readonly tagList = computed(() => checklist(this.tagCounts(), this.tags()));
  protected readonly skillList = computed(() => checklist(this.skillCounts(), this.skills(), this.skillSearch().trim().toLowerCase(), true));
  /** suggestions de lieux (zones de recherche puis villes), sans doublon */
  protected readonly zoneSuggestions = computed(() => [...new Set([...this.store.meta().zones, ...this.store.meta().cities])]);

  /** Offres qui passent les filtres, dans l'ordre de tri choisi. */
  protected readonly visible = computed(() => {
    const words = this.q().trim().toLowerCase().split(/\s+/).filter(Boolean);
    const country = this.country(), city = this.city(), company = this.company(), src = this.source();
    const cats = this.cats(), zones = this.zones(), tags = this.tags(), skills = [...this.skills()];
    const matches = (j: Job) => {
      if (country && j.country !== country) return false;
      if (city && j.city !== city) return false;
      if (company && companyOf(j) !== company) return false;
      if (src && !(j.source || '').startsWith(src)) return false;
      if (cats.size && !j.categories.some((c) => cats.has(c))) return false;
      if (zones.size && !j.zones.some((z) => zones.has(z))) return false;
      if (tags.size && !j.tags.some((t) => tags.has(t))) return false;
      if (!skills.every((s) => j.skills.includes(s))) return false;
      if (words.length) {
        const hay = [j.title, j.company, j.location, j.country, j.skills.join(' '), j.tags.join(' '), j.categories.join(' '), j.notes].join(' ').toLowerCase();
        if (!words.every((w) => hay.includes(w))) return false;
      }
      return true;
    };
    return this.store.jobs().filter(matches).sort(SORTS[this.sort()]);
  });

  protected readonly columns = computed<Column[]>(() => {
    const visible = this.visible();
    return this.store.statuses().map((s) => ({
      key: s.key,
      label: s.label,
      color: statusColor(s.key),
      cards: visible.filter((j) => j.status === s.key).map(toCard),
    }));
  });

  protected readonly total = computed(() => {
    const all = this.store.jobs().length, shown = this.visible().length;
    return shown === all ? `${all} offres` : `${shown} offres sur ${all}`;
  });
  protected readonly noResults = computed(() => this.visible().length === 0 && this.store.jobs().length > 0);

  /** Pastilles « filtres actifs », chacune avec un × pour l'enlever. */
  protected readonly activeFilters = computed<ActiveFilter[]>(() => {
    const items: ActiveFilter[] = [];
    const push = (label: string, value: string, clear: () => void) => items.push({ label, value, clear });
    if (this.q().trim()) push('Recherche', this.q().trim(), () => this.q.set(''));
    if (this.country()) push('Pays', this.country(), () => this.setCountry(''));
    if (this.city()) push('Ville', this.city(), () => this.city.set(''));
    if (this.company()) push('Entreprise', this.company(), () => this.company.set(''));
    if (this.source()) push('Site', this.source(), () => this.source.set(''));
    this.zones().forEach((z) => push('Zone', z, () => this.toggle(this.zones, z, false)));
    this.tags().forEach((t) => push('Recherche', t, () => this.toggle(this.tags, t, false)));
    this.skills().forEach((s) => push('Compétence', s, () => this.toggle(this.skills, s, false)));
    return items;
  });
  /** nombre affiché sur le bouton « Filtres » (la recherche libre ne compte pas) */
  protected readonly filterCount = computed(() => this.activeFilters().length - (this.q().trim() ? 1 : 0));

  /* ---------- multi-sélection ---------- */
  private readonly pickedRaw = signal<ReadonlySet<number>>(new Set());
  /** Cartes sélectionnées : seulement des offres visibles (pas d'action sur des offres masquées par un filtre). */
  protected readonly picked = computed<ReadonlySet<number>>(() => {
    const raw = this.pickedRaw();
    if (!raw.size || this.view() === 'parametres') return raw;
    if (this.view() !== 'kanban') return new Set();
    const visible = new Set(this.visible().map((j) => j.id));
    return new Set([...raw].filter((id) => visible.has(id)));
  });
  /** pour Maj+clic */
  private lastPicked: number | null = null;
  /** carte en cours de déplacement */
  private readonly dragId = signal<number | null>(null);
  /** vrai quand on déplace une carte sélectionnée : toute la sélection suit */
  protected readonly groupDrag = computed(() => {
    const id = this.dragId();
    return id != null && this.picked().has(id);
  });

  /* ---------- recherche en arrière-plan ---------- */
  protected readonly progress = computed(() => {
    const s = this.store.scrape();
    // index = recherches terminées
    const pct = s?.step ? ((s.running ? s.step.index : s.step.total) / s.step.total) * 100 : 0;
    return Math.max(3, pct);
  });
  protected readonly log = computed(() => (this.store.scrape()?.log ?? []).join('\n'));

  private searchInput = viewChild.required<ElementRef<HTMLInputElement>>('searchInput');
  private logEl = viewChild.required<ElementRef<HTMLElement>>('logEl');
  private searchDrawer = viewChild.required(SearchDrawer);
  private addDialog = viewChild.required(AddJobDialog);

  constructor() {
    if (safeStorage.get('filters') === null) safeStorage.set('filters', innerWidth > 860 ? 'open' : 'closed');
    this.filtersOpen.set(safeStorage.get('filters') === 'open');

    // une valeur de filtre qui n'existe plus dans les offres (après rechargement, suppression…) est retirée
    effect(() => {
      this.store.jobs();
      untracked(() => this.pruneFilters());
    });
    // une offre que les filtres masquent sort de la sélection pour de bon
    effect(() => {
      const picked = this.picked();
      untracked(() => {
        if (picked.size !== this.pickedRaw().size) this.pickedRaw.set(picked);
      });
    });
    effect(() => {
      if (this.view() === 'parametres') untracked(() => this.store.closeDetail());
    });
    // le détail de la recherche reste calé sur la dernière ligne
    afterRenderEffect(() => {
      this.log();
      this.logEl().nativeElement.scrollTop = 1e9;
    });

    this.store
      .load()
      .then(() => this.store.resumeScrape())
      .catch((e) => this.store.toast(e?.message || 'Erreur'));
  }

  /* ---------- filtres ---------- */
  protected setCountry(country: string) {
    this.country.set(country);
    if (!this.cityCounts().has(this.city())) this.city.set('');
  }

  protected toggle(set: WritableSignal<StringSet>, value: string, on?: boolean) {
    const next = new Set(set());
    if (on ?? !next.has(value)) next.add(value);
    else next.delete(value);
    set.set(next);
  }

  protected clearCats() {
    this.cats.set(new Set());
  }

  protected toggleFilters() {
    this.filtersOpen.update((open) => !open);
    safeStorage.set('filters', this.filtersOpen() ? 'open' : 'closed');
  }

  protected resetFilters() {
    for (const text of [this.q, this.country, this.city, this.company, this.source, this.skillSearch]) text.set('');
    for (const set of [this.cats, this.tags, this.zones, this.skills]) set.set(new Set());
  }

  private pruneFilters() {
    const keep = (value: WritableSignal<string>, counts: Map<string, number>) => {
      if (value() && !counts.has(value())) value.set('');
    };
    keep(this.country, this.countryCounts());
    keep(this.city, this.cityCounts());
    keep(this.company, this.companyCounts());
    keep(this.source, this.sourceCounts());
    const keepAll = (set: WritableSignal<StringSet>, counts: Map<string, number>) => {
      if ([...set()].some((v) => !counts.has(v))) set.set(new Set([...set()].filter((v) => counts.has(v))));
    };
    keepAll(this.cats, this.catCounts());
    keepAll(this.zones, this.zoneCounts());
    keepAll(this.tags, this.tagCounts());
    keepAll(this.skills, this.skillCounts());
  }

  /** Clic sur une entreprise de l'onglet « Entreprises » : retour au tableau, filtré sur elle. */
  protected showCompany(name: string) {
    this.company.set(name);
    this.router.navigate([], { fragment: 'kanban' });
  }

  /* ---------- cartes ---------- */
  protected onCardClick(e: MouseEvent, job: Job) {
    if (e.shiftKey && this.lastPicked != null) this.pickRange(job);
    else if (e.ctrlKey || e.metaKey || this.picked().size) this.togglePick(job.id); // en mode sélection, un clic sélectionne
    else this.store.openDetail(job.id);
  }

  protected onPickClick(e: MouseEvent, job: Job) {
    e.stopPropagation();
    if (e.shiftKey) this.pickRange(job);
    else this.togglePick(job.id);
  }

  private togglePick(id: number) {
    const next = new Set(this.picked());
    if (next.has(id)) next.delete(id);
    else next.add(id);
    this.pickedRaw.set(next);
    this.lastPicked = id;
  }

  /** Maj+clic : sélectionne toutes les cartes entre la dernière cliquée et celle-ci, dans la même colonne. */
  private pickRange(job: Job) {
    const column = this.visible().filter((j) => j.status === job.status);
    const from = column.findIndex((j) => j.id === this.lastPicked);
    const to = column.findIndex((j) => j.id === job.id);
    if (from < 0 || to < 0) return this.togglePick(job.id);
    const next = new Set(this.picked());
    column.slice(Math.min(from, to), Math.max(from, to) + 1).forEach((j) => next.add(j.id));
    this.pickedRaw.set(next);
    this.lastPicked = job.id;
  }

  protected allPicked(col: Column): boolean {
    const picked = this.picked();
    return col.cards.every((c) => picked.has(c.job.id));
  }

  protected toggleColumn(col: Column) {
    const all = this.allPicked(col);
    const next = new Set(this.picked());
    col.cards.forEach((c) => (all ? next.delete(c.job.id) : next.add(c.job.id)));
    this.pickedRaw.set(next);
  }

  protected clearPicked() {
    this.pickedRaw.set(new Set());
    this.lastPicked = null;
  }

  protected bulkStatus(status: string) {
    const ids = [...this.picked()];
    this.pickedRaw.set(new Set());
    this.store.bulkStatus(ids, status);
  }

  protected async bulkDelete() {
    const ids = [...this.picked()];
    if (!ids.length || !confirm(`Supprimer définitivement ${ids.length} offre${plural(ids.length)} ?`)) return;
    if (await this.store.bulkDelete(ids)) this.pickedRaw.set(new Set());
  }

  /* ---------- glisser-déposer ---------- */
  protected onDragStart(id: number) {
    this.store.setDragging(true);
    this.dragId.set(id);
  }

  protected onDragEnd() {
    this.dragId.set(null);
    this.store.setDragging(false);
  }

  /** Carte lâchée dans une autre colonne : change son statut (et celui de toute la sélection si elle en fait partie). */
  protected onDrop(e: CdkDragDrop<string, string, number>) {
    if (e.previousContainer === e.container) return;
    const id = e.item.data, status = e.container.data;
    if (this.picked().has(id) && this.picked().size > 1) this.bulkStatus(status);
    else this.store.setStatus(id, status);
  }

  /* ---------- recherche d'offres ---------- */
  protected openSearch() {
    this.searchDrawer().open();
  }

  protected openAdd() {
    this.addDialog().open();
  }

  /* ---------- raccourcis clavier ---------- */
  protected onKeydown(e: KeyboardEvent) {
    const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(document.activeElement?.tagName ?? '');
    if (e.key === 'Escape' && this.store.searchOpen()) {
      this.store.searchOpen.set(false);
      return;
    }
    if (e.key === 'Escape' && this.picked().size) {
      this.clearPicked();
      return;
    }
    if ((e.key === 'Delete' || e.key === 'Backspace') && this.picked().size && !typing) {
      e.preventDefault();
      this.bulkDelete();
      return;
    }
    if (e.key === 'Escape' && this.store.current()) this.store.closeDetail();
    if (e.key === '/' && !typing) {
      e.preventDefault();
      this.searchInput().nativeElement.focus();
    }
  }
}
