import { Component, ElementRef, Injector, afterNextRender, computed, inject, output, signal, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { ChipEditor } from './chip-editor';
import { KanbanStore } from './kanban-store';
import { ConfigResponse, countBy, plural, siteName } from './models';
import { CHECKS_CSS, FORM_GRID_CSS, PILL_CSS } from './shared-styles';

const CONTRACTS = ['stage', 'alternance', 'CDD', 'CDI', 'freelance', ''];

interface CategoryRow {
  id: number;
  name: string;
  keywords: string[];
}

/** Onglet « Paramètres » : ce qui est cherché, catégories automatiques, compétences détectées, options. */
@Component({
  selector: 'app-settings-tab',
  imports: [FormsModule, ChipEditor],
  template: `
    <div class="settings-inner">
      <div>
        <h2>Paramètres</h2>
        <div class="muted">Ta recherche habituelle, le classement des offres trouvées, et quelques réglages.</div>
      </div>

      <div class="group">
        <div class="group-head">
          <h3>Ma recherche habituelle</h3>
          <p>Ce qui est proposé d'office quand tu cliques sur « Chercher des offres ». Tu peux toujours l'ajuster au moment de lancer.</p>
        </div>
        <section class="section">
          <h4>Contrat</h4>
          <p>Ajouté devant chaque mot-clé : « {{ contract() || 'stage' }} DevOps ».</p>
          <div class="pills">
            @for (c of contractChoices(); track c) {
              <button type="button" class="pill" [class.on]="contract().toLowerCase() === c.toLowerCase()" (click)="contract.set(c); markDirty()">
                {{ c ? c[0].toUpperCase() + c.slice(1) : 'Peu importe' }}
              </button>
            }
          </div>
        </section>
        <section class="section">
          <h4>Mots-clés</h4>
          <p>Métiers, domaines ou technologies. Chaque mot-clé fait l'objet d'une recherche à part.</p>
          <app-chip-editor placeholder="ex : développeur, DevOps, Python…  (Entrée pour ajouter)" list="dl-skills" [(values)]="keywords" (changed)="markDirty()" />
        </section>
        <section class="section">
          <h4>Recherches exactes <span class="opt">facultatif</span></h4>
          <p>Phrases envoyées telles quelles aux sites, sans le contrat devant : « alternance développeur Rust ».</p>
          <app-chip-editor placeholder="ex : stage développeur python" list="dl-tags" [(values)]="searches" (changed)="markDirty()" />
        </section>
        <section class="section">
          <h4>Lieux</h4>
          <p>Villes, régions ou pays. Chaque recherche est refaite dans chaque lieu.</p>
          <app-chip-editor placeholder="ex : Toulouse, Occitanie, Île-de-France" list="dl-zones" [(values)]="locations" (changed)="markDirty()" />
          <div class="form-grid">
            <label>Rayon autour d'une ville (km)<input type="number" min="1" max="200" [(ngModel)]="radius" (ngModelChange)="markDirty()" /></label>
            <label>Pays des recherches<input placeholder="France" [(ngModel)]="country" (ngModelChange)="markDirty()" /></label>
          </div>
        </section>
        <section class="section">
          <h4>Sites</h4>
          <div class="checks" style="margin-top:0">
            @for (site of availableSites(); track site) {
              <label><input type="checkbox" [checked]="sites().has(site)" (change)="toggleSite(site, $any($event.target).checked)" />{{ siteLabel(site) }}</label>
            }
          </div>
          <label class="key">Clé SerpAPI, nécessaire pour Google Jobs <span class="opt">gratuite sur serpapi.com</span>
            <input type="password" autocomplete="off" [placeholder]="serpapiSet() ? 'clé enregistrée — saisir pour la remplacer' : 'sans clé, Google est ignoré'"
                   [(ngModel)]="serpapi" (ngModelChange)="serpapiRemove.set(false); markDirty()" />
            @if (serpapiSet() && !serpapi()) {
              <span class="small" style="display:flex; align-items:center; gap:6px; font-weight:400">
                <input type="checkbox" [checked]="serpapiRemove()" (change)="serpapiRemove.set($any($event.target).checked); markDirty()" /> Retirer la clé enregistrée
              </span>
            }
          </label>
        </section>
      </div>

      <div class="group">
        <div class="group-head">
          <h3>Classement des offres</h3>
          <p>Ce que l'application repère toute seule dans chaque offre trouvée, pour t'aider à trier.</p>
        </div>
        <section class="section">
          <h4>Catégories</h4>
          <p>Chaque offre est rangée dans les catégories dont elle contient les mots-clés (un mot-clé dans le titre suffit, sinon trois dans la description).
             Elles servent de filtre en haut du tableau et s'affichent en bleu sur les cartes. Enregistrer reclasse toutes tes offres.</p>
          <div class="cat-list">
            @for (cat of categories(); track cat.id) {
              <div class="cat-row">
                <div class="cat-name">
                  <input #catName placeholder="Nom de la catégorie" [(ngModel)]="cat.name" (ngModelChange)="markDirty()" />
                  <span class="muted small">{{ categoryCount(cat.name) }}</span>
                </div>
                <app-chip-editor placeholder="mot-clé à repérer…" [(values)]="cat.keywords" (changed)="markDirty()" />
                <button class="btn-ghost" title="Supprimer la catégorie" (click)="removeCategory(cat)">×</button>
              </div>
            }
          </div>
          <div style="margin-top:10px; display:flex; gap:12px; align-items:center; flex-wrap:wrap">
            <button (click)="addCategory()">+ Ajouter une catégorie</button>
          </div>
          @if (uncategorized().length; as n) {
            <details class="uncat">
              <summary><b>{{ n }} offre{{ plural(n) }} sans catégorie</b> sur {{ store.jobs().length }} <span class="muted">— voir lesquelles</span></summary>
              <p>Leurs titres donnent les mots-clés qui manquent : ajoute-les à une catégorie ci-dessus, ou crée-en une nouvelle.</p>
              <ul>
                @for (j of uncategorized().slice(0, 40); track j.id) {
                  <li><span class="t">{{ j.title }}</span><span class="muted small">{{ j.company }}</span></li>
                }
              </ul>
              <div style="display:flex; gap:12px; align-items:center; flex-wrap:wrap">
                <button (click)="showUncategorized.emit()">Voir ces offres dans le tableau</button>
                @if (n > 40) {
                  <span class="muted small">les 40 premières sont listées ici</span>
                }
              </div>
            </details>
          }
        </section>
        <section class="section">
          <h4>Compétences à repérer</h4>
          <p>Cherchées dans la description de chaque offre. Elles s'affichent sur les cartes et servent au filtre « Compétences » et à la comparaison avec ton CV.</p>
          <app-chip-editor placeholder="ex : Svelte" list="dl-skills" [(values)]="skills" (changed)="markDirty()" />
          <div style="margin-top:10px; display:flex; gap:10px; align-items:center; flex-wrap:wrap">
            <button (click)="redetect()">Relire les offres déjà enregistrées</button>
            <span class="muted small">{{ redetectStatus() || "À faire après avoir modifié la liste : sinon seules les nouvelles offres sont concernées." }}</span>
          </div>
        </section>
      </div>

      <details class="group advanced">
        <summary><h3>Réglages avancés</h3><span class="muted small">quantité d'offres et vitesse des recherches</span></summary>
        <section class="section">
          <div class="form-grid" style="margin-top:0">
            <label>Offres demandées par recherche<input type="number" min="1" max="200" [(ngModel)]="limit" (ngModelChange)="markDirty()" /><span class="note">par site et par lieu</span></label>
            <label>Âge maximum des offres (jours)<input type="number" min="1" max="365" [(ngModel)]="age" (ngModelChange)="markDirty()" /><span class="note">les offres plus anciennes sont ignorées</span></label>
            <label>Recherches en même temps<input type="number" min="1" max="32" [(ngModel)]="parallel" (ngModelChange)="markDirty()" /><span class="note">plus = plus rapide</span></label>
            <label>Dont sur un même site<input type="number" min="1" max="8" [(ngModel)]="perSite" (ngModelChange)="markDirty()" /><span class="note">trop haut, le site bloque les demandes</span></label>
          </div>
        </section>
      </details>
    </div>
    @if (dirty()) {
      <div class="savebar">
        <span>Modifications non enregistrées</span>
        <button class="btn-ghost" (click)="loadConfig()">Annuler</button>
        <button class="btn-primary" (click)="save()">Enregistrer</button>
      </div>
    }
  `,
  styles: [
    FORM_GRID_CSS,
    CHECKS_CSS,
    PILL_CSS,
    `
    :host { display: block; flex: 1; overflow-y: auto; padding: 24px 20px 100px; }
    .settings-inner { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 28px; }
    .settings-inner h2 { margin: 0 0 2px; font-size: 20px; }
    .group { border: 1px solid var(--line); border-radius: 14px; background: var(--surface); box-shadow: var(--shadow); }
    .group-head { padding: 18px 20px 14px; border-bottom: 1px solid var(--line); background: var(--soft); border-radius: 14px 14px 0 0; }
    .group h3 { margin: 0; font-size: 16px; }
    .group-head p { margin: 4px 0 0; color: var(--muted); font-size: 13px; }
    .section { padding: 16px 20px 18px; border-bottom: 1px solid var(--line); }
    .section:last-child { border-bottom: none; }
    .section h4 { margin: 0; font-size: 14px; display: flex; align-items: baseline; gap: 8px; }
    .section > p { margin: 3px 0 10px; color: var(--muted); font-size: 13px; }
    .opt { font-size: 12px; font-weight: 400; color: var(--faint); }
    .pills { display: flex; flex-wrap: wrap; gap: 6px; }
    .key { display: flex; flex-direction: column; gap: 5px; font-size: 13px; font-weight: 500; margin-top: 14px; max-width: 420px; }
    .note { font-size: 12px; font-weight: 400; color: var(--muted); }
    .advanced summary { cursor: pointer; padding: 16px 20px; display: flex; align-items: baseline; gap: 10px; list-style: none; }
    .advanced summary::-webkit-details-marker { display: none; }
    .advanced summary::before { content: "›"; color: var(--faint); font-size: 18px; transition: transform .15s; }
    .advanced[open] summary::before { transform: rotate(90deg); }
    .advanced[open] summary { border-bottom: 1px solid var(--line); }
    .cat-name { display: flex; flex-direction: column; gap: 3px; }
    .uncat { margin-top: 14px; border: 1px solid var(--warn-line); background: var(--warn-soft); border-radius: 10px; padding: 10px 14px; font-size: 13px; }
    .uncat summary { cursor: pointer; }
    .uncat p { margin: 8px 0; color: var(--muted); }
    .uncat ul { list-style: none; margin: 0 0 10px; padding: 0; max-height: 260px; overflow-y: auto; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; }
    .uncat li { display: flex; justify-content: space-between; gap: 12px; padding: 6px 10px; border-bottom: 1px solid var(--line); }
    .uncat li:last-child { border-bottom: none; }
    .uncat .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .uncat li .muted { white-space: nowrap; }
    .cat-list { display: flex; flex-direction: column; gap: 10px; }
    .cat-row { display: grid; grid-template-columns: 180px 1fr auto; gap: 8px; align-items: start; }
    .cat-name input { font-weight: 600; width: 100%; }
    .savebar { position: fixed; left: 50%; bottom: 20px; transform: translateX(-50%); background: var(--tip); color: #fff; border-radius: 12px; padding: 10px 10px 10px 18px; display: flex; align-items: center; gap: 14px; box-shadow: var(--shadow-lg); z-index: 30; }
    .savebar button { border-color: transparent; }
    .savebar .btn-ghost { color: #d1d5db; }
    .savebar .btn-ghost:hover { background: rgba(255,255,255,.1); color: #fff; }
    @media (max-width: 860px) {
      .cat-row { grid-template-columns: 1fr auto; }
      .cat-row app-chip-editor { grid-column: 1 / -1; grid-row: 2; }
    }
    `,
  ],
})
export class SettingsTab {
  private api = inject(Api);
  protected store = inject(KanbanStore);
  private injector = inject(Injector);

  protected readonly locations = signal<string[]>([]);
  protected readonly searches = signal<string[]>([]);
  /** mots-clés de recherche (anciens « tags » et « compétences à chercher », cherchés de la même façon) */
  protected readonly keywords = signal<string[]>([]);
  protected readonly skills = signal<string[]>([]);
  protected readonly contract = signal('');
  protected readonly country = signal('');
  protected readonly radius = signal<number | null>(null);
  protected readonly limit = signal<number | null>(null);
  protected readonly age = signal<number | null>(null);
  protected readonly parallel = signal<number | null>(null);
  protected readonly perSite = signal<number | null>(null);
  protected readonly serpapi = signal('');
  /** une clé est enregistrée côté serveur (elle n'est jamais renvoyée : le champ reste vide) */
  protected readonly serpapiSet = signal(false);
  protected readonly serpapiRemove = signal(false);
  protected readonly categories = signal<CategoryRow[]>([]);
  protected readonly availableSites = signal<string[]>([]);
  protected readonly sites = signal<ReadonlySet<string>>(new Set());

  protected readonly dirty = signal(false);
  protected readonly redetectStatus = signal('');

  protected readonly siteLabel = siteName;
  /** contrats proposés, plus celui déjà enregistré s'il est différent */
  protected readonly contractChoices = computed(() => {
    const current = this.contract().trim();
    return current && !CONTRACTS.some((c) => c.toLowerCase() === current.toLowerCase()) ? [current, ...CONTRACTS] : CONTRACTS;
  });
  private readonly categoryCounts = computed(() => countBy(this.store.jobs(), (j) => j.categories));
  /** offres qu'aucune catégorie n'a attrapées */
  protected readonly uncategorized = computed(() => this.store.jobs().filter((j) => !j.categories.length));
  protected readonly plural = plural;
  /** demande au tableau d'afficher les offres sans catégorie */
  readonly showUncategorized = output<void>();

  private editors = viewChildren(ChipEditor);
  private catNames = viewChildren<ElementRef<HTMLInputElement>>('catName');
  private nextCatId = 0;

  constructor() {
    this.loadConfig();
  }

  protected markDirty() {
    this.dirty.set(true);
  }

  protected async loadConfig() {
    let res: ConfigResponse;
    try {
      res = await this.api.get<ConfigResponse>('/api/config');
    } catch (e: any) {
      this.store.toast('Erreur : ' + (e?.message ?? ''));
      return;
    }
    const c = res.config;
    this.editors().forEach((e) => e.clearInput());
    this.locations.set(c.locations);
    this.searches.set(c.searches);
    this.keywords.set([...c.search_tags, ...c.search_skills].filter((v, i, a) => a.findIndex((x) => x.toLowerCase() === v.toLowerCase()) === i));
    this.contract.set(c.contract);
    this.skills.set(c.skills);
    this.country.set(c.country);
    this.radius.set(c.radius_km);
    this.limit.set(c.results_per_search);
    this.age.set(c.max_age_days);
    this.parallel.set(c.parallel_searches);
    this.perSite.set(c.per_site);
    this.serpapi.set('');
    this.serpapiSet.set(c.serpapi_key_set);
    this.serpapiRemove.set(false);
    this.categories.set(Object.entries(c.categories).map(([name, keywords]) => ({ id: this.nextCatId++, name, keywords })));
    this.availableSites.set(res.available_sites);
    this.sites.set(new Set(c.sites));
    this.dirty.set(false);
  }

  protected addCategory() {
    this.categories.update((list) => [...list, { id: this.nextCatId++, name: '', keywords: [] }]);
    this.markDirty();
    afterNextRender(() => this.catNames().at(-1)?.nativeElement.focus(), { injector: this.injector });
  }

  /** nombre d'offres actuellement rangées dans la catégorie (d'après le dernier enregistrement) */
  protected categoryCount(name: string): string {
    const n = this.categoryCounts().get(name.trim());
    return n ? `${n} offre${plural(n)}` : 'aucune offre';
  }

  protected removeCategory(cat: CategoryRow) {
    this.categories.update((list) => list.filter((c) => c !== cat));
    this.markDirty();
  }

  protected toggleSite(site: string, on: boolean) {
    const next = new Set(this.sites());
    if (on) next.add(site);
    else next.delete(site);
    this.sites.set(next);
    this.markDirty();
  }

  protected async save() {
    // le texte en cours de saisie dans un éditeur compte aussi
    this.editors().forEach((e) => e.flush());
    const body = {
      locations: this.locations(),
      searches: this.searches(),
      skills: this.skills(),
      search_tags: this.keywords(),
      search_skills: [],
      contract: this.contract().trim(),
      categories: Object.fromEntries(
        this.categories()
          .map((c): [string, string[]] => [c.name.trim(), c.keywords])
          .filter(([name]) => name),
      ),
      country: this.country().trim() || 'France',
      radius_km: Number(this.radius()) || 30,
      results_per_search: Number(this.limit()) || 25,
      max_age_days: Number(this.age()) || 30,
      parallel_searches: Number(this.parallel()) || 6,
      per_site: Number(this.perSite()) || 2,
      // champ vide = clé inchangée, sauf si « Retirer » est coché
      ...(this.serpapi().trim() ? { serpapi_key: this.serpapi().trim() } : this.serpapiRemove() ? { serpapi_key: '' } : {}),
      sites: this.availableSites().filter((s) => this.sites().has(s)),
    };
    try {
      await this.api.put('/api/config', body);
      await this.loadConfig();
      await this.store.load();
      this.store.toast('Paramètres enregistrés');
    } catch (e: any) {
      this.store.toast('Erreur : ' + (e?.message ?? ''));
    }
  }

  protected async redetect() {
    if (this.dirty() && !confirm('Les modifications non enregistrées ne seront pas prises en compte. Continuer ?')) return;
    this.redetectStatus.set('En cours…');
    try {
      const r = await this.api.post<{ updated: number }>('/api/redetect-skills');
      this.redetectStatus.set(`${r.updated} offre(s) mise(s) à jour`);
      await this.store.load();
    } catch (e: any) {
      this.redetectStatus.set(e?.message || 'Erreur');
    }
  }
}
