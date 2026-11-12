// Activaciones: el cliente o vendedor te manda el código que ve en su PC o TV y tú lo activas aquí.
// El cobro es por fuera (efectivo, transferencia…). En el archivo público solo queda un resumen del
// código (no el código) y la fecha de vencimiento. Quién es, de qué app, teléfono, cuánto pagó y su
// historial quedan en el REGISTRO CIFRADO (se abre con tu clave y lo ves igual desde cualquier equipo).
import { useEffect, useMemo, useState } from 'react';
import { githubApi, type Licenses, type Vendor } from '../api-github';
import { open, seal, statusOf, daysUntil, today, toCsv, type Device, type RegEntry, type Registry, type SealedRegistry, type Status } from '../registry';
import { Group, Notice } from './ui';

const OLD_NOTES = 'ev.studio.activations'; // notas guardadas solo en el navegador (versión anterior)
const PASS_KEY = 'ev.studio.regkey';

/** «k7m 42q» → «K7M-42Q…»: mayúsculas, sin espacios, guion en medio. */
export function normCode(raw: string): string | null {
  const c = raw.toUpperCase().replace(/[^A-Z0-9]/g, '');
  return /^[A-Z0-9]{8}$/.test(c) ? `${c.slice(0, 4)}-${c.slice(4)}` : null;
}
export async function licHash(code: string): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('lic:' + code.replace('-', '')));
  return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
}
const DURATIONS: { label: string; months: number }[] = [
  { label: '1 mes', months: 1 }, { label: '3 meses', months: 3 }, { label: '6 meses', months: 6 }, { label: '1 año', months: 12 }, { label: 'De por vida', months: 0 },
];
const DEVICES: Device[] = ['PC', 'TV', 'Celular', 'Otro'];
const addMonths = (from: Date, m: number) => { const d = new Date(from); d.setMonth(d.getMonth() + m); return d.toISOString().slice(0, 10); };
const STATUS: Record<Status, { label: string; color: string }> = {
  life: { label: 'De por vida', color: '#067647' }, active: { label: 'Activo', color: '#067647' },
  soon: { label: 'Por vencer', color: '#b54708' }, expired: { label: 'Vencido', color: '#d92d20' },
};
const money = (s?: string) => { const n = Number(String(s ?? '').replace(/[^0-9.]/g, '')); return Number.isFinite(n) ? n : 0; };
const storedPass = () => { try { return localStorage.getItem(PASS_KEY) || ''; } catch { return ''; } };

type Row = { h: string; exp: string | null; st: Status; e: RegEntry | null };

