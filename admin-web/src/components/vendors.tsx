import { useState, type FormEvent } from 'react';
import { ApiError } from '../errors';
import { currentTarget, githubApi, linksFor, setTarget, type Vendor } from '../api-github';
import { Group, Notice } from './ui';

/**
 * Selector de la app que se está editando, separado en dos: «App de clientes» (la principal, la que usan
 * tus clientes directos) y «Apps de vendedores» (cada vendedor con su propia marca). Cambiar recarga el estudio.
 */
export function TargetSelect({ vendors }: { vendors: Vendor[] }) {
  const cur = currentTarget();
  const go = (slug: string) => { setTarget(slug); location.reload(); };
  return (
    <div className="target-switch" role="group" aria-label="App que se edita">
      <button className={cur === '' ? 'on' : ''} onClick={() => cur !== '' && go('')}>App de clientes</button>
      <label className={cur !== '' ? 'on' : ''}>
        <span>Apps de vendedores</span>
        <select value={cur} aria-label="Vendedor" onChange={(e) => go(e.target.value)} disabled={vendors.length === 0 && !cur}>
          <option value="" disabled>{vendors.length ? 'Elige un vendedor…' : 'Sin vendedores'}</option>
          {vendors.map((v) => <option key={v.slug} value={v.slug}>{v.name}</option>)}
          {cur && !vendors.some((v) => v.slug === cur) && <option value={cur}>{cur}</option>}
        </select>
      </label>
    </div>
  );
}

/** Franja que deja claro qué app se está editando (y quién verá los cambios al publicar). */
export function EditingBanner({ vendors }: { vendors: Vendor[] }) {
  const cur = currentTarget();
  const name = vendors.find((v) => v.slug === cur)?.name ?? cur;
  return cur
    ? <div className="editing-banner vendor">Estás editando la <b>app del vendedor {name}</b>. Al publicar, solo sus clientes ven los cambios (al abrir la app o al conectarse a internet).</div>
    : <div className="editing-banner clients">Estás editando la <b>app de tus clientes</b>. Al publicar, todos tus clientes ven los cambios al abrir la app o al conectarse a internet, sin descargar nada.</div>;
}

/** Acorta un enlace con is.gd (gratis, sin cuenta). Se usa JSONP porque is.gd no permite peticiones directas desde el navegador. */
function shorten(url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const cb = `evShort${Date.now()}${Math.floor(Math.random() * 1000)}`;
    const w = window as unknown as Record<string, unknown>;
    const s = document.createElement('script');
    const done = () => { delete w[cb]; s.remove(); };
    w[cb] = (r: { shorturl?: string; errormessage?: string }) => { done(); if (r.shorturl) resolve(r.shorturl); else reject(new Error(r.errormessage || 'No se pudo crear el enlace corto')); };
    s.onerror = () => { done(); reject(new Error('No se pudo conectar con is.gd')); };
    s.src = `https://is.gd/create.php?format=json&callback=${cb}&url=${encodeURIComponent(url)}`;
    document.head.appendChild(s);
  });
}

const SHORT_KEY = (slug: string) => `ev.studio.short:${slug || 'principal'}`;

