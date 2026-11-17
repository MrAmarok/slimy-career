import { Component, computed, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChipEditor } from './chip-editor';
import { INFO_FIELDS, ListKey, ORDER_PRESETS, SCHEMAS, SECTION_LABELS, SKILL_CATEGORIES, SectionKey } from './cv-model';
import { CvCard, CvField, EntryControls } from './cv-parts';
import { CvStore } from './cv-store';

/** Informations personnelles et photo. */
@Component({
  selector: 'app-info-section',
  imports: [CvCard, CvField],
  template: `
    @let data = store.data;
    <app-cv-card key="info" title="Informations personnelles">
      <div class="photo-box">
        <div class="photo" [class.has]="data.photo" [style.background-image]="data.photo ? 'url(' + data.photo + ')' : null" (click)="file.click()">
          Ajouter une photo
        </div>
        <div class="photo-side">
          <div class="row">
            <button type="button" (click)="file.click()">{{ data.photo ? 'Changer' : 'Choisir une photo' }}</button>
            @if (data.photo) {
              <button type="button" class="btn-danger" (click)="removePhoto()">Retirer</button>
            }
          </div>
          <label [cvField]="showPhoto" [obj]="data"></label>
          <div class="hint">Les logiciels RH ignorent la photo : elle ne gêne pas la lecture. Décoche-la pour les candidatures à l'international.</div>
        </div>
      </div>
      <div class="grid">
        @for (f of fields; track f.key) {
          <label [cvField]="f" [obj]="data.info"></label>
        }
      </div>
      <input #file type="file" accept="image/*" class="hidden" (change)="pickPhoto(file)" />
    </app-cv-card>
  `,
})
export class InfoSection {
  protected store = inject(CvStore);
  protected fields = INFO_FIELDS;
  protected showPhoto = { key: 'show_photo', label: 'Afficher la photo sur le CV', type: 'check' as const };

  protected removePhoto() {
    this.store.data.photo = null;
    this.store.changed();
  }

  /* photo : recadrée en carré et réduite avant d'être enregistrée */
  protected pickPhoto(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    const cv = this.store.cv();
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      URL.revokeObjectURL(url);
      if (this.store.cv() !== cv || !cv) return; // un autre CV a été ouvert entre-temps
      const side = Math.min(img.width, img.height), size = Math.min(500, side);
      const c = document.createElement('canvas');
      c.width = c.height = size;
      const ctx = c.getContext('2d')!;
      ctx.fillStyle = '#fff';
      ctx.fillRect(0, 0, size, size);
      // recadrage centré, légèrement vers le haut (visage)
      ctx.drawImage(img, (img.width - side) / 2, Math.max(0, (img.height - side) / 2.6), side, side, 0, 0, size, size);
      cv.data.photo = c.toDataURL('image/jpeg', 0.88);
      cv.data.show_photo = true;
      this.store.changed();
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      this.store.toast('Image illisible');
    };
    img.src = url;
  }
}

/** Section faite de fiches dépliables (expériences, projets, formations, certifications). */
@Component({
  selector: 'app-list-section',
  imports: [CvCard, CvField, EntryControls],
  template: `
    @let list = store.data[key()];
    <app-cv-card [key]="key()" [title]="label()" [count]="list.length">
      @for (item of list; track item) {
        @let title = schema().title(item);
        <details class="entry" [open]="store.openItems.has(item)" (toggle)="toggled(item, $any($event.target).open)">
          <summary>
            <span class="t">{{ title[0] || '(sans titre)' }}<span class="sub">{{ title[1] ? ' — ' + title[1] : '' }}</span></span>
            <app-entry-controls [list]="list" [index]="$index" />
          </summary>
          <div class="grid entry-body">
            @for (f of schema().fields; track f.key) {
              <label [cvField]="f" [obj]="item"></label>
            }
          </div>
        </details>
      }
      <button type="button" class="add" (click)="add()">{{ schema().add }}</button>
    </app-cv-card>
  `,
})
export class ListSection {
  protected store = inject(CvStore);
  readonly key = input.required<ListKey>();
  protected schema = computed(() => SCHEMAS[this.key()]);
  protected label = computed(() => SECTION_LABELS[this.key()]);

