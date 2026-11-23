import { ChangeDetectionStrategy, Component, ElementRef, booleanAttribute, computed, inject, input, output, signal, viewChild } from '@angular/core';

export interface FilterOption {
  value: string;
  n: number;
}

/**
 * Bouton de filtre avec liste déroulante. Choix unique (pays, ville…) ou multiple (compétences…).
 * Le bouton rappelle la sélection ; la liste propose une recherche dès qu'elle est longue.
 */
@Component({
  selector: 'app-filter-menu',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:click)': 'onDocumentClick($event)', '(keydown.escape)': 'close(true)' },
  template: `
    <button #trigger type="button" class="trigger" [class.on]="selected().length" [attr.aria-expanded]="open()" (click)="toggleOpen()">
      <span class="lbl">{{ label() }}</span>
      @if (summary(); as text) {
        <span class="val">{{ text }}</span>
      }
      <svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6" /></svg>
    </button>
    @if (open()) {
      <div class="menu" [class.right]="alignRight()">
        @if (options().length > 8) {
          <input #search type="search" placeholder="Chercher…" [value]="query()" (input)="query.set($any($event.target).value)" />
        }
        <div class="list">
          @if (!multi()) {
            <button type="button" class="item" [class.sel]="!selected().length" (click)="choose('')">
              <span class="name">{{ allLabel() }}</span>
            </button>
          }
          @for (o of shown(); track o.value) {
            <button type="button" class="item" [class.sel]="isSelected(o.value)" [title]="o.value" (click)="choose(o.value)">
              @if (multi()) {
                <span class="box">✓</span>
              }
              <span class="name">{{ o.value }}</span><span class="n">{{ o.n }}</span>
            </button>
          } @empty {
            <div class="none">{{ options().length ? 'Aucun résultat' : "Rien pour l'instant" }}</div>
          }
        </div>
        @if (multi() && selected().length) {
          <button type="button" class="clear" (click)="cleared.emit()">Tout décocher</button>
        }
      </div>
    }
  `,
  styles: `
    :host { position: relative; display: inline-block; }
    .trigger { display: inline-flex; align-items: center; gap: 6px; padding: 6px 8px 6px 11px; font-size: 13px; max-width: 240px; background: var(--surface); }
    .trigger .lbl { color: var(--muted); white-space: nowrap; }
    .trigger .val { font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .trigger.on { border-color: var(--accent); background: var(--accent-soft); }
    .trigger.on .lbl, .trigger.on .val { color: var(--accent-text); }
    .trigger svg { width: 14px; height: 14px; flex-shrink: 0; fill: none; stroke: var(--faint); stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
    .menu { position: absolute; top: calc(100% + 6px); left: 0; z-index: 18; width: 270px; max-width: calc(100vw - 24px); background: var(--surface);
            border: 1px solid var(--line); border-radius: 12px; box-shadow: var(--shadow-lg); padding: 8px; display: flex; flex-direction: column; gap: 6px; }
    .menu.right { left: auto; right: 0; }
    .menu input { width: 100%; }
    .list { display: flex; flex-direction: column; max-height: 280px; overflow-y: auto; }
    .item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; border: none; border-radius: 7px; background: transparent; font-weight: 400; font-size: 13px; text-align: left; }
    .item:hover { background: var(--hover); }
    .item.sel { color: var(--accent-text); font-weight: 600; }
    .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .n { color: var(--faint); font-size: 12px; font-weight: 400; }
    .box { width: 15px; height: 15px; border-radius: 4px; border: 1.5px solid var(--line-strong); display: grid; place-items: center; font-size: 10px; color: transparent; flex-shrink: 0; }
    .item.sel .box { background: var(--accent); border-color: var(--accent); color: #fff; }
    .none { color: var(--muted); font-size: 12px; padding: 8px; }
    .clear { border: none; background: transparent; color: var(--muted); font-size: 12px; padding: 5px 8px; align-self: flex-start; }
    .clear:hover { color: var(--text); background: var(--hover); }
  `,
})
export class FilterMenu {
  readonly label = input.required<string>();
  readonly options = input.required<readonly FilterOption[]>();
  readonly selected = input.required<readonly string[]>();
  readonly multi = input(false, { transform: booleanAttribute });
  /** libellé de l'entrée « pas de filtre » (choix unique) */
  readonly allLabel = input('Tous');
  /** valeur choisie (chaîne vide : pas de filtre) ou, en choix multiple, valeur cochée ou décochée */
  readonly picked = output<string>();
  readonly cleared = output<void>();

  protected readonly open = signal(false);
  protected readonly query = signal('');
  protected readonly alignRight = signal(false);
  private host: ElementRef<HTMLElement> = inject(ElementRef);
  private trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private search = viewChild<ElementRef<HTMLInputElement>>('search');

  protected readonly summary = computed(() => {
    const selected = this.selected();
    if (!selected.length) return '';
    return selected.length === 1 ? selected[0] : `${selected.length}`;
  });

  protected readonly shown = computed(() => {
    const q = this.query().trim().toLowerCase();
    return q ? this.options().filter((o) => o.value.toLowerCase().includes(q)) : this.options();
  });

  protected isSelected(value: string) {
    return this.selected().includes(value);
  }

  protected toggleOpen() {
    if (this.open()) return this.close();
    const left = this.trigger().nativeElement.getBoundingClientRect().left;
    this.alignRight.set(left + 282 > innerWidth);
    this.query.set('');
    this.open.set(true);
    setTimeout(() => this.search()?.nativeElement.focus());
  }

  protected choose(value: string) {
    this.picked.emit(value);
    if (!this.multi()) this.close(true);
  }

  protected close(focus = false) {
    if (!this.open()) return;
    this.open.set(false);
    if (focus) this.trigger().nativeElement.focus();
  }

  protected onDocumentClick(event: Event) {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) this.close();
  }
}
