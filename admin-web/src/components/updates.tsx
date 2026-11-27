// Actualizaciones: las versiones nuevas de las apps se compilan solas, pero NO llegan a los clientes hasta que
// pulsas «Actualizar versión». Android va por app (la tuya y la de cada vendedor); TV (Samsung/LG) y PC son una
// sola versión para todas las marcas. El sitio publica lo aprobado en unos 5 minutos.
import { useEffect, useState } from 'react';
import { githubApi, type Approvals, type VersionStatus, type Ver } from '../api-github';
import { Group, Notice } from './ui';

const vtxt = (v: Ver) => (v ? `${v.name}${v.code ? ` (${v.code})` : ''}` : '—');

export function UpdatesPanel() {
  const [st, setSt] = useState<VersionStatus | null | undefined>(undefined);
  const [appr, setAppr] = useState<Approvals | null>(null);
  const [busy, setBusy] = useState(''); const [err, setErr] = useState(''); const [ok, setOk] = useState('');

  const load = async () => {
    setErr('');
    const [s, a] = await Promise.all([githubApi.versionStatus(), githubApi.approvals().then((r) => r.data).catch(() => null)]);
    setSt(s); setAppr(a);
  };
  useEffect(() => { void load(); }, []);

  const run = async (key: string, question: string, change: (d: Approvals) => void, message: string) => {
    if (!window.confirm(question)) return;
    setBusy(key); setErr(''); setOk('');
    try {
      setAppr(await githubApi.approveRelease(change, message));
      setOk('Listo. En unos 5 minutos la versión nueva queda publicada; las apps la ofrecen al abrirse.');
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); } finally { setBusy(''); }
  };

  if (st === undefined) return <p className="hint">Cargando versiones…</p>;
  if (st === null) return <Notice kind="info">Todavía no hay información de versiones. Aparece después de la próxima compilación de las apps.</Notice>;

  const waitingAndroid = st.android.filter((a) => a.pendiente && a.nueva);
  const approvedAndroid = (asset: string, code?: number) => code != null && appr?.android?.[asset] === code;
  const state = (pend: boolean, approved: boolean) => !pend ? <span className="upd-ok">Al día</span>
    : approved ? <span className="upd-going">Aprobada · publicando…</span> : <span className="upd-wait">Esperando tu aprobación</span>;

  return (
    <div className="updates" id="updates-panel">
      <p className="hint">Las versiones nuevas no les llegan a tus clientes hasta que pulsas <b>Actualizar versión</b>.
        Puedes aprobar solo tu app, solo algunos vendedores o todas. Última revisión: {new Date(st.updated).toLocaleString('es-MX', { dateStyle: 'medium', timeStyle: 'short' })}</p>
      {err && <Notice kind="error">{err}</Notice>}
      {ok && <Notice kind="ok">{ok}</Notice>}

      <Group title="Android (celular, tableta, Android TV y Fire TV)" hint="Una app por marca. Cada una se actualiza por separado."
        actions={waitingAndroid.length > 1 ? (
          <button className="btn primary" disabled={!!busy} id="upd-android-all"
            onClick={() => run('android-all', `¿Mandar la versión nueva a las ${waitingAndroid.length} apps de Android?`,
              (d) => { for (const a of waitingAndroid) d.android[a.asset] = a.nueva!.code!; }, `Android a todas (${waitingAndroid.length})`)}>
            Actualizar todas</button>) : undefined}>
        <table className="upd-table">
          <thead><tr><th>App</th><th>Tus clientes tienen</th><th>Versión nueva</th><th>Estado</th><th /></tr></thead>
          <tbody>
            {st.android.map((a) => {
              const approved = approvedAndroid(a.asset, a.nueva?.code);
              return (
                <tr key={a.asset} data-asset={a.asset}>
                  <td><b>{a.slug ? a.name : 'Tu app (EmbyVerse)'}</b>{a.slug ? <span className="hint"> · vendedor</span> : null}</td>
                  <td><span className="upd-lbl">Tienen </span>{vtxt(a.live)}</td>
                  <td>{a.pendiente ? <><span className="upd-lbl">Nueva </span>{vtxt(a.nueva)}</> : <span className="upd-nolbl">—</span>}</td>
                  <td>{state(a.pendiente, approved)}</td>
                  <td>{a.pendiente && a.nueva && !approved && (
                    <button className="btn" disabled={!!busy} data-approve={a.asset}
                      onClick={() => run(a.asset, `¿Mandar la versión ${a.nueva!.name} a los clientes de ${a.slug ? a.name : 'tu app'}?`,
                        (d) => { d.android[a.asset] = a.nueva!.code!; }, `Android ${a.nueva!.name} para ${a.slug || 'la app principal'}`)}>
                      {busy === a.asset ? 'Aprobando…' : 'Actualizar versión'}</button>)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Group>

      {(['tv', 'pc'] as const).map((k) => {
        const x = st[k]; const approved = !!x.nueva && appr?.[k] === x.nueva.name;
        return (
          <Group key={k} title={k === 'tv' ? 'Smart TV (Samsung y LG)' : 'PC (Windows)'}
            hint={k === 'tv' ? 'Una sola versión para todas las marcas: las TVs instaladas se actualizan solas al abrir la app.'
              : 'Una sola versión para todas las marcas: cambia los instaladores que se descargan del sitio.'}>
            <div className="upd-row" data-kind={k}>
              <span>Tus clientes tienen <b>{vtxt(x.live)}</b></span>
              {x.pendiente && <span>Versión nueva <b>{vtxt(x.nueva)}</b></span>}
              {state(x.pendiente, approved)}
              {x.pendiente && x.nueva && !approved && (
                <button className="btn" disabled={!!busy} data-approve={k}
                  onClick={() => run(k, `¿Mandar la versión ${x.nueva!.name} de ${k === 'tv' ? 'Smart TV' : 'PC'} a todos los clientes?`,
                    (d) => { d[k] = x.nueva!.name; }, `${k.toUpperCase()} ${x.nueva!.name}`)}>
                  {busy === k ? 'Aprobando…' : 'Actualizar versión'}</button>)}
            </div>
          </Group>
        );
      })}
      <button className="btn ghost" onClick={() => void load()} disabled={!!busy}>Revisar otra vez</button>
    </div>
  );
}
