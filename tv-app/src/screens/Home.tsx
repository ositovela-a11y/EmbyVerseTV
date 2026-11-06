import { useEffect, useMemo, useState } from 'react';
import { Card, Fx, Img, Row, Spinner, useApp, useLoad } from '../ui';
import { categories, newestMovies, allShows, sagas, todayEvents, liveIn } from '../catalog';
import { isActive } from '../config';
import { store } from '../store';
import * as nav from '../nav';

export function Home() {
  const app = useApp();
  const { config, profile } = app;
  const news = useLoad(() => newestMovies(30), [profile.id]);
  const shows = useLoad(async () => (await allShows()).slice().sort((a, b) => (b.year ?? 0) - (a.year ?? 0)).slice(0, 30), [profile.id]);
  const events = useLoad(() => todayEvents(), [profile.id]);
  const sg = useLoad(() => sagas(16), [profile.id]);
  const featured = useLoad(async () => {
    const keys = (config.texts['home.channels'] ?? '').split(',').map((k) => k.trim().toLowerCase()).filter((k) => k.length >= 2);
    if (!keys.length) return [];
    const all = await liveIn();
    return keys.flatMap((k) => all.filter((c) => c.name.toLowerCase().includes(k)).slice(0, 6)).filter((c, i, a) => a.findIndex((o) => o.id === c.id) === i).slice(0, 20);
  }, [config.texts['home.channels'], profile.id]);
  const cats = useLoad(() => categories('vod'), [profile.id]);
  const cont = useMemo(() => store.progress(profile.id).filter((p) => p.dur > 0 && p.pos / p.dur < 0.95 && p.pos > 15000), [profile.id]);
  const msgs = config.messages.filter((m) => isActive(m));
  const [hero, setHero] = useState(0);
  const heroItems = (news.data ?? []).slice(0, 8);
  useEffect(() => { if (!heroItems.length) return; const i = setInterval(() => setHero((h) => (h + 1) % heroItems.length), 7000); return () => clearInterval(i); }, [heroItems.length]);
  const h = heroItems[hero % Math.max(1, heroItems.length)];
  const ev = featuredEvent(config.texts);
  // Al llegar el catálogo, el foco pasa al banner (si seguía en el menú).
  useEffect(() => { if (h && (!nav.focused() || nav.focused()!.closest('.menu'))) nav.focusFirst(); }, [!!h]);

  return (
    <div className="screen home">
      {profile.kids && <div className="kids-hello"><div><b>¡Hola, {profile.name}! ¿Qué vemos hoy?</b><span>Caricaturas, películas y series para ti</span></div><div className="kids-emoji">🦄 🐻 🚀</div></div>}
      {msgs.slice(0, 2).map((m) => <Fx key={m.id} className="msg-strip" onClick={() => app.toast(m.body)}><span>📢</span><div><b>{m.title}</b><small>{m.body}</small></div></Fx>)}
      {h && (
        <div className="hero">
          <Img src={h.poster} alt={h.name} className="hero-bg" />
          <div className="hero-fade" />
          <div className="hero-info">
            <span className="pill">NOVEDAD</span>
            <h1>{h.name}</h1>
            <p>{[h.year, h.rating ? `★ ${h.rating.toFixed(1)}` : null, h.genre].filter(Boolean).join('  ·  ')}</p>
            <div className="hero-btns">
              <Fx className="btn primary" def onClick={() => app.go({ name: 'movie', id: h.id })}>▶ Ver ahora</Fx>
              <Fx className="btn" onClick={() => setHero((x) => (x + 1) % heroItems.length)}>Siguiente ›</Fx>
            </div>
          </div>
          <div className="hero-dots">{heroItems.map((_, i) => <i key={i} className={i === hero % heroItems.length ? 'on' : ''} />)}</div>
        </div>
      )}
      {ev && <EventCard ev={ev} />}
      {cont.length > 0 && (
        <Row title="Continuar viendo">
          {cont.map((p) => <Card key={p.kind + p.id} wide title={p.title} image={p.image} progress={p.pos / p.dur}
            sub={p.kind === 'episode' ? `T${p.season} · E${p.episode}` : undefined}
            onClick={() => app.play({ kind: p.kind, id: p.id, title: p.title, image: p.image, ext: p.ext, seriesId: p.seriesId, season: p.season, episode: p.episode, startMs: p.pos })} />)}
        </Row>
      )}
      {(featured.data ?? []).length > 0 && (
        <Row title={config.texts['home.channelsTitle'] || 'Canales destacados'}>
          {featured.data!.map((c) => <Card key={c.id} wide title={c.name} image={c.logo} onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo, list: featured.data!.map((x) => ({ id: x.id, title: x.name, image: x.logo, num: x.num })) })} />)}
        </Row>
      )}
      {(events.data ?? []).length > 0 && (
        <Row title="⚽ Partidos y eventos de hoy">
          {events.data!.map((c) => <Card key={c.id} wide title={c.name} image={c.logo} badge="EN VIVO" onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo, list: events.data!.map((x) => ({ id: x.id, title: x.name, image: x.logo, num: x.num })) })} />)}
        </Row>
      )}
      {news.loading && <Spinner label="Cargando tu catálogo…" />}
      {news.error && <div className="error-box">No se pudo cargar el catálogo: {news.error}</div>}
      {(news.data ?? []).length > 0 && (
        <Row title="Películas: lo más nuevo">
          {news.data!.map((m) => <Card key={m.id} title={m.name} image={m.poster} sub={m.year ? String(m.year) : undefined} onClick={() => app.go({ name: 'movie', id: m.id })} />)}
        </Row>
      )}
      {(sg.data ?? []).length > 0 && (
        <Row title="Sagas">
          {sg.data!.map((s) => <Card key={s.key} title={s.name} image={s.poster} badge={String(s.items.length)} onClick={() => app.go({ name: 'saga', key: s.key })} />)}
        </Row>
      )}
      {(shows.data ?? []).length > 0 && (
        <Row title="Series">
          {shows.data!.map((s) => <Card key={s.id} title={s.name} image={s.poster} sub={s.year ? String(s.year) : undefined} onClick={() => app.go({ name: 'show', id: s.id })} />)}
        </Row>
      )}
      {(cats.data ?? []).length > 0 && (
        <Row title="Colecciones">
          {cats.data!.slice(0, 16).map((c, i) => <Fx key={c.id} className="coll" style={{ background: `linear-gradient(135deg, hsl(${(i * 47) % 360},65%,45%), hsl(${(i * 47 + 60) % 360},70%,18%))` }}
            onClick={() => app.go({ name: 'movies', cat: c.id })}>{c.name.replace(/^[A-Z]{2,3}\s*[|:-]\s*/, '')}</Fx>)}
        </Row>
      )}
    </div>
  );
}

