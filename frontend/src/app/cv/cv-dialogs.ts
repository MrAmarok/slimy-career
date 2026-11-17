import { Component, ElementRef, computed, inject, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api } from '../core/api';
import { AtsCheck, CvData, CvDoc, Issue, prefs } from './cv-model';
import { CvStore, errorText } from './cv-store';

/** « Vérifier la lecture ATS » : liste de contrôles + texte extrait du PDF. */
@Component({
  selector: 'app-ats-dialog',
  template: `
    <dialog #dialog>
      <div class="modal">
        <div class="row"><h2 class="grow">Lecture par un logiciel RH</h2><button type="button" class="btn-ghost" (click)="dialog.close()">×</button></div>
        <div class="muted small">Texte extrait du PDF exporté, dans l'ordre où un ATS le lit. Si tout y est, lisible et dans le bon ordre, ton CV passe.</div>
        <div class="checklist">
          @for (c of checks(); track $index) {
            <div [class]="c.cls">{{ c.msg }}</div>
          }
        </div>
        <pre>{{ text() }}</pre>
      </div>
    </dialog>
  `,
})
export class AtsDialog {
  private dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected checks = signal<AtsCheck[]>([]);
  protected text = signal('');

  show(checks: AtsCheck[], text: string) {
    this.checks.set(checks);
    this.text.set(text);
    this.dialog().nativeElement.showModal();
  }
}

/* ---------- correcteur (LanguageTool) ---------- */

const dictWords = (): string[] => {
  try { return JSON.parse(prefs.get('cv-dict') || '[]'); } catch { return []; }
};

/** Champ du CV désigné par un chemin : [objet ou tableau qui le contient, clé]. */
function fieldOf(data: CvData, path: (string | number)[]): [any, string | number] {
  let holder: any = data;
  for (const k of path.slice(0, -1)) holder = holder?.[k];
  return [holder, path[path.length - 1]];
}

/** Position actuelle de la faute dans son champ (-1 si le texte a changé depuis la vérification). */
function locate(data: CvData, issue: Issue) {
  const [holder, key] = fieldOf(data, issue.path);
  const text = String(holder?.[key] ?? '');
  const at = text.slice(issue.offset, issue.offset + issue.length) === issue.bad ? issue.offset : text.indexOf(issue.bad);
  return { holder, key, text, at };
}

const flat = (s: string) => s.replace(/\s+/g, ' ');

@Component({
  selector: 'app-check-dialog',
  template: `
    <dialog #dialog>
      <div class="modal">
        <div class="row">
          <h2 class="grow">Correcteur</h2>
          <button type="button" (click)="run()">Revérifier</button>
          <button type="button" class="btn-ghost" (click)="dialog.close()">×</button>
        </div>
        <div class="muted small">{{ intro() }}</div>
        <div class="issues">
          @switch (state()) {
            @case ('loading') {
              <div class="muted">Vérification en cours…</div>
            }
            @case ('error') {
              <div class="checklist"><div class="warn">{{ error() }}</div></div>
            }
            @default {
              @for (row of rows(); track row.issue) {
                @let issue = row.issue;
                <div class="issue">
                  <div class="where"><span [class]="'kind ' + issue.kind">{{ issue.kind }}</span><span>{{ issue.label }}</span></div>
                  <div class="ctx">{{ row.before }}<mark>{{ issue.bad }}</mark>{{ row.after }}</div>
                  <div>{{ issue.message }}</div>
                  <div class="fixes">
                    @for (value of issue.replacements; track $index) {
                      <button type="button" class="fix" title="Remplacer par cette proposition" (click)="applyFix(issue, value)">{{ value === '' ? '(supprimer)' : value }}</button>
                    }
                    <button type="button" class="btn-ghost" (click)="skip(issue)">Ignorer</button>
                    @if (issue.kind === 'orthographe') {
                      <button type="button" class="btn-ghost" title="Ne plus signaler ce mot (nom propre, technologie, mot étranger…)" (click)="learn(issue)">Ce mot est correct</button>
                    }
                  </div>
                </div>
              } @empty {
                <div class="checklist"><div class="ok">Rien à corriger : orthographe et grammaire sont bonnes.</div></div>
              }
            }
          }
        </div>
      </div>
    </dialog>
  `,
})
export class CheckDialog {
  private api = inject(Api);
  private store = inject(CvStore);
  private dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  protected state = signal<'loading' | 'error' | 'done'>('done');
  protected error = signal('');
  private issues = signal<Issue[]>([]);
  private language = signal('fr');
  private seq = 0;

  protected intro = computed(() => {
    if (this.state() !== 'done') return '';
    const n = this.issues().length, lang = this.language();
    const langName = lang === 'fr' ? 'français' : lang === 'en-GB' ? 'anglais britannique' : 'anglais américain';
    return (n ? `${n} point${n > 1 ? 's' : ''} à regarder` : 'Aucune faute détectée')
      + ` — vérification en ${langName} (langue du CV, réglable dans « Mise en page »). Clique sur une proposition verte pour l'appliquer.`;
  });

  /** Chaque faute avec son contexte (60 caractères de part et d'autre), relu dans le CV à chaque modification. */
  protected rows = computed(() => {
    const cv = this.store.cv();
    if (!cv) return [];
    this.store.rev();
    return this.issues().map((issue) => {
      const { text, at } = locate(cv.data, issue);
      if (at < 0) return { issue, before: '', after: '' };
      const end = at + issue.length;
      return {
        issue,
        before: (at > 60 ? '…' : '') + flat(text.slice(Math.max(0, at - 60), at)),
        after: flat(text.slice(end, end + 60)) + (text.length > end + 60 ? '…' : ''),
      };
    });
  });

