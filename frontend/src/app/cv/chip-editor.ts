import { Component, ElementRef, inject, input, output, viewChild } from '@angular/core';
import { CvStore } from './cv-store';

/** Liste de mots-clés : Entrée ou virgule pour ajouter, Retour arrière dans le champ vide pour retirer le dernier. */
@Component({
  selector: 'app-chip-editor',
  host: { class: 'chip-editor', '(click)': 'onHostClick($event)' },
  template: `
    @for (v of list; track $index) {
      <span class="chip">{{ v }}<button type="button" title="Retirer" (click)="remove($index, $event)">×</button></span>
    }
    <input #field [placeholder]="placeholder()" (keydown)="onKey($event)" (blur)="onBlur()" />
  `,
})
export class ChipEditor {
  readonly values = input<string[] | null | undefined>([]);
  readonly placeholder = input('Ajouter…');
  readonly valuesChange = output<string[]>();

  private host = inject<ElementRef<HTMLElement>>(ElementRef);
  private store = inject(CvStore);
  private field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  protected get list(): string[] {
    this.store.rev(); // le tableau peut être modifié en place (correcteur) : on le relit à chaque modification du CV
    return this.values() || [];
  }

  private add(raw: string) {
    const next = [...this.list];
    for (const v of raw.split(',').map((x) => x.trim()).filter(Boolean)) {
      if (!next.some((x) => x.toLowerCase() === v.toLowerCase())) next.push(v);
    }
    this.field().nativeElement.value = '';
    if (next.length !== this.list.length) this.valuesChange.emit(next);
  }

  protected onKey(e: KeyboardEvent) {
    const el = this.field().nativeElement;
    if ((e.key === 'Enter' || e.key === ',') && el.value.trim()) {
      e.preventDefault();
      this.add(el.value);
      el.focus();
    } else if (e.key === 'Backspace' && !el.value && this.list.length) {
      this.valuesChange.emit(this.list.slice(0, -1));
    }
  }

  protected onBlur() {
    const el = this.field().nativeElement;
    if (el.value.trim()) this.add(el.value);
  }

  protected remove(i: number, e: Event) {
    e.stopPropagation();
    this.valuesChange.emit(this.list.filter((_, k) => k !== i));
  }

  protected onHostClick(e: Event) {
    if (e.target === this.host.nativeElement) this.field().nativeElement.focus();
  }
}