interface Ev { title: string; subtitle?: string; start: number; image?: string; keywords: string[] }
function featuredEvent(t: Record<string, string>): Ev | null {
  const title = t['event.title']?.trim(); const start = Date.parse(t['event.start'] ?? '');
  if (!title || !Number.isFinite(start) || Date.now() > start + 3 * 3600_000) return null;
  return { title, subtitle: t['event.subtitle'], start, image: /^https:\/\//.test(t['event.image'] ?? '') ? t['event.image'] : undefined,
    keywords: (t['event.channels'] ?? '').split(',').map((k) => k.trim().toLowerCase()).filter((k) => k.length >= 2) };
}

function EventCard({ ev }: { ev: Ev }) {
  const app = useApp();
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const i = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(i); }, []);
  const ch = useLoad(async () => { const all = await liveIn(); return ev.keywords.flatMap((k) => all.filter((c) => c.name.toLowerCase().includes(k)).slice(0, 6)).slice(0, 12); }, [ev.title]);
  const left = Math.max(0, Math.floor((ev.start - now) / 1000)); const live = now >= ev.start;
  const parts = [[Math.floor(left / 86400), 'días'], [Math.floor((left % 86400) / 3600), 'horas'], [Math.floor((left % 3600) / 60), 'min'], [left % 60, 'seg']] as [number, string][];
  return (
    <div className="event-card">
      {ev.image && <Img src={ev.image} alt={ev.title} className="event-bg" />}
      <div className="event-info"><span className={`pill ${live ? 'live' : ''}`}>{live ? '● EN VIVO AHORA' : 'EVENTO DESTACADO'}</span><h2>{ev.title}</h2>{ev.subtitle && <p>{ev.subtitle}</p>}</div>
      {!live && <div className="countdown">{parts.filter((p, i) => i > 0 || p[0] > 0).map(([v, l]) => <div key={l}><b>{String(v).padStart(2, '0')}</b><small>{l}</small></div>)}</div>}
      {(ch.data ?? []).length > 0 && <div className="row-scroll event-ch" data-fx-group="">{ch.data!.map((c) => <Fx key={c.id} className="chip-ch" onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo })}>▶ {c.name}</Fx>)}</div>}
    </div>
  );
}
