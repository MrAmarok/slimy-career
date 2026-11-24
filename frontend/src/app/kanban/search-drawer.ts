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
          <div class="muted small" style="margin-top:3px">L'application tape tes recherches sur les sites d'emploi et range les offres trouvées dans « À trier ».</div>
        </div>
        <button class="btn-ghost" title="Fermer (Échap)" style="font-size:18px; padding:2px 9px" (click)="close()">×</button>
      </div>
      <div class="drawer-body" #body>
        <div class="sd-section">
          <h4><span class="num">1</span>Quel contrat ?</h4>
          <div class="contract-pills">
            @for (c of contracts; track c) {
              <button type="button" class="pill" [class.on]="contract().toLowerCase() === c.toLowerCase()" (click)="contract.set(c); planSoon()">
                {{ c ? c[0].toUpperCase() + c.slice(1) : 'Peu importe' }}
              </button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4><span class="num">2</span>Quels mots-clés ?</h4>
          <p class="help">Un métier, un domaine ou une technologie. Chaque mot-clé fait l'objet d'une recherche à part{{ contract() ? ', précédé du contrat' : '' }} : {{ example() }}.</p>
          <app-chip-editor placeholder="ex : développeur, DevOps, Python…  (Entrée pour ajouter)" list="dl-skills" [(values)]="keywords" (changed)="planSoon()" />
          @if (categorySuggestions().length) {
            <div class="suggest"><span class="suggest-label">Tes catégories</span>
              @for (s of categorySuggestions(); track s.value) {
                <button type="button" class="pill" [class.on]="s.on" (click)="toggle(keywords, s)">{{ s.value }}</button>
              }
            </div>
          }
          <div class="suggest"><span class="suggest-label">Compétences</span>
            @for (s of skillSuggestions(); track s.value) {
              <button type="button" class="pill" [class.on]="s.on" (click)="toggle(keywords, s)">{{ s.value }}</button>
            }
          </div>
          <details class="exact" [open]="exactOpen()">
            <summary>Ajouter une recherche exacte (facultatif)</summary>
            <p class="help">Une phrase envoyée telle quelle aux sites, sans le contrat devant. Utile pour une recherche précise : « alternance développeur Rust ».</p>
            <app-chip-editor placeholder="ex : stage développeur python" list="dl-tags" [(values)]="searches" (changed)="planSoon()" />
          </details>
        </div>
        <div class="sd-section">
          <h4><span class="num">3</span>Où ?</h4>
          <p class="help">Villes, régions ou pays. Chaque recherche est refaite dans chaque lieu.</p>
          <app-chip-editor placeholder="ex : Toulouse, Occitanie, France" list="dl-zones" [(values)]="locations" (changed)="planSoon()" />
          <div class="suggest">
            @for (s of locationSuggestions(); track s.value) {
              <button type="button" class="pill" [class.on]="s.on" (click)="toggle(locations, s)">{{ s.value }}</button>
            }
          </div>
        </div>
        <div class="sd-section">
          <h4><span class="num">4</span>Sur quels sites ?</h4>
          <div class="checks" style="margin-top:0">
            @for (site of availableSites(); track site) {
              <label [class.off]="site === 'google' && !config()?.google_available">
                <input type="checkbox" [checked]="sites().has(site)" (change)="toggleSite(site, $any($event.target).checked)" />{{ siteLabel(site) }}
                @if (site === 'google' && !config()?.google_available) {
                  <span class="muted small">(clé à ajouter dans Paramètres)</span>
                }
              </label>
            }
          </div>
        </div>
        <div class="plan">
          <div class="plan-title">Ce qui va être lancé</div>
          @if (plan(); as p) {
            <div class="total">{{ p.steps }} recherche{{ plural(p.steps) }} <span class="muted">· {{ duration(p) }}</span></div>
            <div class="muted small">{{ planDetail(p) }}</div>
            <div class="plan-label">Ce qui sera tapé sur chaque site :</div>
            <div class="queries">
              @for (q of p.queries; track $index) {
                <span class="tag">{{ q.query }}</span>
              }
            </div>
            @if (p.steps > 40) {
              <div class="warn">C'est beaucoup. Retire des mots-clés ou des lieux pour aller plus vite ; tu peux continuer à utiliser le tableau pendant la recherche.</div>
            }
          } @else {
            <div class="muted">{{ planText() }}</div>
          }
        </div>
      </div>
      <div class="drawer-foot">
        <label class="remember" title="Ces choix deviennent ta recherche par défaut (modifiable dans Paramètres)"><input type="checkbox" [(ngModel)]="remember" /> Retenir ces choix</label>
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
    .sd-section h4 { display: flex; align-items: center; gap: 8px; font-size: 14px; text-transform: none; letter-spacing: 0; color: var(--text); margin-bottom: 8px; }
    .num { width: 20px; height: 20px; border-radius: 50%; background: var(--accent-soft); color: var(--accent-text); display: grid; place-items: center; font-size: 12px; font-weight: 700; flex-shrink: 0; }
    .help { margin: 0 0 8px; color: var(--muted); font-size: 12.5px; line-height: 1.45; }
    .suggest { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; align-items: center; }
    .suggest-label { font-size: 12px; color: var(--muted); margin-right: 2px; flex-basis: 100%; }
    .exact { margin-top: 12px; border-top: 1px dashed var(--line-strong); padding-top: 10px; }
    .exact summary { cursor: pointer; font-size: 13px; color: var(--secondary); font-weight: 500; }
    .exact .help { margin-top: 8px; }
    .checks label.off { color: var(--muted); }
    .suggest .pill { font-size: 12px; padding: 3px 10px; }
    .suggest .pill::before { content: "+ "; color: var(--faint); }
    .suggest .pill.on::before { content: "✓ "; color: var(--accent-text); }
    .contract-pills { display: flex; flex-wrap: wrap; gap: 6px; }
    .plan { background: var(--soft); border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; font-size: 13px; }
    .plan-title { font-size: 12px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; margin-bottom: 6px; }
    .plan .total { font-weight: 600; font-size: 15px; }
    .plan .total .muted { font-weight: 400; font-size: 13px; }
    .plan-label { margin-top: 10px; font-size: 12px; color: var(--muted); }
    .plan .queries { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; max-height: 120px; overflow-y: auto; }
    .plan .warn { color: var(--warn); margin-top: 6px; font-size: 12px; }
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
  protected readonly plural = plural;

  protected readonly config = signal<SearchConfig | null>(null);
  protected readonly searches = signal<string[]>([]);
  /** mots-clés : métiers, domaines ou technologies, chacun cherché à part (précédé du contrat) */
  protected readonly keywords = signal<string[]>([]);
  protected readonly locations = signal<string[]>([]);
  protected readonly contract = signal('stage');
  protected readonly availableSites = signal<string[]>([]);
  protected readonly sites = signal<ReadonlySet<string>>(new Set());
  protected readonly remember = signal(true);

  protected readonly plan = signal<ScrapePlan | null>(null);
  protected readonly planText = signal('');
  protected readonly startDisabled = signal(false);
  protected readonly startLabel = signal('Lancer');

  protected readonly exactOpen = signal(false);

  /** catégories du tableau : un clic cherche les mots de leur nom (« DevOps / Cloud » : « DevOps » et « Cloud ») */
  protected readonly categorySuggestions = computed(() => {
    const c = this.config();
    return c ? suggestions(Object.keys(c.categories), this.keywords()) : [];
  });
  /** compétences : les plus fréquentes dans tes offres d'abord, puis le reste de la liste */
  protected readonly skillSuggestions = computed(() => {
    const c = this.config();
    if (!c) return [];
    const counts = countBy(this.store.jobs(), (j) => j.skills);
    const list = [...c.skills].sort((a, b) => (counts.get(b) || 0) - (counts.get(a) || 0) || collator.compare(a, b));
    return suggestions(list, this.keywords(), 14);
  });
  /** exemple de ce qui est tapé sur les sites, avec le contrat et le premier mot-clé choisis */
  protected readonly example = computed(() => {
    // un nom de catégorie « DevOps / Cloud » est cherché mot par mot
    const word = (this.keywords()[0] || 'DevOps').split('/')[0].trim();
    return `« ${[this.contract(), word].filter(Boolean).join(' ')} »`;
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
    // tags et compétences de recherche sont une seule liste : ils sont cherchés de la même façon
    this.keywords.set(suggestions([...c.search_tags, ...c.search_skills], []).map((s) => s.value));
    this.exactOpen.set(c.searches.length > 0);
    this.locations.set(c.locations);
    this.contract.set(c.contract);
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

  protected planDetail(p: ScrapePlan): string {
    const n = p.queries.length;
    return `${n} recherche${plural(n)} différente${plural(n)} × ${p.locations} lieu${p.locations > 1 ? 'x' : ''} × ${p.sites} site${plural(p.sites)}`;
  }

  protected duration(p: ScrapePlan): string {
    const minutes = Math.round(p.steps * 0.4);
    return minutes < 1 ? "moins d'une minute" : `environ ${minutes} min`;
  }

  private request() {
    // le texte en cours de saisie dans un éditeur compte aussi
    this.editors().forEach((e) => e.flush());
    return {
      searches: this.searches(),
      search_tags: this.keywords(),
      search_skills: [],
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
    this.startLabel.set(running ? 'Une recherche tourne déjà' : plan.steps ? `Lancer ${plan.steps} recherche${plural(plan.steps)}` : 'Lancer');
    if (!plan.queries.length) this.showText('Ajoute au moins un mot-clé pour lancer une recherche.');
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
