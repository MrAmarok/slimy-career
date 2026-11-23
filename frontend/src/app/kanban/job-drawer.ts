import { Component, DestroyRef, ElementRef, computed, effect, inject, linkedSignal, signal, untracked, viewChild, viewChildren } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ChipEditor } from './chip-editor';
import { KanbanStore } from './kanban-store';
import { Job, sourceOf, statusColor } from './models';
import { DOT_CSS, DRAWER_CSS } from './shared-styles';

/** Panneau de détail d'une offre : statut, notes (enregistrées pendant la frappe), tags, compétences, description. */
@Component({
  selector: 'app-job-drawer',
  imports: [FormsModule, RouterLink, ChipEditor],
  template: `
    <div class="drawer" [class.open]="!!store.current()" [attr.aria-hidden]="!store.current()">
      @if (job(); as j) {
        <div class="drawer-head">
          <div style="flex:1; min-width:0">
            <h2>{{ j.title }}</h2>
            <div class="muted" style="margin-top:3px">{{ subtitle() }}</div>
          </div>
          <button class="btn-ghost" title="Fermer (Échap)" style="font-size:18px; padding:2px 9px" (click)="store.closeDetail()">×</button>
        </div>
        <div class="drawer-body" #body>
          <div style="display:flex; gap:8px">
            @if (hasLink()) {
              <a class="btn-primary" [href]="j.link" target="_blank" rel="noopener" style="padding:8px 14px; border-radius:8px; color:#fff; font-weight:500">Ouvrir l'offre ↗</a>
            }
            <a routerLink="/cv" [queryParams]="{ job: j.id }" style="padding:8px 14px; border-radius:8px; border:1px solid var(--line-strong); color:var(--text); font-weight:500" title="Comparer ton CV aux compétences de cette offre">Adapter mon CV</a>
            <span class="spacer"></span>
            <button class="btn-danger" (click)="remove(j)">Supprimer</button>
          </div>
          <div>
            <h4>Statut</h4>
            <div class="status-picker">
              @for (s of store.statuses(); track s.key) {
                <button [class.on]="j.status === s.key" (click)="store.setStatus(j.id, s.key)">
                  <span class="dot" [style.background]="color(s.key)"></span>{{ s.label }}
                </button>
              }
            </div>
          </div>
          <dl class="info-grid">
            @for (row of info(); track row[0]) {
              <dt>{{ row[0] }}</dt>
              <dd>{{ row[1] }}</dd>
            }
          </dl>
          <div>
            <h4>Notes @if (notesSaved()) { <span class="saved-hint">enregistré ✓</span> }</h4>
            <textarea placeholder="Date de candidature, contact, relance…" [ngModel]="notes()" (ngModelChange)="onNotesInput($event)" (blur)="flushNotes()"></textarea>
          </div>
          <div>
            <h4>Tags</h4>
            <app-chip-editor placeholder="Ajouter un tag…" list="dl-tags" [values]="j.tags" (changed)="save(j.id, { tags: $event })" />
          </div>
          <div>
            <h4>Compétences</h4>
            <app-chip-editor placeholder="Ajouter une compétence…" list="dl-skills" [values]="j.skills" (changed)="save(j.id, { skills: $event })" />
          </div>
          <div>
            <h4>Description</h4>
            <div class="desc">{{ j.description || 'Pas de description récupérée.' }}</div>
          </div>
        </div>
      }
    </div>
  `,
  styles: [
    DRAWER_CSS,
    DOT_CSS,
    `
    .status-picker { display: flex; flex-wrap: wrap; gap: 6px; }
    .status-picker button { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; padding: 5px 10px; border-color: var(--line); }
    .status-picker button.on { border-color: var(--text); background: var(--tip); color: #fff; }
    .info-grid { display: grid; grid-template-columns: auto 1fr; gap: 4px 14px; font-size: 13px; }
    .info-grid dt { color: var(--muted); }
    .info-grid dd { margin: 0; }
    .desc { white-space: pre-wrap; font-size: 13px; line-height: 1.6; color: var(--text-2); }
    .saved-hint { font-size: 12px; color: var(--ok); margin-left: 8px; font-weight: 400; text-transform: none; letter-spacing: 0; }
    `,
  ],
})
export class JobDrawer {
  protected store = inject(KanbanStore);
  protected color = statusColor;

  /** Offre affichée : la dernière ouverte reste visible pendant que le panneau se referme. */
  protected readonly job = linkedSignal<Job | null, Job | null>({
    source: this.store.current,
    computation: (current, previous) => current ?? previous?.value ?? null,
  });
  protected readonly subtitle = computed(() => {
    const j = this.job();
    return j ? [j.company, j.city || j.location].filter(Boolean).join(' · ') : '';
  });
  /** une offre ajoutée à la main peut ne pas avoir de lien */
  protected readonly hasLink = computed(() => /^https?:/i.test(this.job()?.link ?? ''));
  protected readonly info = computed<[string, string][]>(() => {
    const j = this.job();
    if (!j) return [];
    const rows: [string, string | null][] = [
      ['Lieu', [j.location, j.country].filter(Boolean).join(', ')],
      ['Contrat', j.contract],
      ['Publiée', j.date_posted ? new Date(j.date_posted.slice(0, 10)).toLocaleDateString('fr-FR', { dateStyle: 'long' }) : ''],
      ['Site', sourceOf(j)],
      ['Catégories', j.categories.join(', ')],
      ['Trouvée à', j.zones.join(', ')],
    ];
    return rows.filter((r): r is [string, string] => !!r[1]);
  });

  /* notes : enregistrement automatique pendant la frappe */
  protected readonly notes = signal('');
  protected readonly notesSaved = signal(false);
  private notesFor: number | null = null;
  private notesTimer: ReturnType<typeof setTimeout> | null = null;

  private body = viewChild<ElementRef<HTMLElement>>('body');
  private editors = viewChildren(ChipEditor);

  constructor() {
    // à chaque changement d'offre (ou fermeture) : enregistre les notes en attente, puis affiche celles de la nouvelle
    effect(() => {
      const id = this.store.currentId();
      untracked(() => {
        this.flushNotes();
        this.notesFor = id;
        if (id == null) return;
        this.notes.set(this.store.current()?.notes || '');
        this.notesSaved.set(false);
        this.editors().forEach((e) => e.clearInput());
        const body = this.body()?.nativeElement;
        if (body) body.scrollTop = 0;
      });
    });
    inject(DestroyRef).onDestroy(() => this.flushNotes());
  }

  protected onNotesInput(text: string) {
    this.notes.set(text);
    this.notesSaved.set(false);
    if (this.notesTimer) clearTimeout(this.notesTimer);
    this.notesTimer = setTimeout(() => this.saveNotes(), 700);
  }

  protected flushNotes() {
    if (this.notesTimer) this.saveNotes();
  }

  private async saveNotes() {
    if (this.notesTimer) clearTimeout(this.notesTimer);
    this.notesTimer = null;
    const id = this.notesFor;
    const text = this.notes();
    const job = this.store.jobs().find((j) => j.id === id);
    if (id == null || !job || text === (job.notes || '')) return;
    try {
      await this.store.patch(id, { notes: text });
      if (this.notesFor === id) this.notesSaved.set(true);
    } catch {
      this.store.toast('Échec de la mise à jour');
    }
  }

  protected async save(id: number, body: Partial<Job>) {
    try {
      await this.store.patch(id, body);
    } catch {
      this.store.toast('Échec de la mise à jour');
    }
  }

  protected async remove(j: Job) {
    if (!confirm('Supprimer cette offre ?')) return;
    await this.store.deleteJob(j.id);
  }
}
