import { Component, ElementRef, inject, input, model, output, viewChild } from '@angular/core';

/**
 * Liste de valeurs éditable sous forme de pastilles : Entrée ou virgule pour ajouter, Retour arrière pour retirer la dernière.
 * `changed` n'est émis que pour une modification faite par l'utilisateur (pas quand le parent change `values`).
 */
@Component({
  selector: 'app-chip-editor',
  template: `
    @for (v of values(); track $index) {
      <span class="chip">{{ v }}<button type="button" title="Retirer" (click)="remove($index, $event)">×</button></span>
    }
    <input #field [placeholder]="placeholder()" [attr.list]="list() || null" (keydown)="onKeydown($event)" />
  `,
  host: { '(click)': 'onHostClick($event)' },
  styles: `
    :host { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; border: 1px solid var(--line-strong); border-radius: 8px; padding: 6px; min-height: 40px; background: #fff; cursor: text; }
    :host(:focus-within) { border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
    input { border: none; flex: 1; min-width: 140px; padding: 3px 4px; box-shadow: none !important; }
    .chip { display: inline-flex; align-items: center; gap: 4px; background: var(--soft); border: 1px solid var(--line); border-radius: 6px; padding: 2px 4px 2px 8px; font-size: 12.5px; }
    .chip button { border: none; background: none; padding: 0 4px; color: var(--faint); font-size: 15px; line-height: 1; }
    .chip button:hover { color: var(--danger); background: none; }
  `,
})
export class ChipEditor {
  readonly values = model<string[]>([]);
  readonly placeholder = input('Ajouter…');
  /** id d'une <datalist> de suggestions */
  readonly list = input<string | null>(null);
  readonly changed = output<string[]>();

  private host: ElementRef<HTMLElement> = inject(ElementRef);
  private field = viewChild.required<ElementRef<HTMLInputElement>>('field');

  /** Valide le texte en cours de saisie (s'il y en a) et renvoie la liste complète. */
  flush(): string[] {
    if (this.field().nativeElement.value.trim()) this.add(this.field().nativeElement.value);
    return [...this.values()];
  }

  /** Oublie le texte en cours de saisie (quand le parent charge d'autres valeurs). */
  clearInput() {
    this.field().nativeElement.value = '';
  }

  protected onKeydown(e: KeyboardEvent) {
    if (e.isComposing) return;
    const field = this.field().nativeElement;
    if ((e.key === 'Enter' || e.key === ',') && field.value.trim()) {
      e.preventDefault();
      this.add(field.value);
      field.focus();
    } else if (e.key === 'Backspace' && !field.value && this.values().length) {
      this.emit(this.values().slice(0, -1));
    }
  }

  protected remove(index: number, e: Event) {
    e.stopPropagation();
    this.emit(this.values().filter((_, i) => i !== index));
  }

  protected onHostClick(e: Event) {
    if (e.target === this.host.nativeElement) this.field().nativeElement.focus();
  }

  private add(raw: string) {
    const next = [...this.values()];
    let added = false;
    for (const v of raw.split(',').map((s) => s.trim()).filter(Boolean)) {
      if (!next.some((x) => x.toLowerCase() === v.toLowerCase())) {
        next.push(v);
        added = true;
      }
    }
    this.field().nativeElement.value = '';
    if (added) this.emit(next);
  }

  private emit(next: string[]) {
    this.values.set(next);
    this.changed.emit(next);
  }
}
