import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { filter, map, startWith } from 'rxjs';
import { Auth } from './auth';
import { Theme } from './theme';

type Page = 'kanban' | 'entreprises' | 'parametres' | 'cv' | 'compte' | '';

/** Barre de navigation latérale, commune à toutes les pages (masquée tant que personne n'est connecté). */
@Component({
  selector: 'app-side-nav',
  imports: [RouterLink],
  host: { '[class.off]': '!auth.user()' },
  template: `
    @if (auth.user(); as u) {
      <a class="brand" routerLink="/" fragment="kanban"><img src="logo.png" alt="" width="34" height="34" /><b>Slimy Career</b></a>
      <nav>
        <a routerLink="/" fragment="kanban" [class.active]="page() === 'kanban'" title="Tableau">
          <svg viewBox="0 0 24 24"><rect x="3" y="4" width="5" height="16" rx="1.5" /><rect x="9.5" y="4" width="5" height="11" rx="1.5" /><rect x="16" y="4" width="5" height="7" rx="1.5" /></svg>
          <span>Tableau</span>
        </a>
        <a routerLink="/" fragment="entreprises" [class.active]="page() === 'entreprises'" title="Entreprises">
          <svg viewBox="0 0 24 24"><path d="M4 20V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v14M14 10h4a2 2 0 0 1 2 2v8M2 20h20M8 8h2M8 12h2M8 16h2" /></svg>
          <span>Entreprises</span>
        </a>
        <a routerLink="/cv" [class.active]="page() === 'cv'" title="CV">
          <svg viewBox="0 0 24 24"><path d="M7 3h7l5 5v11a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM14 3v5h5M9 13h6M9 17h6" /></svg>
          <span>CV</span>
        </a>
        <a routerLink="/" fragment="parametres" [class.active]="page() === 'parametres'" title="Paramètres de recherche">
          <svg viewBox="0 0 24 24"><path d="M4 7h10M18 7h2M4 17h2M10 17h10" /><circle cx="16" cy="7" r="2" /><circle cx="8" cy="17" r="2" /></svg>
          <span>Paramètres</span>
        </a>
      </nav>
      <div class="foot">
        <button type="button" (click)="theme.toggle()" [title]="theme.name() === 'dark' ? 'Passer au thème clair' : 'Passer au thème sombre'">
          @if (theme.name() === 'dark') {
            <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>
            <span>Thème clair</span>
          } @else {
            <svg viewBox="0 0 24 24"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" /></svg>
            <span>Thème sombre</span>
          }
        </button>
        <a routerLink="/compte" [class.active]="page() === 'compte'" title="Mon compte">
          <svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></svg>
          <span class="name">{{ u.username }}</span>
        </a>
        <button type="button" (click)="auth.logout()" title="Déconnexion">
          <svg viewBox="0 0 24 24"><path d="M10 4H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h4M15 8l4 4-4 4M19 12H9" /></svg>
          <span>Déconnexion</span>
        </button>
      </div>
    }
  `,
  styles: `
    :host { width: 208px; flex-shrink: 0; display: flex; flex-direction: column; gap: 4px; padding: 14px 10px 12px;
            background: var(--soft); border-right: 1px solid var(--line); overflow-y: auto; }
    :host(.off) { display: none; }
    .brand { display: flex; align-items: center; gap: 9px; padding: 2px 8px 14px; font-size: 16px; font-weight: 700;
             color: var(--secondary-text); white-space: nowrap; }
    .brand img { width: 34px; height: 34px; display: block; }
    nav, .foot { display: flex; flex-direction: column; gap: 2px; }
    .foot { margin-top: auto; padding-top: 10px; border-top: 1px solid var(--line); }
    a:not(.brand), button { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 8px; border: none;
             background: transparent; color: var(--muted); font-weight: 500; text-align: left; white-space: nowrap; width: 100%; }
    a:not(.brand):hover, button:hover { background: var(--hover); color: var(--text); }
    a.active { background: var(--accent-soft); color: var(--accent-text); }
    a.active:hover { background: var(--accent-soft); color: var(--accent-text); }
    svg { width: 18px; height: 18px; flex-shrink: 0; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
    .name { overflow: hidden; text-overflow: ellipsis; }

    /* écran étroit : la barre passe en haut, icônes seules */
    @media (max-width: 860px) {
      :host { width: auto; flex-direction: row; align-items: center; padding: 6px 8px; gap: 2px; border-right: none;
              border-bottom: 1px solid var(--line); overflow-x: auto; overflow-y: hidden; }
      .brand { padding: 0 6px 0 2px; }
      .brand b, span { display: none; }
      nav, .foot { flex-direction: row; }
      .foot { margin: 0 0 0 auto; padding: 0; border-top: none; }
      a:not(.brand), button { width: auto; padding: 8px; }
    }
  `,
})
export class SideNav {
  protected auth = inject(Auth);
  protected theme = inject(Theme);
  private router = inject(Router);

  private url = toSignal(
    this.router.events.pipe(
      filter((e) => e instanceof NavigationEnd),
      map(() => this.router.url),
      startWith(this.router.url),
    ),
    { initialValue: this.router.url },
  );

  protected readonly page = computed<Page>(() => {
    const tree = this.router.parseUrl(this.url());
    const path = tree.root.children['primary']?.segments[0]?.path ?? '';
    if (path === 'cv') return 'cv';
    if (path === 'compte') return 'compte';
    if (path) return '';
    return tree.fragment === 'entreprises' || tree.fragment === 'parametres' ? tree.fragment : 'kanban';
  });
}
