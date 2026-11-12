// Activaciones: el cliente o vendedor te manda el código que ve en su TV/PC y tú lo activas aquí.
// El cobro es por fuera (efectivo, transferencia…). En el archivo público solo queda un resumen del
// código (no el código) y la fecha de vencimiento; los nombres/notas quedan solo en este navegador.
import { useEffect, useMemo, useState } from 'react';
import { githubApi, type Licenses } from '../api-github';
import { Group, Notice } from './ui';

const NOTES_KEY = 'ev.studio.activations';
type Note = { code: string; note: string; at: string };
const loadNotes = (): Record<string, Note> => { try { return JSON.parse(localStorage.getItem(NOTES_KEY) ?? '{}'); } catch { return {}; } };
const saveNotes = (n: Record<string, Note>) => { try { localStorage.setItem(NOTES_KEY, JSON.stringify(n)); } catch { /* */ } };

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
const addMonths = (from: Date, m: number) => { const d = new Date(from); d.setMonth(d.getMonth() + m); return d.toISOString().slice(0, 10); };
const today = () => new Date().toISOString().slice(0, 10);

export function ActivationsPanel() {
  const [lic, setLic] = useState<Licenses | null>(null);
  const [err, setErr] = useState(''); const [ok, setOk] = useState('');
  const [busy, setBusy] = useState(false);
  const [code, setCode] = useState(''); const [note, setNote] = useState(''); const [months, setMonths] = useState(12);
  const [notes, setNotes] = useState(loadNotes);
  const [filter, setFilter] = useState('');

  const reload = async () => { try { setLic((await githubApi.licenses()).data); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } };
  useEffect(() => { void reload(); }, []);

  const run = async (fn: () => Promise<string>) => {
    setBusy(true); setErr(''); setOk('');
    try { setOk(await fn()); } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); }
  };

  const activate = (rawCode: string, m: number, n: string) => run(async () => {
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
    const nn = { ...notes, [h]: { code: c, note: n.trim() || notes[h]?.note || '', at: today() } }; setNotes(nn); saveNotes(nn);
    setCode(''); setNote('');
    return `✓ ${c} activado ${exp ? `hasta el ${exp}` : 'de por vida'}. El equipo se desbloquea en 1–3 minutos (o al pulsar «Ya me activaron: revisar»).`;
  });

  const remove = (h: string) => run(async () => {
    if (!confirm('¿Quitar la activación de este equipo? Dejará de funcionar en unos minutos.')) return '';
    const data = await githubApi.saveLicenses((d) => { delete d.devices[h]; }, 'quita un equipo');
    setLic(data); return 'Activación quitada.';
  });

  const rows = useMemo(() => {
    const list = Object.entries(lic?.devices ?? {}).map(([h, v]) => ({ h, exp: v.exp, code: notes[h]?.code ?? '', note: notes[h]?.note ?? '' }));
    const f = filter.trim().toLowerCase();
    return list.filter((r) => !f || r.code.toLowerCase().includes(f) || r.note.toLowerCase().includes(f))
      .sort((a, b) => (a.exp ?? '9999') .localeCompare(b.exp ?? '9999'));
  }, [lic, notes, filter]);
  const active = rows.filter((r) => !r.exp || r.exp >= today()).length;

  return (
    <div className="panel">
      <Group title="Activar un equipo" hint="El cliente (o vendedor) abre la app en su TV o PC, ve un código de 8 caracteres y te lo manda por WhatsApp. Tú cobras por fuera y lo activas aquí.">
        <div className="act-form">
          <label>Código del equipo<input className="input" value={code} onChange={(e) => setCode(e.target.value)} placeholder="K7M4-2QXA" aria-label="Código del equipo" style={{ textTransform: 'uppercase', fontSize: 20, letterSpacing: 2 }} /></label>
          <label>Nombre o nota (solo la ves tú)<input className="input" value={note} onChange={(e) => setNote(e.target.value)} placeholder="Ej.: Juan Pérez · vendedor Nandtv" aria-label="Nota" /></label>
          <label>Duración
            <select className="input" value={months} onChange={(e) => setMonths(Number(e.target.value))} aria-label="Duración">
              {DURATIONS.map((d) => <option key={d.months} value={d.months}>{d.label}</option>)}
            </select>
          </label>
          <button className="btn" disabled={busy || !normCode(code)} onClick={() => void activate(code, months, note)}>{busy ? 'Guardando…' : 'Activar'}</button>
        </div>
        {code && !normCode(code) && <span className="hint">Faltan caracteres: el código tiene 8 (letras y números).</span>}
        {ok && <Notice kind="ok">{ok}</Notice>}
        {err && <Notice kind="error">{err}</Notice>}
      </Group>

      <Group title={`Equipos activados (${active} activos de ${rows.length})`} hint="Los nombres y notas se guardan solo en este navegador. Si entras desde otro equipo verás «código no guardado aquí», pero la activación sigue funcionando."
        actions={<input className="input" placeholder="Buscar código o nombre" value={filter} onChange={(e) => setFilter(e.target.value)} style={{ maxWidth: 240 }} aria-label="Buscar" />}>
        {!lic ? <p className="hint">Cargando…</p> : rows.length === 0 ? <p className="hint">Todavía no hay equipos activados.</p> : (
          <table className="act-table">
            <thead><tr><th>Código</th><th>Nombre / nota</th><th>Vence</th><th></th></tr></thead>
            <tbody>
              {rows.map((r) => {
                const expired = !!r.exp && r.exp < today();
                return (
                  <tr key={r.h} className={expired ? 'expired' : ''}>
                    <td><code>{r.code || '—'}</code>{!r.code && <span className="hint"> (código no guardado aquí)</span>}</td>
                    <td>{r.note || <span className="hint">—</span>}</td>
                    <td>{r.exp ? <>{r.exp}{expired && <b className="tag-exp"> vencido</b>}</> : 'De por vida'}</td>
                    <td className="act-actions">
                      {r.code && <select className="input" aria-label="Renovar" value="" disabled={busy} onChange={(e) => { if (e.target.value) void activate(r.code, Number(e.target.value), r.note); }}>
                        <option value="">Renovar…</option>{DURATIONS.map((d) => <option key={d.months} value={d.months}>{d.label}</option>)}
                      </select>}
                      <button className="link danger" disabled={busy} onClick={() => void remove(r.h)}>Quitar</button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Group>
    </div>
  );
}
