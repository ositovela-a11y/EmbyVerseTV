import { useEffect, useMemo, useState } from 'react';
import { Clock, Fx, Img, Spinner, useApp, useLoad } from '../ui';
import { categories, liveIn, client } from '../catalog';
import { store } from '../store';
import type { EpgItem, Live as Ch } from '../xtream';

const FAV = '__fav'; const HIST = '__hist';

export function Live({ cat }: { cat?: string }) {
  const app = useApp();
  const [folder, setFolder] = useState<string>(cat ?? '');
  const [q, setQ] = useState('');
  const cats = useLoad(() => categories('live'), [app.profile.id]);
  const list = useLoad<Ch[]>(async () => {
    if (folder === FAV || folder === HIST) {
      const all = await liveIn();
      const ids = folder === FAV ? store.favs(app.profile.id).filter((f) => f.kind === 'live').map((f) => f.id) : store.lastLive(app.profile.id);
      return ids.map((id) => all.find((c) => c.id === id)).filter(Boolean) as Ch[];
    }
    return liveIn(folder || undefined);
  }, [folder, app.profile.id]);
  const shown = useMemo(() => {
    const l = list.data ?? []; const n = q.trim().toLowerCase();
    return (n ? l.filter((c) => c.name.toLowerCase().includes(n)) : l).slice(0, 600);
  }, [list.data, q]);
  const [sel, setSel] = useState<Ch | null>(null);
  const [epg, setEpg] = useState<EpgItem[]>([]);
  useEffect(() => {
    if (!sel) return; setEpg([]);
    const t = setTimeout(() => client().shortEpg(sel.id, 4).then(setEpg), 350);
    return () => clearTimeout(t);
  }, [sel?.id]);
  const play = (c: Ch) => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo, list: shown.map((x) => ({ id: x.id, title: x.name, image: x.logo, num: x.num })) });
  const now = Date.now();
  const cur = epg.find((e) => e.start <= now && e.end > now); const next = epg.find((e) => e.start > now);
  const fmt = (ms: number) => new Date(ms).toLocaleTimeString('es-MX', { hour: 'numeric', minute: '2-digit' });

  return (
    <div className="screen live">
      <div className="live-head"><h1>TV en vivo</h1><Clock />
        <div className="grow" />
        <Fx className="btn" onClick={() => app.go({ name: 'guide' })}>📅 Guía de TV</Fx>
        <SearchBox value={q} onChange={setQ} placeholder="Buscar canal" />
      </div>
      <div className="live-body">
        <div className="folders" data-fx-group="">
          <Fx className={`folder ${folder === FAV ? 'sel' : ''}`} onClick={() => setFolder(FAV)}>❤ Favoritos</Fx>
          <Fx className={`folder ${folder === HIST ? 'sel' : ''}`} onClick={() => setFolder(HIST)}>↺ Historial</Fx>
          <Fx className={`folder ${folder === '' ? 'sel' : ''}`} def={!cat} onClick={() => setFolder('')}>Todas</Fx>
          {(cats.data ?? []).map((c) => <Fx key={c.id} className={`folder ${folder === c.id ? 'sel' : ''}`} def={cat === c.id} onClick={() => setFolder(c.id)}>{c.name}</Fx>)}
        </div>
        <div className="channels" data-fx-group="">
          {list.loading && <Spinner />}
          {!list.loading && shown.length === 0 && <div className="empty">Sin canales en esta carpeta.</div>}
          {shown.map((c, i) => (
            <Fx key={c.id} className="channel" onFocus={() => setSel(c)} onClick={() => play(c)}>
              <span className="ch-num">{c.num ?? i + 1}</span>
              <Img src={c.logo} alt={c.name} className="ch-logo" fit="contain" />
              <span className="ch-name">{c.name}</span>
              {store.isFav(app.profile.id, 'live', c.id) && <span className="ch-fav">❤</span>}
            </Fx>
          ))}
        </div>
        <div className="live-info">
          {sel ? <>
            <Img src={sel.logo} alt={sel.name} className="info-logo" fit="contain" />
            <h2>{sel.name}</h2>
            {cur ? <div className="now"><small>AHORA · {fmt(cur.start)}–{fmt(cur.end)}</small><b>{cur.title}</b>
              <div className="bar"><div style={{ width: `${Math.min(100, ((now - cur.start) / (cur.end - cur.start)) * 100)}%` }} /></div>
              {cur.desc && <p>{cur.desc}</p>}</div> : <p className="muted">Sin guía para este canal.</p>}
            {next && <div className="next"><small>SIGUE · {fmt(next.start)}</small><b>{next.title}</b></div>}
            <p className="muted hint">OK para ver · ▲▼ para cambiar de canal en pantalla completa</p>
          </> : <p className="muted">Elige un canal para ver qué están pasando.</p>}
        </div>
      </div>
    </div>
  );
}

/** Caja de búsqueda: al pulsar OK se abre el teclado de la TV. */
export function SearchBox({ value, onChange, placeholder, def }: { value: string; onChange: (v: string) => void; placeholder: string; def?: boolean }) {
  return (
    <Fx className="searchbox" def={def} onClick={() => (document.getElementById(`in-${placeholder}`) as HTMLInputElement | null)?.focus()}>
      <span>🔍</span>
      <input id={`in-${placeholder}`} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => { const k = e.keyCode; if (k === 13 || k === 40 || k === 38 || k === 10009 || k === 461 || k === 27) { (e.target as HTMLInputElement).blur(); if (k !== 13) e.preventDefault(); return; } e.stopPropagation(); }} />
    </Fx>
  );
}
