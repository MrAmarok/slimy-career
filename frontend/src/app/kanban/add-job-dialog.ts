import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { KanbanStore } from './kanban-store';
import { Job } from './models';
import { FORM_GRID_CSS } from './shared-styles';

const emptyForm = () => ({ title: '', company: '', location: '', contract: '', link: '', description: '', notes: '', status: 'interesse' });

/** Fenêtre « Ajouter une offre » : offre trouvée ailleurs, candidature spontanée… */
@Component({
  selector: 'app-add-job-dialog',
  imports: [FormsModule],
  template: `
    <dialog #dialog>
      <!-- ngNativeValidate : garde la validation du navigateur (titre obligatoire) -->
      <form class="modal" ngNativeValidate (ngSubmit)="submit()">
        <div style="display:flex; align-items:center; gap:8px">
          <h2 style="flex:1">Ajouter une offre</h2>
          <button type="button" class="btn-ghost" title="Fermer (Échap)" style="font-size:18px; padding:2px 9px" (click)="close()">×</button>
        </div>
        <div class="muted small" style="margin-top:-8px">Pour une offre trouvée ailleurs, une candidature spontanée ou un contact. Seul le titre est obligatoire ;
          colle la description pour que les compétences et la catégorie soient détectées.</div>
        <div class="form-grid" style="margin-top:0">
          <label>Titre du poste *<input #title name="title" required placeholder="Stage DevOps (6 mois)" [(ngModel)]="form().title" /></label>
          <label>Entreprise<input name="company" placeholder="Nom de l'entreprise" [(ngModel)]="form().company" /></label>
          <label>Lieu<input name="location" list="dl-zones" placeholder="Toulouse" [(ngModel)]="form().location" /></label>
          <label>Contrat<input name="contract" placeholder="Stage, alternance, CDI…" [(ngModel)]="form().contract" /></label>
          <label>Lien de l'offre<input name="link" placeholder="https://… (optionnel)" [(ngModel)]="form().link" /></label>
          <label>Colonne
            <select name="status" [(ngModel)]="form().status">
              @for (s of store.statuses(); track s.key) {
                <option [value]="s.key">{{ s.label }}</option>
              }
            </select>
          </label>
        </div>
        <div><label class="lbl" for="add-desc">Description</label><textarea id="add-desc" name="description" style="min-height:130px" placeholder="Colle ici le texte de l'offre" [(ngModel)]="form().description"></textarea></div>
        <div><label class="lbl" for="add-notes">Notes</label><textarea id="add-notes" name="notes" style="min-height:50px" placeholder="Contact, date de relance, d'où vient l'offre…" [(ngModel)]="form().notes"></textarea></div>
        <div style="display:flex; gap:8px; align-items:center">
          <span class="small" style="flex:1; color:var(--danger, #b91c1c)">{{ error() }}</span>
          <button type="button" class="btn-ghost" (click)="close()">Annuler</button>
          <button type="submit" class="btn-primary" [disabled]="busy()">Ajouter</button>
        </div>
      </form>
    </dialog>
  `,
  styles: [
    FORM_GRID_CSS,
    `
    dialog { border: none; border-radius: 14px; padding: 0; width: min(620px, 94vw); box-shadow: var(--shadow-lg); }
    dialog::backdrop { background: rgba(17,24,39,.35); }
    .modal { padding: 22px; display: flex; flex-direction: column; gap: 14px; }
    .modal h2 { margin: 0; font-size: 18px; }
    .modal label.lbl { font-size: 13px; font-weight: 500; display: block; margin-bottom: 5px; }
    .modal textarea { width: 100%; min-height: 70px; resize: vertical; }
    `,
  ],
})
export class AddJobDialog {
  protected store = inject(KanbanStore);
  private api = inject(Api);

  protected readonly form = signal(emptyForm());
  protected readonly error = signal('');
  protected readonly busy = signal(false);

  private dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private title = viewChild.required<ElementRef<HTMLInputElement>>('title');

  open() {
    this.form.set(emptyForm());
    this.error.set('');
    this.dialog().nativeElement.showModal();
    this.title().nativeElement.focus();
  }

  protected close() {
    this.dialog().nativeElement.close();
  }

  protected async submit() {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      const job = await this.api.post<Job>('/api/jobs', this.form());
      this.close();
      await this.store.load({ markFresh: true });
      this.store.toast('Offre ajoutée');
      if (this.store.jobs().some((j) => j.id === job.id)) this.store.openDetail(job.id);
    } catch (e: any) {
      this.error.set(e?.message || 'Erreur');
    }
    this.busy.set(false);
  }
}
