import { useMemo, useState } from 'react';
import { Card, Fx, Img, Spinner, useApp, useLoad } from '../ui';
import { categories, vodIn, seriesIn, client, sagas } from '../catalog';
import { store } from '../store';
import type { Episode } from '../xtream';
import { SearchBox } from './Live';

const NEW = '__new';

export function Library({ kind, cat }: { kind: 'movies' | 'series'; cat?: string }) {
  const app = useApp();
  const [folder, setFolder] = useState(cat ?? NEW);
  const [q, setQ] = useState('');
  const cats = useLoad(() => categories(kind === 'movies' ? 'vod' : 'series'), [kind, app.profile.id]);
  const items = useLoad<any[]>(async () => {
    const all = kind === 'movies' ? await vodIn(folder === NEW ? undefined : folder) : await seriesIn(folder === NEW ? undefined : folder);
    if (folder !== NEW) return all;
    return all.slice().sort((a: any, b: any) => (kind === 'movies' ? (b.added ?? 0) - (a.added ?? 0) : (b.year ?? 0) - (a.year ?? 0))).slice(0, 300);
  }, [kind, folder, app.profile.id]);
  const shown = useMemo(() => { const n = q.trim().toLowerCase(); const l = items.data ?? []; return (n ? l.filter((m) => m.name.toLowerCase().includes(n)) : l).slice(0, 1500); }, [items.data, q]);
  // Se dibuja de a poco (las TVs tienen poca memoria): 60 al inicio y más al bajar.
  const [limit, setLimit] = useState(60);
  return (
    <div className="screen library">
      <div className="lib-head"><h1>{kind === 'movies' ? 'Películas' : 'Series'}</h1><div className="grow" /><SearchBox value={q} onChange={setQ} placeholder={`Buscar en ${kind === 'movies' ? 'películas' : 'series'}`} /></div>
      <div className="folder-row row-scroll" data-fx-group="">
        <Fx className={`chip ${folder === NEW ? 'sel' : ''}`} def={!cat} onClick={() => { setFolder(NEW); setLimit(60); }}>★ Lo más nuevo</Fx>
        {(cats.data ?? []).map((c) => <Fx key={c.id} className={`chip ${folder === c.id ? 'sel' : ''}`} def={cat === c.id} onClick={() => { setFolder(c.id); setLimit(60); }}>{c.name}</Fx>)}
      </div>
      {items.loading ? <Spinner /> : items.error ? <div className="error-box">{items.error}</div> : (
        <div className="grid" data-fx-group="">
          {shown.slice(0, limit).map((m, i) => (
            <span key={m.id} onFocus={() => { if (i > limit - 14) setLimit((l) => l + 60); }}>
              <Card title={m.name} image={m.poster} sub={m.year ? String(m.year) : undefined}
                onClick={() => app.go(kind === 'movies' ? { name: 'movie', id: m.id } : { name: 'show', id: m.id })} />
            </span>
          ))}
          {shown.length > limit && <Fx className="more" onClick={() => setLimit((l) => l + 120)}>Ver más ({shown.length - limit})</Fx>}
          {shown.length === 0 && <div className="empty">No hay títulos aquí.</div>}
        </div>
      )}
    </div>
  );
}

const ageColor: Record<string, string> = { AA: '#2E7D32', A: '#2E7D32', B: '#1565C0', B15: '#EF6C00', C: '#C62828', D: '#C62828' };

export function MovieDetail({ id }: { id: string }) {
  const app = useApp();
  const base = useLoad(async () => (await vodIn()).find((m) => m.id === id) ?? null, [id]);
  const info = useLoad(() => client().movieInfo(id), [id]);
  const m = base.data; const i = info.data;
  const [fav, setFav] = useState(() => store.isFav(app.profile.id, 'movie', id));
  const prog = store.progress(app.profile.id).find((p) => p.kind === 'movie' && p.id === id);
  if (!m) return base.loading ? <Spinner /> : <div className="error-box">No se encontró la película.</div>;
  const play = (startMs?: number) => app.play({ kind: 'movie', id, title: m.name, image: i?.backdrop ?? m.poster, ext: m.ext, startMs });
  return (
    <div className="screen detail">
      <Img src={i?.backdrop ?? m.poster} alt={m.name} className="detail-bg kenburns" />
      <div className="detail-fade" />
      <div className="detail-body">
        <Img src={m.poster} alt={m.name} className="detail-poster" />
        <div className="detail-info">
          <h1>{m.name}</h1>
          <p className="meta">{i?.age && <span className="age" style={{ background: ageColor[i.age] ?? '#555' }}>{i.age}</span>}
            {[m.year, i?.duration ? `${Math.round(i.duration / 60)} min` : null, m.rating ? `★ ${m.rating.toFixed(1)}` : null, i?.genre ?? m.genre].filter(Boolean).join('  ·  ')}</p>
          {i?.plot && <p className="plot">{i.plot}</p>}
          {i?.cast && <p className="muted small">Reparto: {i.cast}</p>}
          <div className="detail-btns" data-fx-group="">
            {prog && prog.pos > 15000 && <Fx className="btn primary" def onClick={() => play(prog.pos)}>▶ Continuar ({Math.round(prog.pos / 60000)} min)</Fx>}
            <Fx className={`btn ${prog ? '' : 'primary'}`} def={!prog} onClick={() => play()}>{prog ? '↺ Desde el inicio' : '▶ Ver ahora'}</Fx>
            <Fx className="btn" onClick={() => setFav(store.toggleFav(app.profile.id, { kind: 'movie', id, title: m.name, image: m.poster }))}>{fav ? '❤ En favoritos' : '♡ Favorito'}</Fx>
          </div>
        </div>
      </div>
    </div>
  );
}

