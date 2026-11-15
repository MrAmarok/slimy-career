import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from './auth';

/** Nom de l'utilisateur connecté + liens « Mon compte » et « Déconnexion », à placer à droite de l'en-tête. */
@Component({
  selector: 'app-user-menu',
  imports: [RouterLink],
  template: `
    @if (auth.user(); as u) {
      <a routerLink="/compte" class="um-name" title="Mon compte">{{ u.username }}</a>
      <button type="button" class="btn-ghost" (click)="auth.logout()">Déconnexion</button>
    }
  `,
  styles: `
    :host { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
    .um-name { color: var(--text); font-weight: 500; padding: 6px 8px; border-radius: 8px; }
    .um-name:hover { background: var(--hover); }
  `,
})
export class UserMenu {
  protected auth = inject(Auth);
}
