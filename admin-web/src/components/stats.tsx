// Estadísticas: quién instaló y usa cada app, separado entre TUS clientes (app principal) y los clientes de
// cada vendedor (sus apps). Los datos vienen del servidor de estadísticas (Cloudflare) con la clave del panel.
import { useEffect, useMemo, useState } from 'react';
import { githubApi, blockDeviceKey, blockUserKey, deviceShortCode, type Blocked, type Vendor } from '../api-github';
import { toCsv } from '../registry';
import { Group, Notice } from './ui';

const KEY = 'ev.studio.statskey';
const DAY = 86_400_000;
export interface DeviceRow { id: string; app: string; platform: string; model: string; version: string; xuser: string; host: string; country: string; first_seen: number; last_seen: number; opens: number }
export interface DlRow { day: string; app: string; platform: string; country: string; n: number }
const PLAT: Record<string, string> = { android: 'Celular Android', androidtv: 'Android TV', samsung: 'Samsung', lg: 'LG', pc: 'PC', web: 'Web', otro: 'Otro' };
const ago = (t: number, now: number) => { const m = Math.round((now - t) / 60_000); return m < 60 ? `hace ${m} min` : m < 1440 ? `hace ${Math.round(m / 60)} h` : `hace ${Math.round(m / 1440)} días`; };
const date = (t: number) => new Date(t).toISOString().slice(0, 10);

type Scope = 'mine' | 'vendors' | 'all';
type CrashRow = { sig: string; app: string; platform: string; version: string; kind: 'crash' | 'play' | 'js'; message: string; detail: string; model: string; os: string; last_id: string; devices: number; n: number; first_seen: number; last_seen: number };
const KIND: Record<string, string> = { crash: 'Se cerró la app', js: 'Error en la app', play: 'No reprodujo' };

/** Barras simples (SVG) de los últimos 30 días. */
function Bars({ days, values, color }: { days: string[]; values: number[]; color: string }) {
  const max = Math.max(1, ...values);
  return (
    <svg viewBox={`0 0 ${days.length * 12} 64`} preserveAspectRatio="none" style={{ width: "100%", height: 90 }} role="img" aria-label="Últimos 30 días">
      {values.map((v, i) => <rect key={i} x={i * 12 + 1} y={60 - (v / max) * 56} width={10} height={Math.max(v ? 2 : 0, (v / max) * 56)} rx={2} fill={color}><title>{`${days[i]}: ${v}`}</title></rect>)}
      <line x1="0" y1="60.5" x2={days.length * 12} y2="60.5" stroke="#d0d5dd" />
    </svg>
  );
}