export function ActivationsPanel() {
  const [lic, setLic] = useState<Licenses | null>(null);
  const [vendors, setVendors] = useState<Vendor[]>([]);
  const [err, setErr] = useState(''); const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  // Registro cifrado
  const [stage, setStage] = useState<'loading' | 'create' | 'locked' | 'open'>('loading');
  const [reg, setReg] = useState<Registry>({ v: 1, entries: {} });
  const [pass, setPass] = useState(''); const [pass2, setPass2] = useState(''); const [remember, setRemember] = useState(true);
  const [activePass, setActivePass] = useState('');
  // Formulario
  const [code, setCode] = useState(''); const [name, setName] = useState(''); const [phone, setPhone] = useState('');
  const [app, setApp] = useState(''); const [device, setDevice] = useState<Device>('PC'); const [months, setMonths] = useState(12); const [price, setPrice] = useState('');
  // Lista
  const [filter, setFilter] = useState(''); const [show, setShow] = useState<'all' | Status>('all'); const [appFilter, setAppFilter] = useState('*');

  const appName = (slug: string) => (slug ? vendors.find((v) => v.slug === slug)?.name ?? slug : 'Principal');

  const loadRegistry = async (p: string) => {
    const f = await githubApi.registry();
    if (!f) { setStage('create'); return; }
    if (!p) { setStage('locked'); return; }
    try { setReg(await open(f.data, p)); setActivePass(p); setStage('open'); }
    catch (e) { setStage('locked'); throw e; }
  };

  useEffect(() => {
    void (async () => {
      try {
        const [l, v] = await Promise.all([githubApi.licenses(), githubApi.vendors().catch(() => [])]);
        setLic(l.data); setVendors(v);
        await loadRegistry(storedPass());
      } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    })();
  }, []);

  const run = async (fn: () => Promise<string>) => {
    setBusy(true); setErr(''); setOk('');
    try { const m = await fn(); if (m) setOk(m); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  };

  /** Cambia el registro: se relee justo antes (por si lo cambiaste desde otro equipo), se cifra y se guarda. */
  const changeRegistry = async (change: (r: Registry) => void, msg: string) => {
    const f = await githubApi.registry();
    const cur = f ? await open(f.data, activePass) : { v: 1 as const, entries: {} };
    change(cur);
    await githubApi.saveRegistry(await seal(cur, activePass), f?.sha ?? null, msg);
    setReg({ ...cur });
  };

  const unlock = () => run(async () => {
    await loadRegistry(pass);
    try { if (remember) localStorage.setItem(PASS_KEY, pass); else localStorage.removeItem(PASS_KEY); } catch { /* */ }
    setPass(''); return 'Registro abierto.';
  });

  const create = (fresh = false) => run(async () => {
    if (pass.length < 6) throw new Error('La clave del registro debe tener al menos 6 caracteres.');
    if (pass !== pass2) throw new Error('Las dos claves no coinciden.');
    const f = await githubApi.registry();
    if (f && !fresh) throw new Error('Ya existe un registro: ábrelo con su clave.');
    // Se traen las notas que antes solo estaban en este navegador.
    const r: Registry = { v: 1, entries: {} };
    try {
      const old = JSON.parse(localStorage.getItem(OLD_NOTES) ?? '{}') as Record<string, { code: string; note: string; at: string }>;
      for (const [h, n] of Object.entries(old)) r.entries[h] = { hash: h, code: n.code, name: n.note || '', app: '', device: 'Otro', first: n.at, history: [] };
    } catch { /* */ }
    await githubApi.saveRegistry(await seal(r, pass), f?.sha ?? null, fresh ? 'registro nuevo' : 'crea el registro');
    setReg(r); setActivePass(pass); setStage('open');
    try { if (remember) localStorage.setItem(PASS_KEY, pass); } catch { /* */ }
    setPass(''); setPass2('');
    return 'Registro creado. Guarda tu clave: sin ella no se puede abrir.';
  });

  const lock = () => { try { localStorage.removeItem(PASS_KEY); } catch { /* */ } setActivePass(''); setReg({ v: 1, entries: {} }); setStage('locked'); };

  const activate = (rawCode: string, m: number, info: Partial<RegEntry> & { price?: string }) => run(async () => {
    const c = normCode(rawCode); if (!c) throw new Error('El código debe tener 8 letras o números, por ejemplo K7M4-2QXA.');
    const h = await licHash(c);
    let exp: string | null = null;
    const data = await githubApi.saveLicenses((d) => {
      const cur = d.devices[h]?.exp;
      // Renovar suma desde la fecha de vencimiento si todavía no vence; si ya venció, desde hoy.
      const base = cur && cur > today() ? new Date(cur + 'T12:00:00') : new Date();
      exp = m === 0 ? null : addMonths(base, m);
      d.devices[h] = { exp };
    }, `activa ${c.slice(0, 4)}…`);
    setLic(data);
    await changeRegistry((r) => {
      const prev = r.entries[h];
      r.entries[h] = {
        hash: h, code: c, name: (info.name ?? '').trim() || prev?.name || '', phone: (info.phone ?? '').trim() || prev?.phone,
        app: info.app ?? prev?.app ?? '', device: info.device ?? prev?.device ?? 'PC', note: prev?.note,
        first: prev?.first ?? today(), history: [...(prev?.history ?? []), { at: today(), months: m, exp, ...(info.price?.trim() ? { price: info.price.trim() } : {}) }],
      };
    }, `${prev(h) ? 'renueva' : 'activa'} un equipo`);
    setCode(''); setName(''); setPhone(''); setPrice('');
    return `✓ ${c} activado ${exp ? `hasta el ${exp}` : 'de por vida'}. El equipo se desbloquea en 1–3 minutos (o al pulsar «Ya me activaron: revisar»).`;
  });
  const prev = (h: string) => !!reg.entries[h];

  const remove = (h: string) => run(async () => {
    if (!confirm('¿Quitar la activación de este equipo? Dejará de funcionar en unos minutos.')) return '';
    setLic(await githubApi.saveLicenses((d) => { delete d.devices[h]; }, 'quita un equipo'));
    await changeRegistry((r) => { delete r.entries[h]; }, 'quita un equipo');
    return 'Activación quitada.';
  });

  const rows: Row[] = useMemo(() => {
    const list = Object.entries(lic?.devices ?? {}).map(([h, v]) => ({ h, exp: v.exp, st: statusOf(v.exp), e: reg.entries[h] ?? null }));
    const f = filter.trim().toLowerCase();
    return list.filter((r) => (show === 'all' || r.st === show || (show === 'active' && r.st === 'life'))
      && (appFilter === '*' || (r.e?.app ?? '') === appFilter)
      && (!f || [r.e?.code, r.e?.name, r.e?.phone, r.e?.note].some((x) => (x ?? '').toLowerCase().includes(f))))
      .sort((a, b) => (a.exp ?? '9999').localeCompare(b.exp ?? '9999'));
  }, [lic, reg, filter, show, appFilter]);

  const all = useMemo(() => Object.values(lic?.devices ?? {}).map((v) => statusOf(v.exp)), [lic]);
  const count = (s: Status | 'on') => all.filter((x) => (s === 'on' ? x !== 'expired' : x === s)).length;
  const month = today().slice(0, 7);
  const income = Object.values(reg.entries).flatMap((e) => e.history).filter((h) => h.at.startsWith(month)).reduce((t, h) => t + money(h.price), 0);

  const csv = () => {
    const data = [['Código', 'Cliente', 'Teléfono', 'App', 'Equipo', 'Primera activación', 'Última renovación', 'Vence', 'Estado', 'Pagos', 'Total pagado']];
    for (const r of rows) {
      const e = r.e; const last = e?.history.at(-1);
      data.push([e?.code ?? '', e?.name ?? '', e?.phone ?? '', appName(e?.app ?? ''), e?.device ?? '', e?.first ?? '', last?.at ?? '', r.exp ?? 'De por vida', STATUS[r.st].label,
        String(e?.history.length ?? 0), String(e?.history.reduce((t, h) => t + money(h.price), 0) ?? 0)]);
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([toCsv(data)], { type: 'text/csv;charset=utf-8' }));
    a.download = `activaciones-${today()}.csv`; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  };

  const remind = (r: Row) => {
    const e = r.e; if (!e?.phone) return;
    const n = e.phone.replace(/\D/g, '');
    const msg = r.st === 'expired'
      ? `Hola ${e.name || ''}, la activación de tu app (${appName(e.app)}, código ${e.code}) venció el ${r.exp}. ¿Quieres renovarla?`
      : `Hola ${e.name || ''}, la activación de tu app (${appName(e.app)}, código ${e.code}) vence el ${r.exp}. ¿Quieres renovarla para no quedarte sin servicio?`;
    window.open(`https://wa.me/${n}?text=${encodeURIComponent(msg)}`, '_blank', 'noopener');
  };

  const passFields = (confirmToo: boolean) => (
    <div className="act-form">
      <label>Clave del registro<input className="input" type="password" value={pass} onChange={(e) => setPass(e.target.value)} aria-label="Clave del registro" autoComplete="current-password" /></label>
      {confirmToo && <label>Repite la clave<input className="input" type="password" value={pass2} onChange={(e) => setPass2(e.target.value)} aria-label="Repite la clave" autoComplete="new-password" /></label>}
      <label style={{ flexDirection: 'row' as const }}><input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} /> Recordar en este equipo</label>
    </div>
  );

  if (stage === 'loading') return <div className="panel"><p className="hint">Cargando…</p>{err && <Notice kind="error">{err}</Notice>}</div>;

  if (stage !== 'open') return (
    <div className="panel">
      {stage === 'create' ? (
        <Group title="Crea tu registro de activaciones" hint="Aquí se guarda quién es cada equipo, de qué app (principal o vendedor), su teléfono, cuándo se activó, cuándo vence y cuánto pagó. Se guarda cifrado en tu repositorio: lo ves desde cualquier equipo con esta clave, y nadie más puede leerlo. Si pierdes la clave no se puede recuperar (las activaciones siguen funcionando).">
          {passFields(true)}
          <button className="btn" disabled={busy || !pass} onClick={() => void create()} id="reg-create">{busy ? 'Creando…' : 'Crear registro'}</button>
        </Group>
      ) : (
        <Group title="Abre tu registro de activaciones" hint="Escribe la clave del registro que creaste.">
          {passFields(false)}
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <button className="btn" disabled={busy || !pass} onClick={() => void unlock()} id="reg-open">{busy ? 'Abriendo…' : 'Abrir registro'}</button>
            <button className="link danger" disabled={busy} onClick={() => { if (confirm('¿Olvidaste la clave? Se empieza un registro nuevo y vacío con la clave que escribas (las activaciones existentes siguen funcionando, pero sin nombres). ¿Continuar?')) { setPass2(pass); void create(true); } }}>Olvidé la clave: empezar otro</button>
          </div>
        </Group>
      )}
      {ok && <Notice kind="ok">{ok}</Notice>}
      {err && <Notice kind="error">{err}</Notice>}
    </div>
  );

  return (
    <div className="panel">
      <div className="act-stats">
        <div><b>{count('on')}</b><span>activos</span></div>
        <div style={{ color: STATUS.soon.color }}><b>{count('soon')}</b><span>vencen en 7 días</span></div>
        <div style={{ color: STATUS.expired.color }}><b>{count('expired')}</b><span>vencidos</span></div>
        <div><b>{income ? `$${income.toLocaleString('es-MX')}` : '—'}</b><span>cobrado este mes</span></div>
      </div>

      <Group title="Activar o renovar un equipo" hint="El cliente (o vendedor) abre la app en su PC o TV, ve un código de 8 caracteres y te lo manda por WhatsApp. Tú cobras por fuera y lo activas aquí. Si el código ya existe, se renueva sumando desde su vencimiento.">
        <div className="act-form act-form-wide">
          <label>Código del equipo<input className="input" value={code} onChange={(e) => setCode(e.target.value)} placeholder="K7M4-2QXA" aria-label="Código del equipo" style={{ textTransform: 'uppercase', fontSize: 20, letterSpacing: 2 }} /></label>
          <label>Cliente<input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Juan Pérez" aria-label="Cliente" /></label>
          <label>WhatsApp<input className="input" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="52 55 1234 5678" aria-label="WhatsApp" inputMode="tel" /></label>
          <label>App
            <select className="input" value={app} onChange={(e) => setApp(e.target.value)} aria-label="App">
              <option value="">Principal</option>{vendors.map((v) => <option key={v.slug} value={v.slug}>{v.name} (vendedor)</option>)}
            </select>
          </label>
          <label>Equipo
            <select className="input" value={device} onChange={(e) => setDevice(e.target.value as Device)} aria-label="Equipo">{DEVICES.map((d) => <option key={d}>{d}</option>)}</select>
          </label>
          <label>Duración
            <select className="input" value={months} onChange={(e) => setMonths(Number(e.target.value))} aria-label="Duración">
              {DURATIONS.map((d) => <option key={d.months} value={d.months}>{d.label}</option>)}
            </select>
          </label>
          <label>Cobrado (opcional)<input className="input" value={price} onChange={(e) => setPrice(e.target.value)} placeholder="$50" aria-label="Cobrado" inputMode="decimal" /></label>
          <button className="btn" disabled={busy || !normCode(code)} onClick={() => void activate(code, months, { name, phone, app, device, price })} id="act-activate">{busy ? 'Guardando…' : 'Activar'}</button>
        </div>
        {code && !normCode(code) && <span className="hint">Faltan caracteres: el código tiene 8 (letras y números).</span>}
        {ok && <Notice kind="ok">{ok}</Notice>}
        {err && <Notice kind="error">{err}</Notice>}
      </Group>

      <Group title={`Registro de equipos (${rows.length})`} hint="Ordenados por fecha de vencimiento. «Avisar» abre WhatsApp con un mensaje listo para que renueven."
        actions={<div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn" onClick={csv} disabled={!rows.length}>Descargar Excel (CSV)</button>
          <button className="link" onClick={lock}>Cerrar registro</button>
        </div>}>
        <div className="act-filters">
          <div className="seg" role="tablist">
            {([['all', 'Todos'], ['active', 'Activos'], ['soon', 'Por vencer'], ['expired', 'Vencidos']] as const).map(([k, l]) =>
              <button key={k} className={show === k ? 'on' : ''} onClick={() => setShow(k)}>{l}</button>)}
          </div>
          <select className="input" value={appFilter} onChange={(e) => setAppFilter(e.target.value)} aria-label="Filtrar por app" style={{ maxWidth: 220 }}>
            <option value="*">Todas las apps</option><option value="">Principal</option>{vendors.map((v) => <option key={v.slug} value={v.slug}>{v.name}</option>)}
          </select>
          <input className="input" placeholder="Buscar código, cliente o teléfono" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ maxWidth: 280 }} aria-label="Buscar" />
        </div>
        {!lic ? <p className="hint">Cargando…</p> : rows.length === 0 ? <p className="hint">No hay equipos con ese filtro.</p> : (
          <div style={{ overflowX: 'auto' }}>
            <table className="act-table">
              <thead><tr><th>Código</th><th>Cliente</th><th>App · equipo</th><th>Activado</th><th>Vence</th><th>Estado</th><th></th></tr></thead>
              <tbody>
                {rows.map((r) => {
                  const e = r.e; const s = STATUS[r.st];
                  return (
                    <tr key={r.h} className={r.st === 'expired' ? 'expired' : ''}>
                      <td><code>{e?.code || '—'}</code>{!e && <span className="hint"> (sin datos en el registro)</span>}</td>
                      <td>{e?.name || <span className="hint">—</span>}{e?.phone && <div className="hint">{e.phone}</div>}</td>
                      <td>{e ? `${appName(e.app)} · ${e.device}` : '—'}</td>
                      <td>{e?.first ?? '—'}{e && e.history.length > 1 && <div className="hint">{e.history.length - 1} renovación(es)</div>}</td>
                      <td>{r.exp ?? 'De por vida'}{r.exp && r.st !== 'expired' && <div className="hint">en {daysUntil(r.exp)} días</div>}</td>
                      <td><b style={{ color: s.color, fontSize: 13 }}>{s.label}</b></td>
                      <td className="act-actions">
                        {e?.phone && (r.st === 'soon' || r.st === 'expired') && <button className="link" onClick={() => remind(r)}>Avisar</button>}
                        {e?.code && <select className="input" aria-label="Renovar" value="" disabled={busy} onChange={(ev) => { if (ev.target.value) void activate(e.code, Number(ev.target.value), {}); }}>
                          <option value="">Renovar…</option>{DURATIONS.map((d) => <option key={d.months} value={d.months}>{d.label}</option>)}
                        </select>}
                        <button className="link danger" disabled={busy} onClick={() => void remove(r.h)}>Quitar</button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Group>
    </div>
  );
}
