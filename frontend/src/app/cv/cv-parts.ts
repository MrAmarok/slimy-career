import { Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChipEditor } from './chip-editor';
import { Entry, Field } from './cv-model';
import { CvStore } from './cv-store';

/** Carte repliable d'une section ; l'état ouvert / fermé est mémorisé sous `cv-open-<key>`. */
@Component({
  selector: 'app-cv-card',
  template: `
    <details class="card" [open]="store.isOpen(key())" (toggle)="store.setOpen(key(), $any($event.target).open)">
      <summary>
        {{ title() }}
        @if (count() !== null) {
          <span class="n">{{ count() ? '(' + count() + ')' : '' }}</span>
        }
      </summary>
      <div class="card-body"><ng-content /></div>
    </details>
  `,
})
export class CvCard {
  protected store = inject(CvStore);
  readonly key = input.required<string>();
  readonly title = input.required<string>();
  readonly count = input<number | null>(null);
}

/** Boutons monter / descendre / supprimer d'un élément de liste. */
@Component({
  selector: 'app-entry-controls',
  host: { class: 'row' },
  template: `
    <button type="button" class="btn-ghost icon" title="Monter" [disabled]="index() === 0" (click)="move(-1, $event)">↑</button>
    <button type="button" class="btn-ghost icon" title="Descendre" [disabled]="last()" (click)="move(1, $event)">↓</button>
    @if (deletable()) {
      <button type="button" class="btn-danger icon" title="Supprimer" (click)="remove($event)">×</button>
    }
  `,
})
export class EntryControls {
  private store = inject(CvStore);
  readonly list = input.required<unknown[]>();
  readonly index = input.required<number>();
  readonly deletable = input(true);
  // la liste est modifiée en place : `rev` signale qu'il faut la relire
  protected last = computed(() => (this.store.rev(), this.index() === this.list().length - 1));

  // preventDefault : ces boutons sont souvent dans un <summary>, qu'ils ne doivent pas replier
  protected move(delta: number, e: Event) {
    e.preventDefault();
    const list = this.list(), i = this.index(), j = i + delta;
    if (j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    this.store.changed();
  }

  protected remove(e: Event) {
    e.preventDefault();
    if (!confirm('Supprimer cet élément ?')) return;
    this.list().splice(this.index(), 1);
    this.store.changed();
  }
}

/** Un champ du CV (texte, mois, e-mail, zone de texte, case à cocher ou mots-clés) lié à `obj[f.key]`. */
@Component({
  selector: 'label[cvField]',
  imports: [FormsModule, ChipEditor],
  host: {
    '[class.field]': "f().type !== 'check'",
    '[class.full]': "f().type !== 'check' && !!f().full",
    '[class.check]': "f().type === 'check'",
  },
  template: `
    @let field = f();
    @switch (field.type) {
      @case ('check') {
        <input type="checkbox" [ngModel]="!!value()" (ngModelChange)="set($event)" />{{ field.label }}
      }
      @case ('chips') {
        {{ field.label }}
        <app-chip-editor [values]="chips()" [placeholder]="field.ph || 'Entrée pour ajouter'" (valuesChange)="set($event)" />
      }
      @case ('textarea') {
        {{ field.label }}
        <textarea
          spellcheck="true" [attr.lang]="store.lang" [attr.rows]="field.rows ?? null" [placeholder]="field.ph || ''"
          [ngModel]="value() || ''" (ngModelChange)="set($event)"></textarea>
      }
      @default {
        {{ field.label }}
        <input
          [type]="field.type || 'text'" [placeholder]="field.ph || (field.type === 'month' ? 'AAAA-MM' : '')"
          [attr.spellcheck]="field.type ? null : 'true'" [attr.lang]="field.type ? null : store.lang"
          [disabled]="endLocked()"
          [ngModel]="value() || ''" (ngModelChange)="set($event)" />
      }
    }
  `,
})
export class CvField {
  protected store = inject(CvStore);
  readonly obj = input.required<Entry>();
  readonly f = input.required<Field>({ alias: 'cvField' });

  // les données sont modifiées en place (par ce champ, un champ voisin, le correcteur…) : `rev` signale qu'il faut les relire
  protected value = computed(() => (this.store.rev(), this.obj()[this.f().key]));
  /** « Fin » est grisé tant que « En cours » est coché. */
  protected endLocked = computed(() => (this.store.rev(), this.f().key === 'end' && !!this.obj()['current']));

  protected chips(): string[] {
    const v = this.value();
    return Array.isArray(v) ? v : String(v || '').split(',').map((s) => s.trim()).filter(Boolean);
  }

  protected set(value: unknown) {
    this.obj()[this.f().key] = value;
    this.store.changed();
  }
}