export function ShowDetail({ id }: { id: string }) {
  const app = useApp();
  const base = useLoad(async () => (await seriesIn()).find((s) => s.id === id) ?? null, [id]);
  const eps = useLoad(() => client().episodes(id), [id]);
  const seasons = useMemo(() => Array.from(new Set((eps.data?.episodes ?? []).map((e) => e.season))), [eps.data]);
  const [season, setSeason] = useState<number | null>(null);
  const s = base.data; const cur = season ?? seasons[0];
  const list = (eps.data?.episodes ?? []).filter((e) => e.season === cur);
  const [fav, setFav] = useState(() => store.isFav(app.profile.id, 'series', id));
  const last = store.progress(app.profile.id).find((p) => p.seriesId === id);
  if (!s) return base.loading ? <Spinner /> : <div className="error-box">No se encontró la serie.</div>;
  const all = eps.data?.episodes ?? [];
  const playEp = (e: Episode, startMs?: number) => app.play({ kind: 'episode', id: e.id, title: s.name, subtitle: `T${e.season} · E${e.episode} · ${e.title}`, image: e.image ?? s.backdrop ?? s.poster,
    ext: e.ext, seriesId: id, season: e.season, episode: e.episode, startMs, list: all.map((x) => ({ id: x.id, title: x.title, image: x.image, ext: x.ext, season: x.season, episode: x.episode })) });
  const resume = last ? all.find((e) => e.id === last.id) : undefined;
  return (
    <div className="screen detail">
      <Img src={eps.data?.backdrop ?? s.backdrop ?? s.poster} alt={s.name} className="detail-bg kenburns" />
      <div className="detail-fade" />
      <div className="detail-body show">
        <div className="detail-info">
          <h1>{s.name}</h1>
          <p className="meta">{[s.year, s.rating ? `★ ${s.rating.toFixed(1)}` : null, s.genre, seasons.length ? `${seasons.length} temporada${seasons.length > 1 ? 's' : ''}` : null].filter(Boolean).join('  ·  ')}</p>
          {(eps.data?.plot ?? s.plot) && <p className="plot">{eps.data?.plot ?? s.plot}</p>}
          <div className="detail-btns" data-fx-group="">
            {all.length > 0 && <Fx className="btn primary" def onClick={() => resume ? playEp(resume, last!.dur && last!.pos / last!.dur < 0.95 ? last!.pos : undefined) : playEp(all[0])}>
              ▶ {resume ? `Continuar T${resume.season}·E${resume.episode}` : 'Ver ahora'}</Fx>}
            <Fx className="btn" onClick={() => setFav(store.toggleFav(app.profile.id, { kind: 'series', id, title: s.name, image: s.poster }))}>{fav ? '❤ En favoritos' : '♡ Favorito'}</Fx>
          </div>
          {eps.loading && <Spinner label="Cargando episodios…" />}
          {!eps.loading && all.length === 0 && <p className="muted">Esta serie todavía no tiene episodios disponibles.</p>}
          {seasons.length > 1 && <div className="row-scroll seasons" data-fx-group="">{seasons.map((n) => <Fx key={n} className={`chip ${n === cur ? 'sel' : ''}`} onClick={() => setSeason(n)}>Temporada {n}</Fx>)}</div>}
          <div className="row-scroll episodes" data-fx-group="">
            {list.map((e) => <Card key={e.id} wide title={`${e.episode}. ${e.title}`} image={e.image ?? s.backdrop} sub={e.duration ? `${Math.round(e.duration / 60)} min` : undefined} onClick={() => playEp(e)} />)}
          </div>
        </div>
      </div>
    </div>
  );
}

export function Saga({ k }: { k: string }) {
  const app = useApp();
  const s = useLoad(async () => (await sagas(200)).find((x) => x.key === k) ?? null, [k]);
  return (
    <div className="screen library">
      <div className="lib-head"><h1>{s.data?.name ?? 'Saga'}</h1></div>
      {s.loading ? <Spinner /> : <div className="grid" data-fx-group="">{(s.data?.items ?? []).map((m, i) => <Card key={m.id} def={i === 0} title={m.name} image={m.poster} sub={m.year ? String(m.year) : undefined} onClick={() => app.go({ name: 'movie', id: m.id })} />)}</div>}
    </div>
  );
}
