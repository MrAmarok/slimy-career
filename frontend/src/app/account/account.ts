import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../core/api';
import { Auth, User } from '../core/auth';
import { UserMenu } from '../core/user-menu';

interface Notice {
  ok: boolean;
  text: string;
}

@Component({
  selector: 'app-account',
  imports: [FormsModule, RouterLink, UserMenu],
  templateUrl: './account.html',
  styleUrl: './account.css',
})
export class Account implements OnInit {
  private api = inject(Api);
  protected auth = inject(Auth);

  // mot de passe
  current = '';
  password = '';
  confirm = '';
  readonly passwordNotice = signal<Notice | null>(null);

  // sauvegarde
  readonly dataNotice = signal<Notice | null>(null);
  readonly busy = signal(false);

  // comptes (administrateur)
  readonly users = signal<User[]>([]);
  newUsername = '';
  newPassword = '';
  newAdmin = false;
  readonly usersNotice = signal<Notice | null>(null);

  ngOnInit() {
    if (this.auth.user()?.admin) this.loadUsers();
  }

  private message(e: unknown): string {
    return (e as Error)?.message || 'Erreur';
  }

  async changePassword() {
    this.passwordNotice.set(null);
    if (this.password !== this.confirm) {
      this.passwordNotice.set({ ok: false, text: 'Les deux mots de passe ne sont pas identiques.' });
      return;
    }
    try {
      await this.api.post('/api/auth/password', { current: this.current, password: this.password });
      this.current = this.password = this.confirm = '';
      this.passwordNotice.set({ ok: true, text: 'Mot de passe modifié.' });
    } catch (e) {
      this.passwordNotice.set({ ok: false, text: this.message(e) });
    }
  }

  async exportData() {
    this.dataNotice.set(null);
    try {
      const r = await this.api.getBlob('/api/account/export');
      const url = URL.createObjectURL(r.body!);
      const a = document.createElement('a');
      a.href = url;
      a.download = r.headers.get('X-Filename') || 'candidatures.json';
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e) {
      this.dataNotice.set({ ok: false, text: this.message(e) });
    }
  }

  async importData(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.dataNotice.set(null);
    this.busy.set(true);
    try {
      let data: unknown;
      try {
        data = JSON.parse(await file.text());
      } catch {
        throw new Error("Ce fichier n'est pas une sauvegarde de Candidatures.");
      }
      const r = await this.api.post<{ jobs: number; jobs_skipped: number; cvs: number; config: boolean }>('/api/account/import', data);
      const parts = [`${r.jobs} offre(s) ajoutée(s)`];
      if (r.jobs_skipped) parts.push(`${r.jobs_skipped} déjà présente(s)`);
      parts.push(`${r.cvs} CV ajouté(s)`);
      if (r.config) parts.push('paramètres de recherche repris');
      this.dataNotice.set({ ok: true, text: `Import terminé : ${parts.join(', ')}.` });
    } catch (e) {
      this.dataNotice.set({ ok: false, text: this.message(e) });
    } finally {
      this.busy.set(false);
    }
  }

  async loadUsers() {
    try {
      this.users.set(await this.api.get<User[]>('/api/admin/users'));
    } catch (e) {
      this.usersNotice.set({ ok: false, text: this.message(e) });
    }
  }

  async createUser() {
    this.usersNotice.set(null);
    try {
      const u = await this.api.post<User>('/api/admin/users', { username: this.newUsername, password: this.newPassword, admin: this.newAdmin });
      this.newUsername = this.newPassword = '';
      this.newAdmin = false;
      this.usersNotice.set({ ok: true, text: `Compte « ${u.username} » créé. Transmets-lui son mot de passe : il pourra le changer ici.` });
      await this.loadUsers();
    } catch (e) {
      this.usersNotice.set({ ok: false, text: this.message(e) });
    }
  }

  async resetPassword(u: User) {
    const password = prompt(`Nouveau mot de passe pour « ${u.username} » (8 caractères minimum) :`);
    if (!password) return;
    this.usersNotice.set(null);
    try {
      await this.api.put(`/api/admin/users/${u.id}/password`, { password });
      this.usersNotice.set({ ok: true, text: `Mot de passe de « ${u.username} » modifié.` });
    } catch (e) {
      this.usersNotice.set({ ok: false, text: this.message(e) });
    }
  }

  async deleteUser(u: User) {
    if (!confirm(`Supprimer le compte « ${u.username} » ? Ses offres, ses CV et ses paramètres seront effacés définitivement.`)) return;
    this.usersNotice.set(null);
    try {
      await this.api.delete(`/api/admin/users/${u.id}`);
      await this.loadUsers();
    } catch (e) {
      this.usersNotice.set({ ok: false, text: this.message(e) });
    }
  }
}
