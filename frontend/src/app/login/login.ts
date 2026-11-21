import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiError } from '../core/api';
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
      @if (needCode()) {
        <label>Code de vérification
          <input #codeInput name="code" [(ngModel)]="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="123 456" required />
        </label>
      }
      @if (error()) {
        <div class="error" role="alert">{{ error() }}</div>
      }
      <button class="btn-primary" type="submit" [disabled]="busy() || !username || !password || (needCode() && !code)">
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
  code = '';
  /** vrai quand le mot de passe est bon et que le serveur attend le code de l'application d'authentification */
  readonly needCode = signal(false);
  private codeInput = viewChild<ElementRef<HTMLInputElement>>('codeInput');
  readonly busy = signal(false);
  readonly error = signal('');

  async submit() {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.username.trim(), this.password, this.needCode() ? this.code.trim() : '');
      const next = this.route.snapshot.queryParamMap.get('next');
      await this.router.navigateByUrl(next && next.startsWith('/') && !next.startsWith('/login') ? next : '/');
    } catch (e: any) {
      if (e instanceof ApiError && e.status === 428) {
        // première demande du code : simple consigne ; ensuite, c'est que le code saisi est faux
        const first = !this.needCode();
        this.needCode.set(true);
        this.code = '';
        this.error.set(first ? '' : e.message);
        setTimeout(() => this.codeInput()?.nativeElement.focus());
        return;
      }
      this.needCode.set(false);
      this.code = '';
      this.error.set(e?.message || 'Connexion impossible.');
    } finally {
      this.busy.set(false);
    }
  }
}
