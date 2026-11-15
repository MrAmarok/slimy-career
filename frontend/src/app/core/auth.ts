import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { Api, ApiError } from './api';

export interface User {
  id: number;
  username: string;
  admin: boolean;
}

@Injectable({ providedIn: 'root' })
export class Auth {
  private api = inject(Api);
  private router = inject(Router);
  readonly user = signal<User | null>(null);
  private loaded = false;

  /** Utilisateur connecté (demandé une fois au serveur, qui pose aussi le jeton CSRF). */
  async current(): Promise<User | null> {
    if (!this.loaded) {
      try {
        this.user.set(await this.api.get<User>('/api/auth/me'));
      } catch (e) {
        if (!(e instanceof ApiError) || e.status !== 401) throw e;
        this.user.set(null);
      }
      this.loaded = true;
    }
    return this.user();
  }

  async login(username: string, password: string): Promise<void> {
    this.user.set(await this.api.post<User>('/api/auth/login', { username, password }));
    this.loaded = true;
  }

  async logout(): Promise<void> {
    await this.api.post('/api/auth/logout').catch(() => {});
    this.forget();
    // rechargement complet : repart d'une session et d'un jeton CSRF neufs
    location.assign('/login');
  }

  forget() {
    this.user.set(null);
    this.loaded = true;
  }
}

export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);
  if (await auth.current()) return true;
  return router.createUrlTree(['/login'], { queryParams: state.url !== '/' ? { next: state.url } : {} });
};

/** Session expirée pendant l'utilisation : retour à l'écran de connexion. */
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(Auth);
  const router = inject(Router);
  return next(req).pipe(
    catchError((e) => {
      if (e instanceof HttpErrorResponse && e.status === 401 && !req.url.startsWith('/api/auth/')) {
        auth.forget();
        router.navigate(['/login'], { queryParams: { next: router.url } });
      }
      return throwError(() => e);
    }),
  );
};