function Links({ slug }: { slug: string }) {
  const l = linksFor(slug);
  const copy = (t: string) => navigator.clipboard?.writeText(t);
  const [short, setShort] = useState<string>(() => { try { return localStorage.getItem(SHORT_KEY(slug)) || ''; } catch { return ''; } });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const make = async () => {
    setBusy(true); setErr('');
    try {
      const s = (await shorten(l.apk)).replace(/^https?:\/\//, '');
      setShort(s); try { localStorage.setItem(SHORT_KEY(slug), s); } catch { /* */ }
    } catch (e) { setErr(e instanceof Error ? e.message : 'Error'); }
    finally { setBusy(false); }
  };
  return (
    <div className="vendor-links">
      <span><b>Código para Downloader:</b>{' '}
        {short ? <><code className="short-code">{short}</code> <button className="link" onClick={() => copy(short)}>Copiar</button></>
          : <button className="btn" disabled={busy} onClick={() => void make()}>{busy ? 'Creando…' : 'Generar código para Downloader'}</button>}
      </span>
      {err && <span className="hint" style={{ color: '#b42318' }}>{err}</span>}
      {short && <span className="hint">Tus clientes escriben <b>{short}</b> en Downloader y se instala la app. Siempre baja la versión más reciente; después se actualiza sola dentro de la app.</span>}
      <span><b>Enlace largo:</b> <code>{l.short}</code> <button className="link" onClick={() => copy(l.short)}>Copiar</button></span>
      <span><b>APK:</b> <a href={l.apk} target="_blank" rel="noreferrer">{l.apk.split('/').pop()}</a></span>
      <span><b>Página de descarga:</b> <a href={l.web} target="_blank" rel="noreferrer">{l.web.replace('https://', '')}</a></span>
      <span className="hint">¿Prefieres un código numérico de Downloader? Pégale el enlace largo en <a href="https://go.aftvnews.com/" target="_blank" rel="noreferrer">go.aftvnews.com</a> (pide resolver un captcha, por eso no se puede hacer automático).</span>
    </div>
  );
}

export function VendorsPanel({ vendors, reload }: { vendors: Vendor[]; reload: () => void }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error' | 'info'; text: string } | null>(null);
  const cur = currentTarget();
  const run = async (f: () => Promise<unknown>, ok: string) => {
    setBusy(true); setMsg(null);
    try { await f(); setMsg({ kind: 'ok', text: ok }); reload(); }
    catch (e) { setMsg({ kind: 'error', text: e instanceof ApiError ? e.message : 'No se pudo completar' }); }
    finally { setBusy(false); }
  };
  const create = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => { const v = await githubApi.createVendor(name); setName(''); return v; },
      'Vendedor creado. Su APK estará lista en unos 10–15 minutos. Elige «Editar su app» para cambiar su logotipo y colores.');
  };
  return (
    <div className="panel-stack">
      <Group title="Apps de vendedores" hint="Cada vendedor tiene su propia app: nombre, logotipo, colores, banners y textos que tú decides. Su APK se instala junto a las demás (no se pisan) y se actualiza sola cuando publicas su diseño.">
        {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
        <form className="vendor-new" onSubmit={create}>
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={30} placeholder="Nombre de la marca del vendedor (ej. Juan TV)" aria-label="Nombre del vendedor" />
          <button className="btn" disabled={busy || name.trim().length < 2}>{busy ? 'Creando…' : 'Crear app de vendedor'}</button>
        </form>
      </Group>
      <Group title="Tus apps" hint="«Editar su app» abre su diseño en el estudio; al publicar solo cambia esa app.">
        <div className={`vendor ${cur === '' ? 'on' : ''}`}>
          <div className="vendor-head"><b>App principal</b>{cur === '' ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(''); location.reload(); }}>Editar su app</button>}</div>
          <Links slug="" />
        </div>
        {vendors.length === 0 && <p className="hint">Todavía no hay vendedores.</p>}
        {vendors.map((v) => (
          <div key={v.slug} className={`vendor ${cur === v.slug ? 'on' : ''}`}>
            <div className="vendor-head">
              <b>{v.name}</b><span className="hint">id: {v.slug}</span>
              {cur === v.slug ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(v.slug); location.reload(); }}>Editar su app</button>}
              <button className="link danger" disabled={busy} onClick={() => { if (confirm(`¿Eliminar la app de ${v.name}? Su APK dejará de actualizarse.`)) void run(() => githubApi.deleteVendor(v.slug), `${v.name} eliminado`); }}>Eliminar</button>
            </div>
            <Links slug={v.slug} />
          </div>
        ))}
      </Group>
      <p className="hint">Pasos para una app nueva: 1) «Crear app de vendedor» · 2) «Editar su app» y cambia logo, nombre y colores · 3) «Publicar» · 4) en unos 15 minutos su APK está lista: pulsa «Generar código para Downloader» y entrégaselo a tu vendedor. Cada versión nueva le llega sola a sus clientes.</p>
    </div>
  );
}
