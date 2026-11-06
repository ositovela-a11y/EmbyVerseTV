// Navegación con el control (flechas) entre elementos marcados con data-fx.
// Elige el elemento visible más cercano en la dirección pulsada; los grupos (data-fx-group) recuerdan su último foco.
type Dir = 'left' | 'right' | 'up' | 'down';

const SEL = '[data-fx]';
let current: HTMLElement | null = null;
const listeners = new Set<(el: HTMLElement | null) => void>();

export function onFocusChange(cb: (el: HTMLElement | null) => void) { listeners.add(cb); return () => { listeners.delete(cb); }; }

function visible(el: HTMLElement) {
  if (!el.isConnected || el.hasAttribute('data-fx-disabled')) return false;
  const r = el.getBoundingClientRect();
  if (r.width === 0 || r.height === 0) return false;
  // Dentro de la capa activa (diálogo, panel) si la hay.
  const layer = activeLayer();
  return !layer || layer.contains(el);
}

function activeLayer(): HTMLElement | null {
  const layers = Array.from(document.querySelectorAll<HTMLElement>('[data-fx-layer]'));
  return layers.length ? layers[layers.length - 1] : null;
}

export function all(): HTMLElement[] { return Array.from(document.querySelectorAll<HTMLElement>(SEL)).filter(visible); }

export function focus(el: HTMLElement | null, opts: { scroll?: boolean } = {}) {
  if (!el) return;
  if (current && current !== el) current.classList.remove('focused');
  current = el;
  el.classList.add('focused');
  try { el.focus({ preventScroll: true } as any); } catch { el.focus(); }
  const g = el.closest<HTMLElement>('[data-fx-group]');
  if (g) (g as any).__last = el;
  if (opts.scroll !== false) scrollIntoView(el);
  try { el.dispatchEvent(new Event('fx-focus')); } catch { /* */ }
  listeners.forEach((l) => l(el));
}

export function focused() { return current && current.isConnected ? current : null; }

/** Enfoca el elemento con data-fx-default (o el primero) dentro de root. */
export function focusFirst(root: ParentNode = document) {
  const layer = activeLayer();
  // Sin capa: primero el contenido de la pantalla (no el menú lateral).
  const scopes: ParentNode[] = layer ? [layer] : [document.querySelector('.content') ?? root, root];
  let el: HTMLElement | null = null;
  for (const scope of scopes) {
    const def = Array.from(scope.querySelectorAll<HTMLElement>('[data-fx-default]')).find(visible);
    el = def ?? Array.from(scope.querySelectorAll<HTMLElement>(SEL)).find(visible) ?? null;
    if (el) break;
  }
  focus(el);
  return el;
}

function center(r: DOMRect) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

export function move(dir: Dir): boolean {
  const cur = focused();
  if (!cur || !visible(cur)) { focusFirst(); return true; }
  const cr = cur.getBoundingClientRect();
  const cc = center(cr);
  let best: HTMLElement | null = null; let bestScore = Infinity;
  for (const el of all()) {
    if (el === cur) continue;
    const r = el.getBoundingClientRect(); const c = center(r);
    let primary: number, secondary: number;
    if (dir === 'right') { if (r.left < cr.right - 4 && c.x <= cc.x + 2) continue; primary = Math.max(0, r.left - cr.right); secondary = Math.abs(c.y - cc.y); if (c.x <= cc.x) continue; }
    else if (dir === 'left') { if (r.right > cr.left + 4 && c.x >= cc.x - 2) continue; primary = Math.max(0, cr.left - r.right); secondary = Math.abs(c.y - cc.y); if (c.x >= cc.x) continue; }
    else if (dir === 'down') { if (r.top < cr.bottom - 4 && c.y <= cc.y + 2) continue; primary = Math.max(0, r.top - cr.bottom); secondary = Math.abs(c.x - cc.x); if (c.y <= cc.y) continue; }
    else { if (r.bottom > cr.top + 4 && c.y >= cc.y - 2) continue; primary = Math.max(0, cr.top - r.bottom); secondary = Math.abs(c.x - cc.x); if (c.y >= cc.y) continue; }
    // Se prefiere seguir en la misma fila/columna (lo que espera el usuario con un control).
    const overlap = dir === 'left' || dir === 'right' ? (r.bottom > cr.top && r.top < cr.bottom) : (r.right > cr.left && r.left < cr.right);
    const score = primary + secondary * (overlap ? 0.3 : 2.2) + (overlap ? 0 : 400);
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (!best) return false;
  // Al entrar a un grupo (una fila) se vuelve a su último elemento enfocado.
  const g = best.closest<HTMLElement>('[data-fx-group]');
  const curG = cur.closest<HTMLElement>('[data-fx-group]');
  if (g && g !== curG && (dir === 'up' || dir === 'down')) {
    const last: HTMLElement | undefined = (g as any).__last;
    if (last && last.isConnected && visible(last)) best = last;
  }
  focus(best);
  return true;
}

/** Desplaza las filas/listas para que el elemento enfocado quede visible (sin animación pesada). */
export function scrollIntoView(el: HTMLElement) {
  let p: HTMLElement | null = el.parentElement;
  while (p) {
    const st = getComputedStyle(p);
    const r = el.getBoundingClientRect(); const pr = p.getBoundingClientRect();
    if ((st.overflowX === 'auto' || st.overflowX === 'scroll' || st.overflowX === 'hidden') && p.scrollWidth > p.clientWidth + 2) {
      const pad = 90;
      if (r.right > pr.right - pad) p.scrollLeft += r.right - pr.right + pad;
      else if (r.left < pr.left + pad) p.scrollLeft -= pr.left + pad - r.left;
    }
    if ((st.overflowY === 'auto' || st.overflowY === 'scroll' || st.overflowY === 'hidden') && p.scrollHeight > p.clientHeight + 2) {
      const pad = 140;
      if (r.bottom > pr.bottom - pad) p.scrollTop += r.bottom - pr.bottom + pad;
      else if (r.top < pr.top + pad) p.scrollTop -= pr.top + pad - r.top;
    }
    p = p.parentElement;
  }
}
