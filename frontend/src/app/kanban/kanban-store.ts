import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Api } from '../core/api';
import { Job, Meta, ScrapeState, Status, plural } from './models';

/** localStorage qui n'échoue jamais (navigation privée, stockage bloqué…). */
export const safeStorage = {
  get(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  set(key: string, value: string) {
    try {
      localStorage.setItem(key, value);
    } catch {}
  },
};

/**
 * État partagé de la page du tableau : offres, offre ouverte, message en bas d'écran et suivi de la recherche
 * d'offres en arrière-plan. Fourni par le composant Kanban : il vit et meurt avec la page.
 */
@Injectable()
export class KanbanStore {
  private api = inject(Api);

  readonly statuses = signal<Status[]>([]);
  readonly jobs = signal<Job[]>([]);
  readonly meta = signal<Meta>({ statuses: [], tags: [], skills: [], cities: [], zones: [], categories: [] });
  /** offres apparues au dernier rechargement (mises en évidence un instant) */
  readonly freshIds = signal<ReadonlySet<number>>(new Set());

  readonly currentId = signal<number | null>(null);
  readonly current = computed(() => {
    const id = this.currentId();
    return id == null ? null : (this.jobs().find((j) => j.id === id) ?? null);
  });
  readonly searchOpen = signal(false);

  readonly toastText = signal('');
  readonly toastShown = signal(false);
  private toastTimer?: ReturnType<typeof setTimeout>;

  readonly scrape = signal<ScrapeState | null>(null);
  readonly scrapeShown = signal(false);
  readonly logOpen = signal(false);
  readonly scrapeRunning = computed(() => !!this.scrape()?.running);
  private poll: ReturnType<typeof setInterval> | null = null;
  private lastNew = 0;

  private dragging = false;
  private refreshPending = false;
  private destroyed = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      clearTimeout(this.toastTimer);
      if (this.poll) clearInterval(this.poll);
      this.poll = null;
    });
  }

  toast(msg: string) {
    this.toastText.set(msg);
    this.toastShown.set(true);
    clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toastShown.set(false), 2500);
  }

  statusLabel(key: string): string {
    return this.statuses().find((s) => s.key === key)?.label ?? key;
  }

  /* ---------- chargement ---------- */
  async load({ markFresh = false } = {}) {
    const [meta, jobs] = await Promise.all([this.api.get<Meta>('/api/meta'), this.api.get<Job[]>('/api/jobs')]);
    if (this.destroyed) return;
    const known = new Set(this.jobs().map((j) => j.id));
    this.freshIds.set(markFresh ? new Set(jobs.filter((j) => !known.has(j.id)).map((j) => j.id)) : new Set());
    this.statuses.set(meta.statuses);
    this.meta.set(meta);
    this.jobs.set(jobs);
    const id = this.currentId();
    if (id != null && !jobs.some((j) => j.id === id)) this.currentId.set(null);
  }

  /* ---------- panneau de détail ---------- */
  openDetail(id: number) {
    this.searchOpen.set(false);
    this.currentId.set(id);
  }

  closeDetail() {
    this.currentId.set(null);
  }

  /* ---------- actions sur les offres ---------- */
  async patch(id: number, body: Partial<Job>): Promise<void> {
    const updated = await this.api.patch<Job>(`/api/jobs/${id}`, body);
    this.jobs.update((list) => list.map((j) => (j.id === id ? { ...j, ...updated } : j)));
  }

  private setLocal(statusOf: ReadonlyMap<number, string | undefined>) {
    this.jobs.update((list) => list.map((j) => (statusOf.get(j.id) != null ? { ...j, status: statusOf.get(j.id)! } : j)));
  }

  async setStatus(id: number, status: string) {
    const job = this.jobs().find((j) => j.id === id);
    if (!job || job.status === status) return;
    this.setLocal(new Map([[id, status]]));
    try {
      await this.patch(id, { status });
      this.toast(`Déplacée dans « ${this.statusLabel(status)} »`);
    } catch {
      this.setLocal(new Map([[id, job.status]]));
      this.toast('Échec de la mise à jour');
    }
  }

  async bulkStatus(ids: number[], status: string) {
    const prev = new Map(ids.map((id) => [id, this.jobs().find((j) => j.id === id)?.status]));
    this.setLocal(new Map(ids.map((id) => [id, prev.get(id) != null ? status : undefined])));
    try {
      await this.api.post('/api/jobs/bulk-status', { ids, status });
      this.toast(`${ids.length} offre${plural(ids.length)} déplacée${plural(ids.length)} dans « ${this.statusLabel(status)} »`);
    } catch {
      this.setLocal(prev);
      this.toast('Échec du déplacement');
    }
  }

  async deleteJob(id: number) {
    try {
      await this.api.delete(`/api/jobs/${id}`);
    } catch {
      this.toast('Échec de la suppression');
      return;
    }
    this.jobs.update((list) => list.filter((j) => j.id !== id));
    if (this.currentId() === id) this.closeDetail();
    this.toast('Offre supprimée');
  }

  /** Renvoie false si la suppression a échoué. */
  async bulkDelete(ids: number[]): Promise<boolean> {
    try {
      await this.api.post('/api/jobs/bulk-delete', { ids });
    } catch {
      this.toast('Échec de la suppression');
      return false;
    }
    const gone = new Set(ids);
    this.jobs.update((list) => list.filter((j) => !gone.has(j.id)));
    const current = this.currentId();
    if (current != null && gone.has(current)) this.closeDetail();
    this.toast(`${ids.length} offre${plural(ids.length)} supprimée${plural(ids.length)}`);
    return true;
  }

  /* ---------- recherche d'offres en arrière-plan ---------- */
  setDragging(dragging: boolean) {
    this.dragging = dragging;
    if (!dragging && this.refreshPending) {
      this.refreshPending = false;
      this.refreshJobs();
    }
  }

  private refreshJobs() {
    // ne pas casser un glisser-déposer en cours
    if (this.dragging) {
      this.refreshPending = true;
      return;
    }
    this.load({ markFresh: true }).catch(() => {});
  }

  private showScrape(s: ScrapeState) {
    this.scrape.set(s);
    this.scrapeShown.set(s.started);
  }

  /** À l'ouverture de la page : reprend le suivi si une recherche tourne déjà. */
  async resumeScrape() {
    const s = await this.api.get<ScrapeState>('/api/scrape');
    if (s.running && !this.destroyed) {
      this.lastNew = s.new_jobs;
      await this.pollScrape();
    }
  }

  /** Après le lancement d'une recherche. */
  scrapeStarted() {
    this.lastNew = 0;
    return this.pollScrape();
  }

  async pollScrape() {
    let s: ScrapeState;
    try {
      s = await this.api.get<ScrapeState>('/api/scrape');
    } catch {
      return;
    }
    if (this.destroyed) return;
    this.showScrape(s);
    if (s.new_jobs !== this.lastNew) {
      this.lastNew = s.new_jobs;
      this.refreshJobs();
    }
    if (s.running && !this.poll) this.poll = setInterval(() => this.pollScrape(), 2000);
    if (!s.running && this.poll) {
      clearInterval(this.poll);
      this.poll = null;
      this.refreshJobs();
      this.toast(`${s.stopped ? 'Recherche arrêtée' : 'Recherche terminée'} · ${s.new_jobs} nouvelle(s) offre(s)`);
    }
  }

  async stopScrape() {
    try {
      await this.api.post('/api/scrape/stop');
    } catch (e: any) {
      this.toast(e?.message || "Impossible d'arrêter la recherche");
    }
    await this.pollScrape();
  }

  dismissScrape() {
    this.scrapeShown.set(false);
    this.logOpen.set(false);
  }
}
