import { Component, ElementRef, Injector, afterNextRender, inject, signal, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { ChipEditor } from './chip-editor';
import { KanbanStore } from './kanban-store';
import { ConfigResponse } from './models';
import { CHECKS_CSS, FORM_GRID_CSS } from './shared-styles';

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
        <h2>Paramètres de recherche</h2>
        <div class="muted">Ce qui est cherché quand tu cliques sur « Chercher des offres ».</div>
      </div>
      <section class="section">
        <h3>Où chercher</h3>
        <p>Villes, régions ou pays. Chaque recherche est lancée dans chaque zone. Entrée pour ajouter.</p>
        <app-chip-editor placeholder="ex : Toulouse, Occitanie, Île-de-France" list="dl-zones" [(values)]="locations" (changed)="markDirty()" />
        <div class="form-grid">
          <label>Pays<input placeholder="France" [(ngModel)]="country" (ngModelChange)="markDirty()" /></label>
          <label>Rayon autour d'une ville (km)<input type="number" min="1" max="200" [(ngModel)]="radius" (ngModelChange)="markDirty()" /></label>
        </div>
      </section>
      <section class="section">
        <h3>Quoi chercher</h3>
        <p>Recherches complètes, envoyées telles quelles aux sites. Chaque recherche devient un tag sur les offres trouvées.</p>
        <app-chip-editor placeholder="ex : stage développeur rust" list="dl-tags" [(values)]="searches" (changed)="markDirty()" />
        <p style="margin-top:16px">Recherches larges : chaque tag et chaque compétence est cherché séparément, précédé du type de contrat (« stage devops », « stage python »…).</p>
        <div class="form-grid" style="margin-top:0">
          <label>Type de contrat<input placeholder="stage, alternance, CDD… (vide = aucun)" [(ngModel)]="contract" (ngModelChange)="markDirty()" /></label>
        </div>
        <label class="lbl" style="display:block; margin:14px 0 5px; font-size:13px; font-weight:500">Tags de recherche</label>
        <app-chip-editor placeholder="ex : Cybersécurité, DevOps / Cloud" list="dl-cats" [(values)]="searchTags" (changed)="markDirty()" />
        <label class="lbl" style="display:block; margin:14px 0 5px; font-size:13px; font-weight:500">Compétences à chercher</label>
        <app-chip-editor placeholder="ex : Python, Docker" list="dl-skills" [(values)]="searchSkills" (changed)="markDirty()" />
      </section>
      <section class="section">
        <h3>Catégories automatiques</h3>
        <p>Une offre entre dans une catégorie si son titre contient un mot-clé, ou si sa description en contient au moins 3.</p>
        <div class="cat-list">
          @for (cat of categories(); track cat.id) {
            <div class="cat-row">
              <input #catName placeholder="Nom de la catégorie" [(ngModel)]="cat.name" (ngModelChange)="markDirty()" />
              <app-chip-editor placeholder="mot-clé…" [(values)]="cat.keywords" (changed)="markDirty()" />
              <button class="btn-ghost" title="Supprimer la catégorie" (click)="removeCategory(cat)">×</button>
            </div>
          }
        </div>
        <div style="margin-top:10px"><button (click)="addCategory()">+ Ajouter une catégorie</button></div>
      </section>
      <section class="section">
        <h3>Compétences détectées</h3>
        <p>Repérées automatiquement dans les descriptions des offres.</p>
        <app-chip-editor placeholder="ex : Svelte" list="dl-skills" [(values)]="skills" (changed)="markDirty()" />
        <div style="margin-top:10px; display:flex; gap:10px; align-items:center">
          <button (click)="redetect()">Appliquer aux offres déjà enregistrées</button>
          <span class="muted small">{{ redetectStatus() }}</span>
        </div>
      </section>
      <section class="section">
        <h3>Options</h3>
        <div class="form-grid">
          <label>Résultats par site, recherche et zone<input type="number" min="1" max="200" [(ngModel)]="limit" (ngModelChange)="markDirty()" /></label>
          <label>Âge maximum des offres (jours)<input type="number" min="1" max="365" [(ngModel)]="age" (ngModelChange)="markDirty()" /></label>
          <label>Recherches en parallèle<input type="number" min="1" max="32" [(ngModel)]="parallel" (ngModelChange)="markDirty()" /></label>
          <label>Max simultanées par site<input type="number" min="1" max="8" [(ngModel)]="perSite" (ngModelChange)="markDirty()" /></label>
          <label>Clé SerpAPI (Google Jobs)<input type="password" autocomplete="off" placeholder="optionnelle" [(ngModel)]="serpapi" (ngModelChange)="markDirty()" /></label>
        </div>
        <div class="checks">
          <span class="muted small">Sites :</span>
          @for (site of availableSites(); track site) {
            <label><input type="checkbox" [checked]="sites().has(site)" (change)="toggleSite(site, $any($event.target).checked)" />{{ site }}</label>
          }
        </div>
      </section>
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
    `
    :host { display: block; flex: 1; overflow-y: auto; padding: 24px 20px 100px; }
    .settings-inner { max-width: 760px; margin: 0 auto; display: flex; flex-direction: column; gap: 28px; }
    .settings-inner h2 { margin: 0 0 2px; font-size: 20px; }
    .section h3 { margin: 0; font-size: 15px; }
    .section > p { margin: 3px 0 12px; color: var(--muted); font-size: 13px; }
    .section { padding-bottom: 28px; border-bottom: 1px solid var(--line); }
    .section:last-child { border-bottom: none; }
    .cat-list { display: flex; flex-direction: column; gap: 10px; }
    .cat-row { display: grid; grid-template-columns: 180px 1fr auto; gap: 8px; align-items: start; }
    .cat-row > input { font-weight: 600; }
    .savebar { position: fixed; left: 50%; bottom: 20px; transform: translateX(-50%); background: var(--text); color: #fff; border-radius: 12px; padding: 10px 10px 10px 18px; display: flex; align-items: center; gap: 14px; box-shadow: var(--shadow-lg); z-index: 30; }
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
  private store = inject(KanbanStore);
  private injector = inject(Injector);

  protected readonly locations = signal<string[]>([]);
  protected readonly searches = signal<string[]>([]);
  protected readonly searchTags = signal<string[]>([]);
  protected readonly searchSkills = signal<string[]>([]);
  protected readonly skills = signal<string[]>([]);
  protected readonly contract = signal('');
  protected readonly country = signal('');
  protected readonly radius = signal<number | null>(null);
  protected readonly limit = signal<number | null>(null);
  protected readonly age = signal<number | null>(null);
  protected readonly parallel = signal<number | null>(null);
  protected readonly perSite = signal<number | null>(null);
  protected readonly serpapi = signal('');
  protected readonly categories = signal<CategoryRow[]>([]);
  protected readonly availableSites = signal<string[]>([]);
  protected readonly sites = signal<ReadonlySet<string>>(new Set());

  protected readonly dirty = signal(false);
  protected readonly redetectStatus = signal('');

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
    this.searchTags.set(c.search_tags);
    this.searchSkills.set(c.search_skills);
    this.contract.set(c.contract);
    this.skills.set(c.skills);
    this.country.set(c.country);
    this.radius.set(c.radius_km);
    this.limit.set(c.results_per_search);
    this.age.set(c.max_age_days);
    this.parallel.set(c.parallel_searches);
    this.perSite.set(c.per_site);
    this.serpapi.set(c.serpapi_key);
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
      search_tags: this.searchTags(),
      search_skills: this.searchSkills(),
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
      serpapi_key: this.serpapi(),
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
