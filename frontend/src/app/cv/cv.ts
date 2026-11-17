import {
  Component, ElementRef, Injector, OnDestroy, OnInit, ViewEncapsulation,
  afterNextRender, computed, effect, inject, signal, untracked, viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { UserMenu } from '../core/user-menu';
import { ChipEditor } from './chip-editor';
import { AtsDialog, CheckDialog, TranslateDialog } from './cv-dialogs';
import {
  CvData, CvDoc, CvSummary, Job, LIST_KEYS, SECTION_KEYS, SECTION_LABELS, SWATCHES,
  atsChecks, cvText, hasSkill, jobLabel, normalizeCv, prefs,
} from './cv-model';
import { CvCard, EntryControls } from './cv-parts';
import { InfoSection, LayoutSection, ListSection, SkillsSection } from './cv-sections';
import { CvStore, errorText } from './cv-store';

interface PageInfo { text: string; warn: boolean; title: string; }

const isFileDrag = (e: DragEvent) => [...(e.dataTransfer?.types || [])].includes('Files');

/** Éditeur de CV : liste des CV, formulaire par section, aperçu PDF en direct, import / export, correcteur, traduction. */
@Component({
  selector: 'app-cv',
  imports: [
    FormsModule, RouterLink, UserMenu, ChipEditor, CvCard, EntryControls,
    InfoSection, ListSection, SkillsSection, LayoutSection, AtsDialog, CheckDialog, TranslateDialog,
  ],
  providers: [CvStore],
  templateUrl: './cv.html',
  styleUrl: './cv.css',
  // Les sous-composants (cartes, champs, dialogues) partagent les mêmes classes : une seule feuille,
  // non encapsulée, dont tous les sélecteurs sont placés sous `app-cv`.
  encapsulation: ViewEncapsulation.None,
  host: {
    '(window:beforeunload)': 'onBeforeUnload($event)',
    '(document:dragenter)': 'onDragEnter($event)',
    '(document:dragleave)': 'onDragLeave($event)',
    '(document:dragover)': 'onDragOver($event)',
    '(document:drop)': 'onDrop($event)',
  },
})
export class Cv implements OnInit, OnDestroy {
  private api = inject(Api);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private sanitizer = inject(DomSanitizer);
  private injector = inject(Injector);
  protected store = inject(CvStore);

  protected readonly labels = SECTION_LABELS;
  protected readonly swatches = SWATCHES;
  protected readonly jobLabel = jobLabel;
  protected readonly styles = [
    { id: 'moderne', label: 'Moderne', title: 'Bandeau de couleur, titres avec barre, dates en pastille' },
    { id: 'tech', label: 'Tech', title: 'Inspiré des CV tech une page : sobre, centré, très lisible' },
    { id: 'classique', label: 'Classique', title: 'Sobre, sans décor' },
  ];
  protected readonly levels = [
    'Langue maternelle', 'Bilingue', 'Courant (C1)', 'Professionnel (B2)', 'Intermédiaire (B1)', 'Notions (A2)', 'C2', 'C1', 'B2', 'B1',
  ];

  private matchBox = viewChild.required<ElementRef<HTMLElement>>('matchBox');
  private atsDialog = viewChild.required<AtsDialog>('atsDialog');

  protected cvs = signal<CvSummary[]>([]);
  protected selectedId = signal<number | null>(null);
  protected jobs = signal<Job[]>([]);
  protected matchedJob = signal<Job | null>(null);
  protected jobText = signal('');
  protected importNote = signal<string | null>(null);
  protected dropHint = signal(false);

  protected previewSrc = signal<SafeResourceUrl | null>(null);
  protected previewState = signal('');
  protected pageInfo = signal<PageInfo | null>(null);

  protected style = computed(() => this.read((d) => d.style || 'moderne', 'moderne'));
  protected accent = computed(() => this.read((d) => (d.accent || '#1f4e79').toLowerCase(), '#1f4e79'));
  protected maxPages = computed(() => this.read((d) => (d.max_pages === 2 ? 2 : 1), 1));

  /** Comparaison du CV avec les compétences de l'offre choisie. */
  protected match = computed(() => {
    const job = this.matchedJob(), cv = this.store.cv();
    if (!job || !cv) return null;
    this.store.rev();
    const text = cvText(cv.data), skills = job.skills || [];
    const found = skills.filter((s) => hasSkill(text, s)), missing = skills.filter((s) => !hasSkill(text, s));
    return {
      job, found, missing, total: skills.length,
      pct: skills.length ? Math.round((found.length / skills.length) * 100) : null,
      canCopy: cv.job_id !== job.id,
    };
  });

  private previewTimer: ReturnType<typeof setTimeout> | null = null;
  private previewSeq = 0;
  private previewUrl: string | null = null;
  private revokeTimers = new Map<ReturnType<typeof setTimeout>, string>();
  private dragDepth = 0;
  private destroyed = false;

  constructor() {
    // chaque modification du CV relance l'aperçu (après une courte pause)
    effect(() => {
      if (this.store.rev()) untracked(() => this.schedulePreview());
    });
  }

  /* ---------- démarrage ---------- */
  async ngOnInit() {
    const params = this.route.snapshot.queryParamMap;
    try {
      const jobs = await this.api.get<Job[]>('/api/jobs').catch(() => [] as Job[]);
      const rank: Record<string, number> = { entretien: 0, postule: 1, interesse: 2, en_attente: 3, a_trier: 4 };
      jobs.sort((a, b) => (rank[a.status ?? ''] ?? 9) - (rank[b.status ?? ''] ?? 9));
      this.jobs.set(jobs);
      let list = await this.loadList();
      if (!list.length) {
        await this.api.post('/api/cvs', { name: 'Mon CV' });
        list = await this.loadList();
      }
      const wanted = +(params.get('id') ?? 0) || +(prefs.get('cv-current') ?? 0);
      await this.openCv(list.some((c) => c.id === wanted) ? wanted : list[0].id);
      // ?job=… : arrivée depuis « Adapter mon CV » dans le tableau
      const job = jobs.find((j) => j.id === +(params.get('job') ?? 0));
      if (job && !this.destroyed) {
        this.selectJob(job);
        afterNextRender(() => this.matchBox().nativeElement.scrollIntoView({ block: 'nearest' }), { injector: this.injector });
      }
    } catch (e) {
      this.fail(e);
    }
  }

  ngOnDestroy() {
    // (la sauvegarde en attente est envoyée par CvStore.ngOnDestroy)
    this.destroyed = true;
    this.previewSeq++;
    if (this.previewTimer) clearTimeout(this.previewTimer);
    for (const [timer, url] of this.revokeTimers) {
      clearTimeout(timer);
      URL.revokeObjectURL(url);
    }
    if (this.previewUrl) URL.revokeObjectURL(this.previewUrl);
  }

  protected onBeforeUnload(e: BeforeUnloadEvent) {
    if (this.store.pendingSave) {
      void this.store.save();
      e.preventDefault();
    }
  }

  private read<T>(get: (d: CvData) => T, fallback: T): T {
    const cv = this.store.cv();
    this.store.rev();
    return cv ? get(cv.data) : fallback;
  }

  private fail(e: unknown) {
    this.store.toast('Erreur : ' + errorText(e));
  }

  /* ---------- aperçu ---------- */
  private schedulePreview() {
    if (this.previewTimer) clearTimeout(this.previewTimer);
    this.previewTimer = setTimeout(() => this.refreshPreview(), 600);
  }

  private async pdf(data: CvData, download: boolean) {
    const r = await this.api.postForBlob('/api/cv/pdf' + (download ? '?download=1' : ''), data);
    return {
      blob: r.body!,
      filename: r.headers.get('X-Filename') || 'CV.pdf',
      pages: +(r.headers.get('X-Pages') ?? 0) || 1,
      compact: +(r.headers.get('X-Compact') ?? 0) || 0,
      overflow: r.headers.get('X-Overflow') === '1',
    };
  }

  private async refreshPreview() {
    if (this.previewTimer) clearTimeout(this.previewTimer);
    this.previewTimer = null;
    const cv = this.store.cv();
    if (!cv || this.destroyed) return;
    const seq = ++this.previewSeq;
    this.previewState.set('mise à jour…');
    try {
      const { blob, pages, compact, overflow } = await this.pdf(cv.data, false);
      if (seq !== this.previewSeq) return;
      const old = this.previewUrl;
      const url = (this.previewUrl = URL.createObjectURL(blob));
      // seule URL de confiance de la page : un blob créé ici à partir de la réponse du serveur
      this.previewSrc.set(this.sanitizer.bypassSecurityTrustResourceUrl(url + '#toolbar=0&navpanes=0&view=FitH'));
      if (old) {
        const timer = setTimeout(() => {
          URL.revokeObjectURL(old);
          this.revokeTimers.delete(timer);
        }, 2000);
        this.revokeTimers.set(timer, old);
      }
      this.previewState.set('');
      const max = cv.data.max_pages === 2 ? 2 : 1;
      this.pageInfo.set({
        text: (pages > 1 ? `${pages} pages` : '1 page') + (overflow ? ' : trop long' : compact ? ' · resserré' : ''),
        warn: overflow || compact >= 0.75,
        title: overflow
          ? `Même resserré au maximum, le contenu ne tient pas sur ${max} page${max > 1 ? 's' : ''} : raccourcis les puces, garde les expériences les plus pertinentes` + (max === 1 ? ', ou autorise une deuxième page.' : '.')
          : compact >= 0.75 ? "Le CV tient, mais le texte a dû être nettement réduit : raccourcis un peu le contenu pour qu'il reste confortable à lire."
          : compact ? 'Marges et espacements resserrés automatiquement pour tenir sur ' + (max === 1 ? 'une page.' : 'deux pages.')
          : pages > 1 ? 'Deux pages autorisées. Pour un stage ou un premier poste, une seule page reste conseillée.'
          : 'Une page : parfait pour un stage ou un premier poste',
      });
    } catch {
      if (seq === this.previewSeq) this.previewState.set("erreur d'aperçu");
    }
  }

  /* ---------- style et couleur (barre d'aperçu) ---------- */
  protected setStyle(style: string) {
    this.store.data.style = style;
    this.store.changed();
  }

  protected setAccent(color: string) {
    this.store.data.accent = color;
    this.store.changed();
  }

  protected setMaxPages(two: boolean) {
    this.store.data.max_pages = two ? 2 : 1;
    this.store.changed();
  }

  protected addLanguage() {
    this.store.data.languages.push({ name: '', level: '' });
    this.store.changed();
  }

  /* ---------- comparaison avec une offre ---------- */
  protected selectJob(job: Job | null) {
    this.matchedJob.set(job);
    this.jobText.set(job ? jobLabel(job) : '');
  }

  protected jobInputChanged(input: HTMLInputElement) {
    const m = input.value.match(/\(#(\d+)\)$/);
    this.selectJob((m && this.jobs().find((j) => j.id === +m[1])) || null);
    input.value = this.jobText(); // texte libre sans offre correspondante : on vide le champ
  }

  protected addSkill(skill: string) {
    const data = this.store.data;
    let g = data.hard_skills.find((x) => /autre|other|à classer/i.test(x.category || ''));
    if (!g) {
      g = { category: data.lang === 'en' ? 'Other' : 'Autres', items: [] };
      data.hard_skills.push(g);
    }
    g.items = [...(g.items || []), skill];
    this.store.changed();
    this.store.toast(`« ${skill} » ajouté dans « ${g.category} »`);
  }

  /* ---------- gestion des CV ---------- */
  private async loadList(selectId?: number): Promise<CvSummary[]> {
    const list = await this.api.get<CvSummary[]>('/api/cvs');
    this.cvs.set(list);
    this.selectedId.set(selectId ?? list[0]?.id ?? null);
    return list;
  }

  private async openCv(id: number) {
    await this.store.flushSave();
    const cv = await this.api.get<CvDoc>(`/api/cvs/${id}`);
    if (this.destroyed) return;
    normalizeCv(cv.data);
    this.store.cv.set(cv);
    this.selectedId.set(id);
    prefs.set('cv-current', String(id));
    void this.router.navigate([], { relativeTo: this.route, queryParams: { id }, replaceUrl: true });
    this.store.saveState.set({ text: '', ok: false });
    const job = cv.job_id ? this.jobs().find((j) => j.id === cv.job_id) : null;
    if (job) this.selectJob(job);
    void this.refreshPreview();
  }

  protected async selectCv(id: number) {
    this.importNote.set(null);
    this.selectedId.set(id);
    try {
      await this.openCv(id);
    } catch (e) {
      this.fail(e);
    }
  }

  protected async duplicate(name: string, jobId?: number) {
    try {
      await this.store.flushSave();
      const cv = this.store.cv()!;
      const c = await this.api.post<CvDoc>('/api/cvs', { from_id: cv.id, name, job_id: jobId ?? cv.job_id });
      await this.loadList(c.id);
      await this.openCv(c.id);
      this.store.toast("Copie créée : tu peux l'adapter sans toucher à l'original");
    } catch (e) {
      this.fail(e);
    }
  }

  protected async newCv() {
    const name = prompt('Nom du nouveau CV :', 'Nouveau CV');
    if (name == null) return;
    try {
      await this.store.flushSave();
      const c = await this.api.post<CvDoc>('/api/cvs', { name });
      await this.loadList(c.id);
      await this.openCv(c.id);
    } catch (e) {
      this.fail(e);
    }
  }

  protected duplicateCv() {
    const name = prompt('Nom de la copie :', this.store.cv()!.name + ' (copie)');
    if (name != null) void this.duplicate(name);
  }

  protected async renameCv() {
    const cv = this.store.cv()!;
    const name = prompt('Nouveau nom :', cv.name);
    if (!name?.trim()) return;
    try {
      await this.api.put(`/api/cvs/${cv.id}`, { name });
      cv.name = name.trim();
      await this.loadList(cv.id);
    } catch (e) {
      this.fail(e);
    }
  }

  protected async deleteCv() {
    const cv = this.store.cv()!;
    if (!confirm(`Supprimer définitivement « ${cv.name} » ?`)) return;
    try {
      this.store.cancelSave();
      await this.api.delete(`/api/cvs/${cv.id}`);
      this.store.cancelSave(); // une modification faite pendant la suppression n'a plus de CV où aller
      const list = await this.loadList();
      if (list.length) await this.openCv(list[0].id);
      else {
        const c = await this.api.post<CvDoc>('/api/cvs', { name: 'Mon CV' });
        await this.loadList(c.id);
        await this.openCv(c.id);
      }
      this.store.toast('CV supprimé');
    } catch (e) {
      this.fail(e);
    }
  }

  protected async translated({ cv, chars }: { cv: CvDoc; chars: number }) {
    try {
      await this.loadList(cv.id);
      await this.openCv(cv.id);
      this.store.toast(`Copie en anglais créée (${chars} caractères traduits) : relis-la, puis « Corriger les fautes »`);
    } catch (e) {
      this.fail(e);
    }
  }

  /* ---------- import d'un CV existant en PDF ---------- */
  private async importPdf(file: File | undefined) {
    if (!file) return;
    if (!/\.pdf$/i.test(file.name) && file.type !== 'application/pdf') return this.store.toast('Choisis un fichier PDF');
    await this.store.flushSave();
    this.store.toast('Lecture du PDF…');
    const form = new FormData();
    form.append('file', file);
    let res: { cv: CvDoc; warnings: string[] };
    try {
      res = await this.api.post('/api/cvs/import', form);
    } catch (e) {
      return this.store.toast('Import impossible : ' + errorText(e));
    }
    try {
      await this.loadList(res.cv.id);
      await this.openCv(res.cv.id);
    } catch (e) {
      return this.fail(e);
    }
    const d = this.store.cv()?.data;
    if (!d || this.destroyed) return;
    // déplie tout pour faciliter la relecture
    for (const k of LIST_KEYS) d[k].forEach((it) => this.store.openItems.add(it));
    for (const k of ['info', ...SECTION_KEYS]) this.store.setOpen(k, true);
    const n = d.experience.length + d.projects.length + d.education.length;
    this.importNote.set(res.warnings?.length ? res.warnings.join(' ')
      : `Importé : ${d.experience.length} expérience(s), ${d.projects.length} projet(s), ${d.education.length} formation(s)`
        + `${d.photo ? ', photo' : ''}. La lecture est automatique : relis chaque section (dates, entreprise / poste, lieu) avant d'exporter.`
        + (n ? '' : ' Aucune expérience reconnue : vérifie que les titres de sections du PDF sont classiques.'));
  }

  protected pdfPicked(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = '';
    void this.importPdf(file);
  }

  protected onDragEnter(e: DragEvent) {
    if (!isFileDrag(e)) return;
    this.dragDepth++;
    this.dropHint.set(true);
  }

  protected onDragLeave(e: DragEvent) {
    if (isFileDrag(e) && --this.dragDepth <= 0) {
      this.dragDepth = 0;
      this.dropHint.set(false);
    }
  }

  protected onDragOver(e: DragEvent) {
    if (isFileDrag(e)) e.preventDefault();
  }

  protected onDrop(e: DragEvent) {
    if (!isFileDrag(e)) return;
    e.preventDefault();
    this.dragDepth = 0;
    this.dropHint.set(false);
    const file = [...e.dataTransfer!.files].find((f) => /pdf/i.test(f.type) || /\.pdf$/i.test(f.name));
    if (file) void this.importPdf(file);
    else this.store.toast('Dépose un fichier PDF');
  }

  /* ---------- export et vérification ---------- */
  protected async exportPdf() {
    await this.store.flushSave();
    try {
      const { blob, filename } = await this.pdf(this.store.data, true);
      const a = document.createElement('a');
      const url = (a.href = URL.createObjectURL(blob));
      a.download = filename;
      document.body.append(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
    } catch (e) {
      this.store.toast('Export impossible : ' + errorText(e));
    }
  }

  protected async atsCheck() {
    await this.store.flushSave();
    try {
      const data = this.store.data;
      const { text } = await this.api.post<{ text: string }>('/api/cv/text', data);
      this.atsDialog().show(atsChecks(data, text, this.matchedJob()), text);
    } catch (e) {
      this.fail(e);
    }
  }
}