  protected toggled(item: object, open: boolean) {
    if (open) this.store.openItems.add(item);
    else this.store.openItems.delete(item);
  }

  protected add() {
    const item = {};
    this.store.data[this.key()].push(item);
    this.store.openItems.add(item);
    this.store.changed();
  }
}

/** Compétences techniques, regroupées par catégorie. */
@Component({
  selector: 'app-skills-section',
  imports: [FormsModule, CvCard, ChipEditor, EntryControls],
  template: `
    @let list = store.data.hard_skills;
    <app-cv-card key="hard_skills" [title]="label" [count]="count()">
      <p class="hint">Regroupe par catégorie (Langages, Frameworks, DevOps, Outils…). Écris les noms exacts utilisés dans les offres : « Kubernetes » plutôt que « k8s ».</p>
      @for (g of list; track g) {
        <div class="entry skill-group">
          <div class="row">
            <input class="grow" placeholder="Catégorie (ex : Langages)" [ngModel]="g.category || ''" (ngModelChange)="g.category = $event; store.changed()" />
            <app-entry-controls [list]="list" [index]="$index" />
          </div>
          <app-chip-editor [values]="g.items" placeholder="Python, Docker… (Entrée pour ajouter)" (valuesChange)="g.items = $event; store.changed()" />
        </div>
      }
      <div class="row wrap">
        <button type="button" class="add" (click)="add('')">+ Ajouter une catégorie</button>
        @if (suggestions().length) {
          <span class="hint">ou :</span>
          @for (c of suggestions(); track c) {
            <button type="button" class="btn-ghost icon" title="Catégorie courante dans les CV informatiques" (click)="add(c)">+ {{ c }}</button>
          }
        }
      </div>
    </app-cv-card>
  `,
})
export class SkillsSection {
  protected store = inject(CvStore);
  protected label = SECTION_LABELS.hard_skills;

  protected count() {
    return this.store.data.hard_skills.reduce((n, g) => n + (g.items?.length || 0), 0);
  }

  protected suggestions = computed(() => {
    const have = new Set(this.store.data.hard_skills.map((g) => (g.category || '').trim().toLowerCase()));
    return SKILL_CATEGORIES[this.store.lang].filter((c) => !have.has(c.toLowerCase()));
  });

  protected add(category: string) {
    this.store.data.hard_skills.push({ category, items: [] });
    this.store.changed();
  }
}

/** Langue des titres, ordres conseillés et ordre des sections. */
@Component({
  selector: 'app-layout-section',
  imports: [FormsModule, CvCard, EntryControls],
  template: `
    @let data = store.data;
    <app-cv-card key="layout" title="Mise en page et ordre des sections">
      <div class="grid">
        <label class="field">Langue des titres de sections
          <select [ngModel]="data.lang || 'fr'" (ngModelChange)="data.lang = $event; store.changed()">
            <option value="fr">Français</option>
            <option value="en">English</option>
          </select>
        </label>
      </div>
      <div class="field">Ordre conseillé</div>
      <div class="presets">
        @for (p of presets; track p.label) {
          <button type="button" [class.on]="p === current()" [title]="p.why" (click)="apply(p.order)">{{ p.label }}</button>
        }
      </div>
      <div class="preset-hint">{{ current()?.why }}</div>
      <div class="field">Ordre des sections (ajuste à la main si besoin)</div>
      <div class="order-list">
        @for (k of data.section_order; track k) {
          <div class="order-item">
            <span>{{ labels[k] }}</span>
            <app-entry-controls [list]="data.section_order" [index]="$index" [deletable]="false" />
          </div>
        }
      </div>
    </app-cv-card>
  `,
})
export class LayoutSection {
  protected store = inject(CvStore);
  protected presets = ORDER_PRESETS;
  protected labels = SECTION_LABELS;

  /** Ordre conseillé qui correspond à l'ordre actuel des sections, s'il y en a un. */
  protected current = computed(() => {
    const order = this.store.data.section_order;
    return ORDER_PRESETS.find((p) => p.order.length === order.length && p.order.every((k, i) => k === order[i]));
  });

  protected apply(order: SectionKey[]) {
    this.store.data.section_order = [...order];
    this.store.changed();
  }
}
