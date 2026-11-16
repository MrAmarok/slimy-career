import { Component, DestroyRef, ElementRef, computed, inject, signal, viewChild, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { ChipEditor } from './chip-editor';
import { KanbanStore } from './kanban-store';
import { ConfigResponse, ScrapePlan, SearchConfig, collator, countBy, plural, siteName } from './models';
import { CHECKS_CSS, DRAWER_CSS, PILL_CSS, TAG_CSS } from './shared-styles';

const CONTRACTS = ['stage', 'alternance', 'CDD', 'CDI', 'freelance', ''];

interface Suggestion {
  value: string;
  on: boolean;
}

/** Valeurs proposées sous un éditeur : sans doublon (à la casse près), cochées si déjà choisies. */
function suggestions(values: string[], chosen: string[], limit = Infinity): Suggestion[] {
  const picked = new Set(chosen.map((v) => v.toLowerCase()));
  return values
    .filter((v, i, a) => a.findIndex((x) => x.toLowerCase() === v.toLowerCase()) === i)
    .slice(0, limit)
    .map((value) => ({ value, on: picked.has(value.toLowerCase()) }));
}

/** Panneau « Chercher des offres » : choix des recherches, aperçu du plan, lancement en arrière-plan. */
@Component({
  selector: 'app-search-drawer',
  imports: [FormsModule, ChipEditor],
  template: `
    <div class="drawer" [class.open]="store.searchOpen()" [attr.aria-hidden]="!store.searchOpen()">
      <div class="drawer-head">
        <div style="flex:1">
          <h2>Chercher des offres</h2>
          <div class="muted small" style="margin-top:3px">Pré-rempli avec tes paramètres. Une offre n'a pas besoin de tout cocher : chaque recherche, tag ou compétence est cherché séparément.</div>
        </div>
        <button class="btn-ghost" title="Fermer (Échap)" style="font-size:18px; padding:2px 9px" (click)="close()">×</button>
      </div>
      <div class="drawer-body" #body>
        <div class="sd-section">
          <h4>Quoi chercher <span class="hint">recherches complètes, envoyées telles quelles</span></h4>
          <app-chip-editor placeholder="ex : stage développeur python" list="dl-tags" [(values)]="searches" (changed)="planSoon()" />
        </div>
        <div class="sd-section">
          <h4>Type de contrat <span class="hint">ajouté devant chaque tag et compétence</span></h4>
          <div class="contract-pills">
            @for (c of contracts; track c) {
              <button type="button" class="pill" [class.on]="contract().toLowerCase() === c.toLowerCase()" (click)="contract.set(c); planSoon()">
                {{ c ? c[0].toUpperCase() + c.slice(1) : 'Aucun' }}
              </button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4>Tags <span class="hint">domaines, chacun cherché séparément</span></h4>
          <app-chip-editor placeholder="Ajouter un tag…" list="dl-cats" [(values)]="tags" (changed)="planSoon()" />
          <div class="suggest">
            @for (s of tagSuggestions(); track s.value) {
              <button type="button" class="pill" [class.on]="s.on" (click)="toggle(tags, s)">{{ s.value }}</button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4>Compétences <span class="hint">lesquelles chercher ?</span></h4>
          <app-chip-editor placeholder="Ajouter une compétence…" list="dl-skills" [(values)]="skills" (changed)="planSoon()" />
          <input type="search" placeholder="Filtrer les suggestions…" style="width:100%; margin-top:8px" [(ngModel)]="skillFilter" />
          <div class="suggest">
            @for (s of skillSuggestions(); track s.value) {
              <button type="button" class="pill" [class.on]="s.on" (click)="toggle(skills, s)">{{ s.value }}</button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4>Où <span class="hint">villes, régions ou pays</span></h4>
          <app-chip-editor placeholder="ex : Toulouse" list="dl-zones" [(values)]="locations" (changed)="planSoon()" />
          <div class="suggest">
            @for (s of locationSuggestions(); track s.value) {
              <button type="button" class="pill" [class.on]="s.on" (click)="toggle(locations, s)">{{ s.value }}</button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4>Sites</h4>
          <div class="checks" style="margin-top:0">
            @for (site of availableSites(); track site) {
              <label [attr.title]="site === 'google' && !config()?.serpapi_key ? 'Ignoré sans clé SerpAPI (voir Paramètres)' : null">
                <input type="checkbox" [checked]="sites().has(site)" (change)="toggleSite(site, $any($event.target).checked)" />{{ siteLabel(site) }}
              </label>
            }
          </div>
        </div>
        <div class="plan">
          @if (plan(); as p) {
            <div class="total">{{ planTotal(p) }}</div>
            <div class="queries">
              @for (q of p.queries; track $index) {
                <span class="tag" [title]="'tag : ' + q.tag">{{ q.query }}</span>
              }
            </div>
            @if (p.steps > 40) {
              <div class="warn">C'est beaucoup : compte environ {{ minutes(p) }} min. Tu peux continuer à utiliser le tableau pendant ce temps.</div>
            }
          } @else {
            {{ planText() }}
          }
        </div>
      </div>
      <div class="drawer-foot">
        <label class="remember"><input type="checkbox" [(ngModel)]="remember" /> Garder ces choix pour la prochaine fois</label>
        <span class="spacer"></span>
        <button class="btn-ghost" (click)="close()">Annuler</button>
        <button class="btn-primary" [disabled]="startDisabled()" (click)="start()">{{ startLabel() }}</button>
      </div>
    </div>
  `,
  styles: [
    DRAWER_CSS,
    PILL_CSS,
    TAG_CSS,
    CHECKS_CSS,
    `
    .sd-section h4 { display: flex; align-items: baseline; gap: 8px; }
    .sd-section h4 .hint { text-transform: none; letter-spacing: 0; font-weight: 400; color: var(--faint); font-size: 12px; }
    .suggest { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
    .suggest .pill { font-size: 12px; padding: 3px 10px; }
    .suggest .pill::before { content: "+ "; color: var(--faint); }
    .suggest .pill.on::before { content: "✓ "; color: var(--accent-text); }
    .contract-pills { display: flex; flex-wrap: wrap; gap: 6px; }
    .plan { background: var(--soft); border-radius: 10px; padding: 12px 14px; font-size: 13px; }
    .plan .total { font-weight: 600; }
    .plan .queries { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; max-height: 120px; overflow-y: auto; }
    .plan .warn { color: #b45309; margin-top: 6px; font-size: 12px; }
    .remember { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); }
    .remember input { accent-color: var(--accent); }
    `,
  ],
})
export class SearchDrawer {
  protected store = inject(KanbanStore);
  private api = inject(Api);

  protected readonly contracts = CONTRACTS;
  protected readonly siteLabel = siteName;

  protected readonly config = signal<SearchConfig | null>(null);
  protected readonly searches = signal<string[]>([]);
  protected readonly tags = signal<string[]>([]);
  protected readonly skills = signal<string[]>([]);
  protected readonly locations = signal<string[]>([]);
  protected readonly contract = signal('stage');
  protected readonly availableSites = signal<string[]>([]);
  protected readonly sites = signal<ReadonlySet<string>>(new Set());
  protected readonly skillFilter = signal('');
  protected readonly remember = signal(true);

  protected readonly plan = signal<ScrapePlan | null>(null);
  protected readonly planText = signal('');
  protected readonly startDisabled = signal(false);
  protected readonly startLabel = signal('Lancer');

  /** tags : catégories automatiques d'abord, puis les tags déjà utilisés */
  protected readonly tagSuggestions = computed(() => {
    const c = this.config();
    return c ? suggestions([...Object.keys(c.categories), ...c.search_tags], this.tags()) : [];
  });
  /** compétences : les plus fréquentes dans tes offres d'abord, puis le reste de la liste */
  protected readonly skillSuggestions = computed(() => {
    const c = this.config();
    if (!c) return [];
    const counts = countBy(this.store.jobs(), (j) => j.skills);
    const q = this.skillFilter().trim().toLowerCase();
    const list = [...c.skills]
      .sort((a, b) => (counts.get(b) || 0) - (counts.get(a) || 0) || collator.compare(a, b))
      .filter((s) => !q || s.toLowerCase().includes(q));
    return suggestions(list, this.skills(), q ? Infinity : 20);
  });
  protected readonly locationSuggestions = computed(() => {
    const c = this.config();
    if (!c) return [];
    const jobs = this.store.jobs();
    const topCities = [...countBy(jobs, (j) => j.city).entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([city]) => city);
    return suggestions([...c.locations, ...countBy(jobs, (j) => j.zones).keys(), ...topCities], this.locations(), 14);
  });

  private body = viewChild.required<ElementRef<HTMLElement>>('body');
  private editors = viewChildren(ChipEditor);
  private planTimer?: ReturnType<typeof setTimeout>;
  private planRequest = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.planTimer));
  }

  async open() {
    this.store.closeDetail();
    let res: ConfigResponse;
    try {
      res = await this.api.get<ConfigResponse>('/api/config');
    } catch (e: any) {
      this.store.toast(e?.message || 'Erreur');
      return;
    }
    const c = res.config;
    this.config.set(c);
    this.editors().forEach((e) => e.clearInput());
    this.searches.set(c.searches);
    this.tags.set(c.search_tags);
    this.skills.set(c.search_skills);
    this.locations.set(c.locations);
    this.contract.set(c.contract);
    this.skillFilter.set('');
    this.availableSites.set(res.available_sites);
    this.sites.set(new Set(c.sites));
    await this.updatePlan();
    this.body().nativeElement.scrollTop = 0;
    this.store.searchOpen.set(true);
  }

  protected close() {
    this.store.searchOpen.set(false);
  }

  /** Bouton de suggestion : un clic ajoute (ou retire) la valeur dans l'éditeur. */
  protected toggle(list: { (): string[]; set(v: string[]): void }, s: Suggestion) {
    const low = s.value.toLowerCase();
    list.set(s.on ? list().filter((x) => x.toLowerCase() !== low) : [...list(), s.value]);
    this.planSoon();
  }

  protected toggleSite(site: string, on: boolean) {
    const next = new Set(this.sites());
    if (on) next.add(site);
    else next.delete(site);
    this.sites.set(next);
    this.planSoon();
  }

  protected planSoon() {
    clearTimeout(this.planTimer);
    this.planTimer = setTimeout(() => this.updatePlan(), 250);
  }

  protected planTotal(p: ScrapePlan): string {
    const n = p.queries.length;
    return `${n} requête${plural(n)} × ${p.locations} zone${plural(p.locations)} × ${p.sites} site${plural(p.sites)} = ${p.steps} recherche${plural(p.steps)}`;
  }

  protected minutes(p: ScrapePlan): number {
    return Math.round(p.steps * 0.4);
  }

  private request() {
    // le texte en cours de saisie dans un éditeur compte aussi
    this.editors().forEach((e) => e.flush());
    return {
      searches: this.searches(),
      search_tags: this.tags(),
      search_skills: this.skills(),
      locations: this.locations(),
      contract: this.contract(),
      sites: this.availableSites().filter((s) => this.sites().has(s)),
    };
  }

  private showText(text: string) {
    this.plan.set(null);
    this.planText.set(text);
  }

  private async updatePlan() {
    const req = this.request();
    const id = ++this.planRequest;
    if (!req.sites.length) {
      this.showText('Choisis au moins un site.');
      this.startDisabled.set(true);
      return;
    }
    let plan: ScrapePlan;
    try {
      plan = await this.api.post<ScrapePlan>('/api/scrape/plan', req);
    } catch (e: any) {
      if (id === this.planRequest) {
        this.showText(e?.message || 'Erreur');
        this.startDisabled.set(true);
      }
      return;
    }
    if (id !== this.planRequest) return; // une demande plus récente est partie entre-temps
    const running = this.store.scrapeRunning();
    this.startDisabled.set(!plan.queries.length || running);
    this.startLabel.set(running ? 'Une recherche tourne déjà' : `Lancer ${plan.steps} recherche${plural(plan.steps)}`);
    if (!plan.queries.length) this.showText('Ajoute au moins une recherche, un tag ou une compétence.');
    else this.plan.set(plan);
  }

  protected async start() {
    const body = { ...this.request(), remember: this.remember() };
    try {
      await this.api.post('/api/scrape', body);
    } catch (e: any) {
      this.store.toast('Impossible de lancer : ' + (e?.message ?? ''));
      return;
    }
    this.close();
    this.store.toast('Recherche lancée en arrière-plan');
    this.store.scrapeStarted();
  }
}
