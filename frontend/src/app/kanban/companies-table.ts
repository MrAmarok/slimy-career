import { Component, computed, input, model, output } from '@angular/core';
import { Job, Status, collator, companyOf, dateKey, relativeDate } from './models';

export interface CompanySort {
  key: string;
  desc: boolean;
}

interface Column {
  key: string;
  label: string;
  num?: boolean;
  wrap?: boolean;
}

interface Group {
  name: string;
  count: number;
  cities: Set<string>;
  citiesText: string;
  last: string;
  byStatus: Record<string, number>;
}

/** Valeur d'une colonne pour une entreprise (les colonnes de statut sont des compteurs). */
function valueOf(g: Group, key: string): string | number {
  switch (key) {
    case 'name':
      return g.name;
    case 'count':
      return g.count;
    case 'citiesText':
      return g.citiesText;
    case 'last':
      return g.last;
    default:
      return g.byStatus[key] ?? 0;
  }
}

/** Onglet « Entreprises » : une ligne par entreprise, avec le nombre d'offres par statut. */
@Component({
  selector: 'app-companies-table',
  template: `
    <table>
      <thead>
        <tr>
          @for (c of columns(); track c.key) {
            <th [class.num]="c.num" (click)="sortBy(c)">{{ c.label }}{{ arrow(c) }}</th>
          }
        </tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.name) {
          <tr [title]="'Voir les offres de ' + row.name" (click)="pick.emit(row.name)">
            @for (cell of row.cells; track $index) {
              <td [class.num]="cell.num" [class.zero]="cell.zero" [class.wrap]="cell.wrap" [style.font-weight]="cell.bold ? 600 : null">{{ cell.text }}</td>
            }
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    :host { display: block; flex: 1; overflow: auto; padding: 0 20px 20px; }
    table { border-collapse: separate; border-spacing: 0; width: 100%; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
    th, td { text-align: left; padding: 10px 14px; border-bottom: 1px solid var(--line); white-space: nowrap; }
    td.wrap { white-space: normal; color: var(--muted); font-size: 13px; }
    tr:last-child td { border-bottom: none; }
    th { font-size: 12px; font-weight: 600; color: var(--muted); background: var(--soft); cursor: pointer; user-select: none; }
    th:hover { color: var(--text); }
    th.num, td.num { text-align: right; }
    td.num.zero { color: var(--line-strong); }
    tbody tr { cursor: pointer; }
    tbody tr:hover td { background: var(--soft); }
    @media (max-width: 860px) {
      :host { padding-left: 12px; padding-right: 12px; }
    }
  `,
})
export class CompaniesTable {
  /** offres visibles (après filtres) */
  readonly jobs = input.required<Job[]>();
  readonly statuses = input.required<Status[]>();
  readonly sort = model<CompanySort>({ key: 'count', desc: true });
  /** clic sur une entreprise */
  readonly pick = output<string>();

  protected readonly columns = computed<Column[]>(() => [
    { key: 'name', label: 'Entreprise' },
    { key: 'count', label: 'Offres', num: true },
    ...this.statuses()
      .filter((s) => s.key !== 'a_trier')
      .map((s) => ({ key: s.key, label: s.label, num: true })),
    { key: 'citiesText', label: 'Villes', wrap: true },
    { key: 'last', label: 'Dernière offre' },
  ]);

  protected readonly rows = computed(() => {
    const groups = new Map<string, Group>();
    for (const j of this.jobs()) {
      const name = companyOf(j);
      let g = groups.get(name);
      if (!g) groups.set(name, (g = { name, count: 0, cities: new Set(), citiesText: '', last: '', byStatus: {} }));
      g.count++;
      g.byStatus[j.status] = (g.byStatus[j.status] ?? 0) + 1;
      if (j.city) g.cities.add(j.city);
      if (dateKey(j) > g.last) g.last = dateKey(j);
    }
    for (const g of groups.values()) g.citiesText = [...g.cities].sort(collator.compare).join(', ');

    const { key, desc } = this.sort();
    const columns = this.columns();
    return [...groups.values()]
      .sort((a, b) => {
        const x = valueOf(a, key);
        const y = valueOf(b, key);
        const r = typeof x === 'number' && typeof y === 'number' ? x - y : collator.compare(String(x), String(y));
        return (desc ? -r : r) || collator.compare(a.name, b.name);
      })
      .map((g) => ({
        name: g.name,
        cells: columns.map((c) => {
          const v = c.key === 'last' ? relativeDate(g.last) : valueOf(g, c.key);
          const zero = !!c.num && !v;
          return { text: zero ? '0' : v, num: !!c.num, zero, wrap: !!c.wrap, bold: c.key === 'name' };
        }),
      }));
  });

  protected arrow(c: Column): string {
    const s = this.sort();
    return s.key === c.key ? (s.desc ? ' ↓' : ' ↑') : '';
  }

  protected sortBy(c: Column) {
    const s = this.sort();
    this.sort.set({ key: c.key, desc: s.key === c.key ? !s.desc : !!c.num });
  }
}
