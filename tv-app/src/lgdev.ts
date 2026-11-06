// LG webOS: renovar el modo desarrollador desde la propia app, para que el cliente no tenga que hacerlo.
//
// LG da sesiones de desarrollador de 50 h; al vencer borra las apps instaladas en modo desarrollador.
// Abrir la app «Developer Mode» (com.palmdts.devmode) con el parámetro {extend:true} equivale a pulsar
// «Extend» en esa app (es lo mismo que hace `ares-launch com.palmdts.devmode -p '{"extend":true}'` y el
// botón «Renew session» de webOS Dev Manager). Lo hacemos al abrir EmbyVerse si pasaron ~20 h desde la
// última renovación, o cuando el administrador lo ordena desde el panel (texto «lg.renew» = fecha en ms).
// Después volvemos a abrir EmbyVerse para que el cliente siga donde estaba.
import { isWebOS } from './platform';

const DEVMODE_APP = 'com.palmdts.devmode';
const KEY_LAST = 'ev.lgdev.last';
const KEY_CMD = 'ev.lgdev.cmd';
const KEY_ERR = 'ev.lgdev.err';
const EVERY_MS = 20 * 3600_000;

const keep: unknown[] = []; // el puente de Luna se pierde si el recolector de basura lo libera antes de responder

/** Llamada a un servicio del sistema webOS (Luna). */
export function luna(uri: string, params: Record<string, unknown>, timeoutMs = 8000): Promise<any> {
  return new Promise((resolve, reject) => {
    const W = window as any;
    const t = setTimeout(() => reject(new Error('sin respuesta del sistema')), timeoutMs);
    const done = (ok: boolean, v: any) => { clearTimeout(t); ok ? resolve(v) : reject(v); };
    try {
      if (W.webOS?.service?.request) {
        const i = uri.lastIndexOf('/');
        W.webOS.service.request(uri.slice(0, i), { method: uri.slice(i + 1), parameters: params, onSuccess: (r: any) => done(true, r), onFailure: (e: any) => done(false, e) });
        return;
      }
      if (typeof W.PalmServiceBridge === 'undefined') { done(false, new Error('no es una TV LG')); return; }
      const b = new W.PalmServiceBridge();
      keep.push(b);
      b.onservicecallback = (msg: string) => {
        keep.splice(keep.indexOf(b), 1);
        try { const r = JSON.parse(msg); done(r.returnValue !== false, r); } catch (e) { done(false, e); }
      };
      b.call(uri, JSON.stringify(params));
    } catch (e) { done(false, e); }
  });
}

export const lgDevSupported = () => isWebOS && (typeof (window as any).PalmServiceBridge !== 'undefined' || !!(window as any).webOS?.service?.request);

function ownId(): string {
  try { const id = String((window as any).PalmSystem?.identifier ?? '').split(' ')[0]; if (id) return id; } catch { /* */ }
  return 'com.embyverse.tv';
}

const num = (k: string) => { try { return Number(localStorage.getItem(k) || 0); } catch { return 0; } };
const put = (k: string, v: string) => { try { localStorage.setItem(k, v); } catch { /* */ } };

export const lgDevLast = () => num(KEY_LAST);
export const lgDevError = () => { try { return localStorage.getItem(KEY_ERR) || ''; } catch { return ''; } };

/** Renueva ya. Devuelve true si la TV aceptó abrir Developer Mode con «extend». */
export async function renewLgDevMode(): Promise<boolean> {
  if (!lgDevSupported()) return false;
  try {
    await luna('luna://com.webos.applicationManager/launch', { id: DEVMODE_APP, params: { extend: true } });
    put(KEY_LAST, String(Date.now())); put(KEY_ERR, '');
    // Regresar a EmbyVerse (Developer Mode queda en segundo plano).
    setTimeout(() => { luna('luna://com.webos.applicationManager/launch', { id: ownId() }).catch(() => {}); }, 3500);
    return true;
  } catch (e: any) {
    put(KEY_ERR, `${new Date().toLocaleString('es-MX')}: ${e?.errorText || e?.message || 'error'}`);
    return false;
  }
}

/** ¿Toca renovar? Por tiempo (cada ~20 h) o porque el panel publicó una orden nueva (lg.renew). */
export function lgDevDue(texts: Record<string, string>): boolean {
  if (!lgDevSupported()) return false;
  const cmd = Number(texts['lg.renew'] || 0);
  if (cmd > 0 && cmd > num(KEY_CMD)) { put(KEY_CMD, String(cmd)); return true; }
  return Date.now() - lgDevLast() > EVERY_MS;
}
