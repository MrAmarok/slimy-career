/** Morceaux de CSS utilisés par plusieurs composants du tableau (chaque composant garde ses styles encapsulés). */

export const PILL_CSS = `
  .pill { border-radius: 999px; padding: 5px 12px; font-size: 13px; border: 1px solid var(--line); background: #fff; color: var(--text); font-weight: 500; }
  .pill .n { color: var(--faint); margin-left: 5px; font-weight: 400; }
  .pill:hover { border-color: var(--line-strong); background: #fff; }
  .pill.on { background: var(--accent-soft); border-color: var(--accent); color: var(--accent-text); }
  .pill.on .n { color: var(--accent-text); }
`;

export const TAG_CSS = `
  .tag { font-size: 11px; padding: 1px 7px; border-radius: 5px; background: var(--soft); color: #374151; white-space: nowrap; }
  .tag.cat { background: var(--accent-soft); color: var(--accent-text); font-weight: 500; }
`;

export const DOT_CSS = `
  .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
`;

export const FORM_GRID_CSS = `
  .form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; margin-top: 14px; }
  .form-grid label { display: flex; flex-direction: column; gap: 5px; font-size: 13px; font-weight: 500; }
`;

export const CHECKS_CSS = `
  .checks { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 14px; }
  .checks label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
  .checks input { accent-color: var(--accent); }
`;

export const DRAWER_CSS = `
  .drawer { position: fixed; top: 0; right: 0; bottom: 0; width: min(520px, 100vw); background: #fff; border-left: 1px solid var(--line); box-shadow: var(--shadow-lg); z-index: 20; display: flex; flex-direction: column; transform: translateX(100%); transition: transform .2s ease; }
  .drawer.open { transform: none; }
  .drawer:not(.open) { box-shadow: none; visibility: hidden; transition: transform .2s ease, visibility 0s .2s; }
  .drawer-head { padding: 18px 20px 14px; border-bottom: 1px solid var(--line); display: flex; gap: 10px; align-items: flex-start; }
  .drawer-head h2 { margin: 0; font-size: 17px; line-height: 1.35; }
  .drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px 24px; display: flex; flex-direction: column; gap: 18px; }
  .drawer h4 { margin: 0 0 7px; font-size: 12px; font-weight: 600; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
  .drawer textarea { width: 100%; min-height: 80px; resize: vertical; }
  .drawer-foot { padding: 12px 20px; border-top: 1px solid var(--line); display: flex; gap: 8px; align-items: center; }
`;
