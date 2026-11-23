import { Injectable, signal } from '@angular/core';

export type ThemeName = 'light' | 'dark';

/** Thème clair ou sombre. La valeur initiale est posée sur <html> par `theme.js`, avant le démarrage d'Angular. */
@Injectable({ providedIn: 'root' })
export class Theme {
  readonly name = signal<ThemeName>(document.documentElement.dataset['theme'] === 'dark' ? 'dark' : 'light');

  toggle() {
    const next: ThemeName = this.name() === 'dark' ? 'light' : 'dark';
    this.name.set(next);
    document.documentElement.dataset['theme'] = next;
    try {
      localStorage.setItem('theme', next);
    } catch {
      /* stockage indisponible : le choix vaut pour cette visite */
    }
  }
}