  open() {
    this.dialog().nativeElement.showModal();
    void this.run();
  }

  protected async run() {
    const seq = ++this.seq;
    this.state.set('loading');
    try {
      await this.store.flushSave();
      const r = await this.api.post<{ issues: Issue[]; language: string }>('/api/cv/check', {
        data: { ...this.store.data, photo: null },
        ignore: dictWords(),
      });
      if (seq !== this.seq) return;
      this.issues.set(r.issues);
      if (r.language) this.language.set(r.language);
      this.state.set('done');
    } catch (e) {
      if (seq !== this.seq) return;
      this.error.set(errorText(e));
      this.state.set('error');
    }
  }

  protected skip(issue: Issue) {
    this.issues.update((list) => list.filter((x) => x !== issue));
  }

  protected learn(issue: Issue) {
    const word = issue.bad.toLowerCase();
    prefs.set('cv-dict', JSON.stringify([...new Set([...dictWords(), word])]));
    this.issues.update((list) => list.filter((x) => !(x.kind === 'orthographe' && x.bad.toLowerCase() === word)));
  }

  protected applyFix(issue: Issue, value: string) {
    const { holder, key, text, at } = locate(this.store.data, issue);
    if (!holder || at < 0) {
      this.skip(issue);
      this.store.toast('Ce passage a changé depuis la vérification : clique sur « Revérifier »');
      return;
    }
    holder[key] = text.slice(0, at) + value + text.slice(at + issue.length);
    // les fautes suivantes du même champ se décalent
    const delta = value.length - issue.length, same = JSON.stringify(issue.path);
    this.issues.update((list) =>
      list.filter((x) => x !== issue)
        .map((x) => (JSON.stringify(x.path) === same && x.offset > at ? { ...x, offset: x.offset + delta } : x)));
    this.store.changed();
  }
}

/* ---------- traduction en anglais (DeepL) ---------- */

@Component({
  selector: 'app-translate-dialog',
  imports: [FormsModule],
  template: `
    <dialog #dialog>
      <div class="modal">
        <div class="row"><h2 class="grow">Traduire le CV en anglais</h2><button type="button" class="btn-ghost" (click)="dialog.close()">×</button></div>
        <div class="small">Crée une <b>copie</b> de ce CV traduite en anglais par DeepL : titre, profil, puces des expériences, projets et formations,
          qualités, centres d'intérêt, langues. Les titres de sections et les dates passent en anglais automatiquement.
          Ton CV en français n'est pas modifié.</div>
        <div class="muted small">Ne sont pas touchés : nom, coordonnées, liens, entreprises, écoles, lieux, noms de projets et technologies.
          Relis la copie (une machine ne connaît pas ton contexte), puis lance « Corriger les fautes » dessus : la vérification se fera en anglais.</div>
        <label class="field">Variante
          <select [ngModel]="variant()" (ngModelChange)="variant.set($event)">
            <option value="en-GB">Anglais britannique (Europe, Royaume-Uni)</option>
            <option value="en-US">Anglais américain</option>
          </select>
        </label>
        @if (needKey()) {
          <div class="key-box">
            <label class="field">Clé d'API DeepL<input type="password" autocomplete="off" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx:fx" [ngModel]="key()" (ngModelChange)="key.set($event)" /></label>
            <div class="hint">Gratuite (500 000 caractères par mois, un CV en fait environ 3 000) : crée un compte « DeepL API Free » sur
              <a href="https://www.deepl.com/pro-api" target="_blank" rel="noopener">deepl.com/pro-api</a> et colle la clé ici.
              Elle est enregistrée dans les paramètres de ton compte.</div>
          </div>
        }
        <div class="row">
          <span class="grow small muted">{{ status() }}</span>
          @if (!needKey()) {
            <button type="button" class="btn-ghost" (click)="needKey.set(true)">Changer la clé</button>
          }
          <button type="button" class="btn-primary" [disabled]="busy()" (click)="go()">Traduire</button>
        </div>
      </div>
    </dialog>
  `,
})
export class TranslateDialog {
  private api = inject(Api);
  private store = inject(CvStore);
  private dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  /** La copie traduite a été créée : à la page de l'ouvrir. */
  readonly created = output<{ cv: CvDoc; chars: number }>();

  protected variant = signal('en-GB');
  protected key = signal('');
  protected needKey = signal(false);
  protected status = signal('');
  protected busy = signal(false);

  async open() {
    if (this.store.data.lang === 'en') {
      this.store.toast('Ce CV est déjà en anglais : ouvre la version française pour la traduire');
      return;
    }
    this.status.set('');
    this.key.set('');
    this.busy.set(false);
    const { deepl } = await this.api.get<{ deepl: boolean }>('/api/cv/lang').catch(() => ({ deepl: false }));
    this.needKey.set(!deepl);
    this.dialog().nativeElement.showModal();
  }

  protected async go() {
    const key = this.key().trim(), needKey = this.needKey();
    if (needKey && !key) {
      this.status.set("Colle d'abord ta clé DeepL.");
      return;
    }
    this.busy.set(true);
    this.status.set('Traduction en cours…');
    try {
      if (needKey) await this.api.put('/api/config', { deepl_key: key });
      await this.store.flushSave();
      const source = this.store.cv()!;
      const { data, chars } = await this.api.post<{ data: CvData; chars: number }>('/api/cv/translate', { data: source.data, variant: this.variant() });
      const cv = await this.api.post<CvDoc>('/api/cvs', { name: `${source.name} (EN)`, data, job_id: source.job_id });
      this.dialog().nativeElement.close();
      this.created.emit({ cv, chars });
    } catch (e) {
      this.status.set(errorText(e));
    }
    this.busy.set(false);
  }
}
