import { Injectable, OnDestroy, inject, signal } from '@angular/core';
import { Api, ApiError } from '../core/api';
import { CvData, CvDoc, prefs } from './cv-model';

export const errorText = (e: unknown) => (e instanceof ApiError || e instanceof Error ? e.message : String(e));

/**
 * État de l'éditeur, partagé par la page et ses sous-composants : le CV ouvert, la sauvegarde
 * automatique, les cartes dépliées et le toast. Fourni par le composant `Cv` (une instance par page).
 *
 * Les données du CV sont modifiées en place par les champs ; chaque modification passe par
 * `changed()`, qui incrémente `rev` (pour les `computed` qui lisent le CV) et programme la sauvegarde.
 */
@Injectable()
export class CvStore implements OnDestroy {
  private api = inject(Api);

  readonly cv = signal<CvDoc | null>(null);
  /** Compteur de modifications du CV ouvert. */
  readonly rev = signal(0);
  readonly saveState = signal<{ text: string; ok: boolean }>({ text: '', ok: false });
  readonly toastText = signal('');
  readonly toastVisible = signal(false);

  /** Fiches dépliées (gardées dépliées quand on les déplace). */
  readonly openItems = new WeakSet<object>();
  private openCards = signal<Record<string, boolean>>({});
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  private toastTimer: ReturnType<typeof setTimeout> | null = null;

  /** Données du CV ouvert (à n'utiliser que lorsqu'un CV est chargé). */
  get data(): CvData {
    this.rev();
    return this.cv()!.data;
  }

  get lang(): 'fr' | 'en' {
    return this.data.lang === 'en' ? 'en' : 'fr';
  }

  /* ---------- sauvegarde automatique ---------- */
  changed() {
    this.saveState.set({ text: 'modifié…', ok: false });
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.save(), 800);
    this.rev.update((n) => n + 1);
  }

  async save(): Promise<void> {
    this.cancelSave();
    const cv = this.cv();
    if (!cv) return;
    try {
      await this.api.put(`/api/cvs/${cv.id}`, { data: cv.data });
      this.saveState.set({ text: 'enregistré ✓', ok: true });
    } catch (e) {
      this.saveState.set({ text: "erreur d'enregistrement", ok: false });
      this.toast('Erreur : ' + errorText(e));
    }
  }

  get pendingSave(): boolean {
    return this.saveTimer !== null;
  }

  /** Enregistre tout de suite s'il reste une modification en attente. */
  flushSave(): Promise<void> {
    return this.saveTimer ? this.save() : Promise.resolve();
  }

  cancelSave() {
    if (this.saveTimer) clearTimeout(this.saveTimer);
    this.saveTimer = null;
  }

  /* ---------- cartes dépliées (mémorisées) ---------- */
  isOpen(key: string): boolean {
    return this.openCards()[key] ?? prefs.get('cv-open-' + key) !== '0';
  }

  setOpen(key: string, open: boolean) {
    if (this.openCards()[key] !== open) this.openCards.update((m) => ({ ...m, [key]: open }));
    prefs.set('cv-open-' + key, open ? '1' : '0');
  }

  /* ---------- toast ---------- */
  toast(msg: string) {
    this.toastText.set(msg);
    this.toastVisible.set(true);
    if (this.toastTimer) clearTimeout(this.toastTimer);
    this.toastTimer = setTimeout(() => this.toastVisible.set(false), 2500);
  }

  ngOnDestroy() {
    // on quitte la page : la dernière modification part quand même au serveur
    if (this.saveTimer) void this.save();
    if (this.toastTimer) clearTimeout(this.toastTimer);
  }
}