export function StatsPanel() {
  const [url, setUrl] = useState<string | null | undefined>(undefined);
  const [key, setKey] = useState(() => { try { return localStorage.getItem(KEY) || ''; } catch { return ''; } });
  const [draft, setDraft] = useState('');
  const [data, setData] = useState<{ now: number; devices: DeviceRow[]; downloads: DlRow[]; crashes?: CrashRow[] } | null>(null);
  const [openCrash, setOpenCrash] = useState('');
  const [vendors, setVendors] = useState<Vendor[]>([]);
  const [err, setErr] = useState(''); const [busy, setBusy] = useState(false);
  const [scope, setScope] = useState<Scope>('mine'); const [vendor, setVendor] = useState('*');
  const [q, setQ] = useState(''); const [onlyInactive, setOnlyInactive] = useState(false);
  // Bloqueos: lista pública con huellas; aquí se calcula la huella de cada equipo para saber si está bloqueado.
  const [blocked, setBlocked] = useState<Blocked | null>(null);
  const [keys, setKeys] = useState<Record<string, { d: string; u: string | null }>>({});
  const [onlyBlocked, setOnlyBlocked] = useState(false);
  const [msg, setMsg] = useState('');
  useEffect(() => { void githubApi.blocked().then((b) => setBlocked(b.data)).catch(() => setBlocked({ v: 1, devices: {}, users: {} })); }, []);
  useEffect(() => { if (!data) return; let live = true; void (async () => {
    const out: Record<string, { d: string; u: string | null }> = {};
    for (const x of data.devices) out[x.id] = { d: await blockDeviceKey(x.id), u: x.xuser ? await blockUserKey(x.app, x.host, x.xuser) : null };
    if (live) setKeys(out);
  })(); return () => { live = false; }; }, [data]);
  const blockOf = (id: string): 'device' | 'user' | null => {
    const k = keys[id]; if (!k || !blocked) return null;
    return blocked.devices[k.d] ? 'device' : k.u && blocked.users[k.u] ? 'user' : null;
  };

  useEffect(() => { void (async () => {
    try { const [u, v] = await Promise.all([githubApi.statsUrl(), githubApi.vendors().catch(() => [])]); setUrl(u); setVendors(v); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); setUrl(null); }
  })(); }, []);

  const load = async (k = key) => {
    if (!url || !k) return;
    setBusy(true); setErr('');
    try {
      const r = await fetch(`${url}/v1/stats`, { headers: { authorization: `Bearer ${k}` } });
      if (r.status === 401) throw new Error('Clave de estadísticas incorrecta.');
      if (!r.ok) throw new Error(`El servidor de estadísticas respondió con error (${r.status}).`);
      setData(await r.json());
    } catch (e) { setErr(e instanceof Error ? e.message : 'No se pudo conectar con el servidor de estadísticas.'); setData(null); }
    finally { setBusy(false); }
  };
  useEffect(() => { if (url && key) void load(); }, [url]);

  const vName = (slug: string) => (slug ? vendors.find((v) => v.slug === slug)?.name ?? slug : 'App principal');
  const inScope = (app: string) => scope === 'all' ? true : scope === 'mine' ? app === '' : app !== '' && (vendor === '*' || app === vendor);

  const view = useMemo(() => {
    if (!data) return null;
    const now = data.now;
    const devs = data.devices.filter((d) => inScope(d.app));
    const dls = data.downloads.filter((d) => inScope(d.app));
    const days = Array.from({ length: 30 }, (_, i) => date(now - (29 - i) * DAY));
    const installsByDay = days.map((d) => devs.filter((x) => date(x.first_seen) === d).length);
    const dlByDay = days.map((d) => dls.filter((x) => x.day === d).reduce((t, x) => t + x.n, 0));
    const byPlat: Record<string, number> = {};
    devs.forEach((d) => { byPlat[d.platform] = (byPlat[d.platform] || 0) + 1; });
    const perVendor = [...new Set(data.devices.filter((d) => d.app).map((d) => d.app).concat(vendors.map((v) => v.slug)))].map((slug) => {
      const ds = data.devices.filter((d) => d.app === slug);
      return { slug, installs: ds.length, active7: ds.filter((d) => now - d.last_seen < 7 * DAY).length,
        users: new Set(ds.map((d) => d.xuser).filter(Boolean)).size, downloads: data.downloads.filter((d) => d.app === slug).reduce((t, d) => t + d.n, 0) };
    }).sort((a, b) => b.installs - a.installs);
    const f = q.trim().toLowerCase();
    const rows = devs.filter((d) => (!onlyInactive || now - d.last_seen > 30 * DAY) && (!onlyBlocked || blockOf(d.id))
      && (!f || [d.xuser, d.model, d.host, d.country, vName(d.app), PLAT[d.platform], deviceShortCode(d.id)].some((x) => (x || '').toLowerCase().includes(f))));
    return {
      now, rows, days, installsByDay, dlByDay, byPlat, perVendor,
      total: devs.length, users: new Set(devs.map((d) => d.xuser).filter(Boolean)).size,
      a1: devs.filter((d) => now - d.last_seen < DAY).length, a7: devs.filter((d) => now - d.last_seen < 7 * DAY).length,
      a30: devs.filter((d) => now - d.last_seen < 30 * DAY).length,
      dl30: dlByDay.reduce((t, n) => t + n, 0), dlAll: dls.reduce((t, d) => t + d.n, 0),
      nBlocked: devs.filter((d) => blockOf(d.id)).length,
    };
  }, [data, scope, vendor, q, onlyInactive, onlyBlocked, vendors, keys, blocked]);

  const csv = () => {
    if (!view) return;
    const rows = [['Usuario', 'App', 'Equipo', 'Modelo', 'Versión', 'Servidor', 'País', 'Instalada', 'Último uso', 'Aperturas']];
    view.rows.forEach((d) => rows.push([d.xuser, vName(d.app), PLAT[d.platform] ?? d.platform, d.model, d.version, d.host, d.country, date(d.first_seen), date(d.last_seen), String(d.opens)]));
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([toCsv(rows)], { type: 'text/csv;charset=utf-8' }));
    a.download = `instalaciones-${scope === 'mine' ? 'mis-clientes' : scope === 'vendors' ? 'vendedores' : 'todas'}-${date(Date.now())}.csv`; a.click();
  };

  const resolveCrash = async (c: CrashRow) => {
    if (!url || !window.confirm('¿Quitar este fallo de la lista? Si vuelve a pasar, aparece otra vez.')) return;
    setBusy(true);
    try { await fetch(`${url}/v1/crashes/${encodeURIComponent(c.sig)}`, { method: 'DELETE', headers: { authorization: `Bearer ${key}` } });
      setData((x) => x ? { ...x, crashes: (x.crashes ?? []).filter((y) => y.sig !== c.sig) } : x); } finally { setBusy(false); }
  };
  const remove = async (d: DeviceRow) => {
    if (!url || !confirm(`¿Eliminar el equipo ${deviceShortCode(d.id)} del registro?\n\nSolo se borra de esta lista: si vuelve a abrir la app, aparece de nuevo. Para que ya no pueda usarla, usa «Bloquear».`)) return;
    await fetch(`${url}/v1/devices/${encodeURIComponent(d.id)}`, { method: 'DELETE', headers: { authorization: `Bearer ${key}` } });
    setMsg(`Equipo ${deviceShortCode(d.id)} eliminado del registro.`);
    await load();
  };
  /** Bloquear / desbloquear un equipo o todos los equipos de un usuario. Las apps lo aplican en 1–3 minutos. */
  const setBlock = async (d: DeviceRow, what: 'device' | 'user', on: boolean) => {
    const k = keys[d.id]; if (!k) return;
    const who = what === 'device' ? `el equipo ${deviceShortCode(d.id)} (${PLAT[d.platform] ?? d.platform} ${d.model})` : `al usuario ${d.xuser} en ${vName(d.app)} (todos sus equipos)`;
    if (on && !confirm(`¿Bloquear ${who}?\n\nLa app se cierra en ese equipo en 1–3 minutos y muestra «Este equipo fue bloqueado». Puedes desbloquearlo cuando quieras.`)) return;
    setBusy(true); setErr(''); setMsg('');
    try {
      const b = await githubApi.saveBlocked((x) => {
        const id = what === 'device' ? k.d : k.u; if (!id) return;
        const bag = what === 'device' ? x.devices : x.users;
        if (on) bag[id] = { at: new Date().toISOString() }; else delete bag[id];
      }, `${on ? 'bloquea' : 'desbloquea'} ${what === 'device' ? 'un equipo' : 'un usuario'}`);
      setBlocked(b);
      setMsg(`✓ ${on ? 'Bloqueado' : 'Desbloqueado'}: ${who}. Se aplica en la app en 1–3 minutos.`);
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  };

  if (url === undefined) return <div className="panel"><p className="hint">Cargando…</p></div>;

  if (!url) return (
    <div className="panel">
      <Group title="Conecta tu servidor de estadísticas" hint="Es gratis (Cloudflare). Se hace una sola vez; después todo se ve aquí.">
        <ol className="steps">
          <li>Crea una cuenta gratis en <b>dash.cloudflare.com</b> y entra una vez a <b>Workers y Pages</b> (te asigna tu dirección workers.dev).</li>
          <li>En Cloudflare: <b>Mi perfil → Tokens de API → Crear token → «Editar Cloudflare Workers»</b>, y agrégale el permiso <b>Cuenta · D1 · Editar</b>. Copia el token.</li>
          <li>Copia tu <b>ID de cuenta</b> (aparece a la derecha en «Workers y Pages»).</li>
          <li>En GitHub, tu repositorio → <b>Settings → Secrets and variables → Actions → New repository secret</b>, crea: <code>CLOUDFLARE_API_TOKEN</code>, <code>CLOUDFLARE_ACCOUNT_ID</code> y <code>STATS_ADMIN_KEY</code> (una clave que tú inventes; es la que usarás aquí).</li>
          <li>En GitHub → <b>Actions → «Servidor de estadísticas» → Run workflow</b>. En unos minutos esta pantalla mostrará tus estadísticas.</li>
        </ol>
      </Group>
      {err && <Notice kind="error">{err}</Notice>}
    </div>
  );

  if (!key || (!data && err.includes('Clave'))) return (
    <div className="panel">
      <Group title="Estadísticas" hint="Escribe la clave de estadísticas (la que pusiste como STATS_ADMIN_KEY).">
        <div className="act-form">
          <label>Clave de estadísticas<input className="input" type="password" value={draft} onChange={(e) => setDraft(e.target.value)} aria-label="Clave de estadísticas" /></label>
          <button className="btn" disabled={!draft || busy} id="stats-open" onClick={() => { try { localStorage.setItem(KEY, draft); } catch { /* */ } setKey(draft); void load(draft); }}>Ver estadísticas</button>
        </div>
        {err && <Notice kind="error">{err}</Notice>}
      </Group>
    </div>
  );

  return (
    <div className="panel">
      <div className="act-filters" style={{ marginBottom: 14 }}>
        <div className="seg" role="tablist">
          {([['mine', 'Mis clientes'], ['vendors', 'Clientes de vendedores'], ['all', 'Todo']] as const).map(([k, l]) =>
            <button key={k} className={scope === k ? 'on' : ''} onClick={() => setScope(k)} id={`scope-${k}`}>{l}</button>)}
        </div>
        {scope === 'vendors' && <select className="input" value={vendor} onChange={(e) => setVendor(e.target.value)} aria-label="Filtrar vendedor" style={{ maxWidth: 240 }}>
          <option value="*">Todos los vendedores</option>{vendors.map((v) => <option key={v.slug} value={v.slug}>{v.name}</option>)}
        </select>}
        <button className="btn" onClick={() => void load()} disabled={busy}>{busy ? 'Actualizando…' : 'Actualizar'}</button>
        <button className="link" onClick={() => { try { localStorage.removeItem(KEY); } catch { /* */ } setKey(''); setData(null); }}>Cerrar</button>
      </div>
      {err && <Notice kind="error">{err}</Notice>}
      {!view ? <p className="hint">Cargando…</p> : <>
        <div className="act-stats stats-cards">
          <div><b id="st-total">{view.total}</b><span>instalaciones</span></div>
          <div><b>{view.users}</b><span>usuarios distintos</span></div>
          <div><b>{view.a1}</b><span>usaron la app hoy</span></div>
          <div><b>{view.a7}</b><span>activos en 7 días</span></div>
          <div><b>{view.a30}</b><span>activos en 30 días</span></div>
          <div><b>{view.dl30}</b><span>descargas en 30 días ({view.dlAll} en total)</span></div>
          <div><b id="st-blocked">{view.nBlocked}</b><span>equipos bloqueados</span></div>
        </div>
        <Group title="Últimos 30 días" hint="Instalaciones nuevas (verde) y descargas (azul) por día. Pasa el mouse sobre una barra para ver el número.">
          <Bars days={view.days} values={view.installsByDay} color="#12b76a" />
          <Bars days={view.days} values={view.dlByDay} color="#2e90fa" />
          <div className="plat-chips">{Object.entries(view.byPlat).sort((a, b) => b[1] - a[1]).map(([p, n]) => <span key={p} className="chip">{PLAT[p] ?? p}: <b>{n}</b></span>)}</div>
        </Group>
        {scope !== 'mine' && view.perVendor.length > 0 && (
          <Group title="Por vendedor" hint="Cada vendedor tiene su propia app: aquí ves cuántos clientes tiene cada uno.">
            <div style={{ overflowX: 'auto' }}>
              <table className="act-table">
                <thead><tr><th>Vendedor</th><th>Instalaciones</th><th>Activos 7 días</th><th>Usuarios</th><th>Descargas</th></tr></thead>
                <tbody>{view.perVendor.map((v) => <tr key={v.slug}><td>{vName(v.slug)}</td><td>{v.installs}</td><td>{v.active7}</td><td>{v.users}</td><td>{v.downloads}</td></tr>)}</tbody>
              </table>
            </div>
          </Group>
        )}
        {(() => {
          const cr = (data.crashes ?? []).filter((c) => inScope(c.app));
          return (
            <Group title={`Fallos (${cr.length})`} hint="Cierres inesperados de la app y videos que no se pudieron reproducir por su formato, en los últimos 60 días. Se agrupan: «Veces» y «Equipos» dicen a cuántos clientes les pasa. Cuando lo arreglemos, márcalo como resuelto.">
              {cr.length === 0 ? <p className="hint" id="no-crashes">Sin fallos reportados. 👍</p> : (
                <div style={{ overflowX: 'auto' }}>
                  <table className="act-table" id="crash-table">
                    <thead><tr><th>Qué pasó</th>{scope !== 'mine' && <th>App</th>}<th>Equipo</th><th>Versión</th><th>Veces</th><th>Equipos</th><th>Último</th><th></th></tr></thead>
                    <tbody>{cr.map((c) => (
                      <tr key={c.sig} data-crash={c.sig}>
                        <td><b className={c.kind === 'play' ? 'upd-wait' : 'danger-text'}>{KIND[c.kind] ?? c.kind}</b><div className="mono crash-msg">{c.message}</div>
                          {openCrash === c.sig && c.detail && <pre className="crash-detail">{c.detail}</pre>}</td>
                        {scope !== 'mine' && <td>{vName(c.app)}</td>}
                        <td>{PLAT[c.platform] ?? c.platform}<div className="hint">{c.model}</div><div className="hint">{c.os}</div></td>
                        <td>{c.version}</td><td>{c.n}</td><td>{c.devices}</td><td>{ago(c.last_seen, view.now)}</td>
                        <td><div className="row-actions">
                          {c.detail && <button className="link" onClick={() => setOpenCrash(openCrash === c.sig ? '' : c.sig)}>{openCrash === c.sig ? 'Ocultar' : 'Detalle'}</button>}
                          <button className="link" onClick={() => void navigator.clipboard?.writeText(`${KIND[c.kind]} · ${c.message}\n${PLAT[c.platform] ?? c.platform} ${c.model} ${c.os} · versión ${c.version} · ${c.n} veces en ${c.devices} equipos\n\n${c.detail}`)}>Copiar</button>
                          <button className="link" disabled={busy} onClick={() => void resolveCrash(c)}>Resuelto</button>
                        </div></td>
                      </tr>))}
                    </tbody>
                  </table>
                </div>)}
            </Group>);
        })()}
        <Group title={`Equipos (${view.rows.length})`} hint="Ordenados por último uso. Un mismo usuario puede tener varios equipos. «Bloquear» cierra la app en ese equipo (o en todos los del usuario) en 1–3 minutos; «Eliminar» solo lo borra de la lista."
          actions={<button className="btn" onClick={csv} disabled={!view.rows.length}>Descargar Excel (CSV)</button>}>
          <div className="act-filters">
            <input className="input" placeholder="Buscar usuario, modelo, país…" value={q} onChange={(e) => setQ(e.target.value)} style={{ maxWidth: 300 }} aria-label="Buscar" />
            <label style={{ display: 'flex', gap: 6, alignItems: 'center' }}><input type="checkbox" checked={onlyInactive} onChange={(e) => setOnlyInactive(e.target.checked)} /> Solo sin uso en 30 días</label>
            <label style={{ display: 'flex', gap: 6, alignItems: 'center' }}><input type="checkbox" id="only-blocked" checked={onlyBlocked} onChange={(e) => setOnlyBlocked(e.target.checked)} /> Solo bloqueados</label>
          </div>
          {msg && <Notice kind="ok">{msg}</Notice>}
          {view.rows.length === 0 ? <p className="hint">Todavía no hay equipos aquí.</p> : (
            <div style={{ overflowX: 'auto' }}>
              <table className="act-table">
                <thead><tr><th>Usuario</th>{scope !== 'mine' && <th>App</th>}<th>Equipo</th><th>Versión</th><th>País</th><th>Instalada</th><th>Último uso</th><th>Estado</th><th></th></tr></thead>
                <tbody>{view.rows.slice(0, 500).map((d) => { const b = blockOf(d.id); return (
                  <tr key={d.id} className={b ? 'blocked' : view.now - d.last_seen > 30 * DAY ? 'expired' : ''} data-dev={d.id}>
                    <td>{d.xuser || <span className="hint">sin iniciar sesión</span>}{d.host && <div className="hint">{d.host}</div>}</td>
                    {scope !== 'mine' && <td>{vName(d.app)}</td>}
                    <td>{PLAT[d.platform] ?? d.platform}<div className="hint">{d.model}</div><div className="hint mono">Código {deviceShortCode(d.id)}</div></td>
                    <td>{d.version}</td><td>{d.country}</td><td>{date(d.first_seen)}</td>
                    <td>{ago(d.last_seen, view.now)}<div className="hint">{d.opens} aperturas</div></td>
                    <td>{b ? <span className="status-blocked">⛔ Bloqueado{b === 'user' ? ' (usuario)' : ''}</span> : <span className="status-ok">Activo</span>}</td>
                    <td><div className="row-actions">
                      {b === 'device' && <button className="link" disabled={busy} onClick={() => void setBlock(d, 'device', false)}>Desbloquear</button>}
                      {b === 'user' && <button className="link" disabled={busy} onClick={() => void setBlock(d, 'user', false)}>Desbloquear usuario</button>}
                      {!b && <button className="link danger" disabled={busy || !keys[d.id]} onClick={() => void setBlock(d, 'device', true)}>Bloquear</button>}
                      {!b && d.xuser && <button className="link danger" disabled={busy || !keys[d.id]} onClick={() => void setBlock(d, 'user', true)}>Bloquear usuario</button>}
                      <button className="link" disabled={busy} onClick={() => void remove(d)}>Eliminar</button>
                    </div></td>
                  </tr>); })}
                </tbody>
              </table>
              {view.rows.length > 500 && <p className="hint">Se muestran 500; descarga el Excel para verlos todos.</p>}
            </div>
          )}
        </Group>
      </>}
    </div>
  );
}
