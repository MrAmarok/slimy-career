import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Auth } from '../core/auth';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  template: `
    <form class="box" (ngSubmit)="submit()">
      <h1>Candidatures</h1>
      <p class="muted">Connecte-toi pour retrouver ton tableau et tes CV.</p>
      <label>Identifiant<input name="username" [(ngModel)]="username" autocomplete="username" required autofocus /></label>
      <label>Mot de passe<input name="password" type="password" [(ngModel)]="password" autocomplete="current-password" required /></label>
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
      <button class="btn-primary" type="submit" [disabled]="busy() || !username || !password">
        {{ busy() ? 'Connexion…' : 'Se connecter' }}
      </button>
    </form>
  `,
  styles: `
    :host { flex: 1; display: grid; place-items: center; background: var(--soft); padding: 16px; }
    .box { width: 100%; max-width: 360px; background: #fff; border: 1px solid var(--line); border-radius: 14px;
           box-shadow: var(--shadow-lg); padding: 28px; display: flex; flex-direction: column; gap: 14px; }
    h1 { margin: 0; font-size: 20px; }
    p { margin: -8px 0 4px; }
    label { display: flex; flex-direction: column; gap: 5px; font-size: 12px; font-weight: 500; color: var(--muted); }
    input { font-size: 14px; color: var(--text); }
    .error { color: var(--danger); font-size: 13px; }
    button { padding: 9px 12px; margin-top: 4px; }
  `,
})
export class Login {
  private auth = inject(Auth);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  username = '';
  password = '';
  readonly busy = signal(false);
  readonly error = signal('');

  async submit() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.username.trim(), this.password);
      const next = this.route.snapshot.queryParamMap.get('next');
      await this.router.navigateByUrl(next && next.startsWith('/') && !next.startsWith('/login') ? next : '/');
    } catch (e: any) {
      this.error.set(e?.message || 'Connexion impossible.');
    } finally {
      this.busy.set(false);
    }
  }
}
