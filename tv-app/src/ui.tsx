// Componentes compartidos: elementos enfocables, tarjetas, filas, imágenes con respaldo y el contexto de la app.
import { createContext, useContext, useEffect, useRef, useState, type ReactNode, type CSSProperties } from 'react';
import type { RemoteConfig } from './config';
import type { Profile } from './store';

export type Route =
  | { name: 'home' } | { name: 'live'; cat?: string } | { name: 'movies'; cat?: string } | { name: 'series'; cat?: string }
  | { name: 'movie'; id: string } | { name: 'show'; id: string } | { name: 'search' } | { name: 'favorites' } | { name: 'settings' }
  | { name: 'saga'; key: string } | { name: 'profiles' } | { name: 'guide' };

export interface PlayRequest {
  kind: 'live' | 'movie' | 'episode'; id: string; title: string; subtitle?: string; image?: string | null; ext?: string;
  seriesId?: string; season?: number; episode?: number; startMs?: number;
  /** Lista para cambiar de canal (▲▼) o siguiente episodio. */
  list?: { id: string; title: string; image?: string | null; ext?: string; num?: number; season?: number; episode?: number }[];
}

export interface AppCtx {
  config: RemoteConfig;
  profile: Profile;
  go(r: Route): void;
  back(): void;
  play(p: PlayRequest): void;
  toast(msg: string): void;
  refreshProfile(): void;
}
export const Ctx = createContext<AppCtx>(null as any);
export const useApp = () => useContext(Ctx);

/** Elemento que se puede seleccionar con el control (OK = clic). */
export function Fx(props: { children?: ReactNode; className?: string; style?: CSSProperties; onClick?: () => void; onFocus?: () => void; def?: boolean; id?: string; title?: string; disabled?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current; if (!el || !props.onFocus) return;
    const h = () => props.onFocus?.(); el.addEventListener('fx-focus', h); return () => el.removeEventListener('fx-focus', h);
  }, [props.onFocus]);
  return (
    <div ref={ref} id={props.id} className={`fx ${props.className ?? ''}`} style={props.style} tabIndex={-1} title={props.title}
      data-fx="" {...(props.def ? { 'data-fx-default': '' } : {})} {...(props.disabled ? { 'data-fx-disabled': '' } : {})}
      onClick={props.onClick}>{props.children}</div>
  );
}

/** Imagen con respaldo: si no carga (o tarda), se ve un degradado con el título. */
export function Img({ src, alt, className, fit = 'cover' }: { src?: string | null; alt: string; className?: string; fit?: 'cover' | 'contain' }) {
  const [state, setState] = useState<'load' | 'ok' | 'fail'>(src ? 'load' : 'fail');
  useEffect(() => { setState(src ? 'load' : 'fail'); }, [src]);
  const hue = Math.abs(Array.from(alt).reduce((a, c) => a * 31 + c.charCodeAt(0), 7)) % 360;
  return (
    <div className={`img ${className ?? ''}${state === 'load' ? ' skeleton' : ''}`} style={state === 'load' ? undefined : { background: `linear-gradient(160deg, hsl(${hue},55%,32%), hsl(${(hue + 50) % 360},60%,12%))` }}>
      {state !== 'fail' && src && <img src={src} alt="" loading="lazy" style={{ objectFit: fit, opacity: state === 'ok' ? 1 : 0 }}
        onLoad={() => setState('ok')} onError={() => setState('fail')} />}
      {state === 'fail' && <span className="img-alt">{alt}</span>}
    </div>
  );
}

export function Card({ title, image, sub, onClick, wide, badge, progress, def }: { title: string; image?: string | null; sub?: string; onClick: () => void; wide?: boolean; badge?: string; progress?: number; def?: boolean }) {
  return (
    <Fx className={`card ${wide ? 'wide' : 'poster'}`} onClick={onClick} def={def} title={title}>
      <Img src={image} alt={title} />
      {badge && <span className="badge">{badge}</span>}
      {progress != null && progress > 0 && <div className="prog"><div style={{ width: `${Math.min(100, progress * 100)}%` }} /></div>}
      <div className="card-title">{title}</div>
      {sub && <div className="card-sub">{sub}</div>}
    </Fx>
  );
}

export function Row({ title, children, extra }: { title: string; children: ReactNode; extra?: ReactNode }) {
  return (
    <section className="row">
      <h2>{title}{extra}</h2>
      <div className="row-scroll" data-fx-group="">{children}</div>
    </section>
  );
}

export function Spinner({ label }: { label?: string }) { return <div className="spinner-box"><div className="spinner" />{label && <div>{label}</div>}</div>; }

export function Clock() {
  const [t, setT] = useState(() => new Date());
  useEffect(() => { const i = setInterval(() => setT(new Date()), 20000); return () => clearInterval(i); }, []);
  return <span className="clock">{t.toLocaleTimeString('es-MX', { hour: 'numeric', minute: '2-digit' })}</span>;
}

export function Logo({ className }: { className?: string }) {
  const { config } = useApp();
  const url = config.brand.logo?.url;
  return <img className={`logo ${className ?? ''}`} src={url && /^https:/.test(url) ? url : 'logo.png'} alt={config.brand.appName} />;
}

/** Carga asíncrona sencilla con estado. */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean } {
  const [s, setS] = useState<{ data: T | null; error: string | null; loading: boolean }>({ data: null, error: null, loading: true });
  useEffect(() => {
    let alive = true; setS((p) => ({ ...p, loading: true, error: null }));
    fn().then((d) => alive && setS({ data: d, error: null, loading: false }), (e) => alive && setS({ data: null, error: e?.message ?? 'Error', loading: false }));
    return () => { alive = false; };
  }, deps);
  return s;
}
