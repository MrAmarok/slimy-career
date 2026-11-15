import { Routes } from '@angular/router';
import { authGuard } from './core/auth';

export const routes: Routes = [
  { path: 'login', title: 'Connexion', loadComponent: () => import('./login/login').then((m) => m.Login) },
  { path: '', pathMatch: 'full', title: 'Candidatures', canActivate: [authGuard], loadComponent: () => import('./kanban/kanban').then((m) => m.Kanban) },
  { path: 'cv', title: 'CV', canActivate: [authGuard], loadComponent: () => import('./cv/cv').then((m) => m.Cv) },
  { path: 'compte', title: 'Mon compte', canActivate: [authGuard], loadComponent: () => import('./account/account').then((m) => m.Account) },
  { path: '**', redirectTo: '' },
];
