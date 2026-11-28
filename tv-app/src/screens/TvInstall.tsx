// PC: instalar la app en una Smart TV de la misma red, sin celular.
//  · LG: lo hace esta app (modo desarrollador: IP + Passphrase de la TV). También renueva las 50 horas.
//  · Samsung: con el instalador gratuito Apps2Samsung para Windows y el archivo .wgt de la app.
import { useEffect, useState } from 'react';
import { Fx, useApp } from '../ui';

const SITE_TV = 'https://ositovela-a11y.github.io/EmbyVerseTV/tv';
const RELEASES = 'https://github.com/ositovela-a11y/EmbyVerseTV/releases/download';
const APPS2SAMSUNG = 'https://github.com/Apps2Samsung/Apps2Samsung/releases/latest';
const HELP = 'https://eversetv.com/instalar/';

type Desk = { lgSaved(): Promise<{ ip: string; pass: string; renew: number }>; lgInstall(ip: string, pass: string, urls: string[]): Promise<{ ok: boolean; message: string }>;
  lgRenew(ip?: string, pass?: string): Promise<{ ok: boolean; message: string }>; onLgProgress(cb: (m: string) => void): () => void; openExternal(u: string): void };
const desk = (): Desk | null => { const d = (window as any).evDesktop; return d && d.lgInstall ? d as Desk : null; };
export const tvInstallSupported = () => !!desk();

export function TvInstall({ onClose }: { onClose: () => void }) {
  const app = useApp();
  const [tab, setTab] = useState<'lg' | 'samsung'>('lg');
  const [ip, setIp] = useState(''); const [pass, setPass] = useState('');
  const [busy, setBusy] = useState(false); const [msg, setMsg] = useState<{ ok?: boolean; text: string } | null>(null);
  const [renewed, setRenewed] = useState(0);
  const d = desk();
  useEffect(() => { d?.lgSaved().then((s) => { setIp(s.ip); setPass(s.pass); setRenewed(s.renew); }); return d?.onLgProgress((m) => setMsg({ text: m })); }, []);
  const t = app.config.texts;
  const ipk = [t['tv.lgUrl'], `${SITE_TV}/EmbyVerse-LG.ipk`, `${RELEASES}/tv-latest/EmbyVerse-LG.ipk`].filter((u): u is string => !!u && u.startsWith('https://'));
  const wgt = (t['tv.samsungUrl']?.startsWith('https://') && t['tv.samsungUrl']) || `${SITE_TV}/EmbyVerse-Samsung.wgt`;
  const run = async (f: () => Promise<{ ok: boolean; message: string }>) => { setBusy(true); setMsg(null); try { const r = await f(); setMsg({ ok: r.ok, text: r.message }); } finally { setBusy(false); } };
  const field = (id: string, label: string, v: string, set: (s: string) => void, ph: string) => (
    <Fx className="field" onClick={() => (document.getElementById(id) as HTMLInputElement)?.focus()}>
      <label>{label}</label>
      <input id={id} value={v} placeholder={ph} onChange={(e) => set(e.target.value)} autoComplete="off"
        onKeyDown={(e) => { const k = e.keyCode; if (k === 13 || k === 40 || k === 38 || k === 27) { (e.target as HTMLInputElement).blur(); if (k !== 13) e.preventDefault(); return; } e.stopPropagation(); }} />
    </Fx>
  );
  const name = app.config.brand.appName;
  return (
    <div className="dialog" data-fx-layer="">
      <div className="dialog-box tv-install" id="tv-install">
        <h2>📺 Instalar {name} en tu Smart TV</h2>
        <div className="row-btns" data-fx-group="">
          <Fx className={`btn ${tab === 'lg' ? 'primary' : ''}`} id="ti-lg" def onClick={() => { setTab('lg'); setMsg(null); }}>TV LG</Fx>
          <Fx className={`btn ${tab === 'samsung' ? 'primary' : ''}`} id="ti-samsung" onClick={() => { setTab('samsung'); setMsg(null); }}>TV Samsung</Fx>
        </div>
        {tab === 'lg' ? (
          <>
            <ol className="ti-steps">
              <li>En la TV abre <b>LG Content Store</b>, instala <b>Developer Mode</b> y entra con una cuenta LG Developer (gratis).</li>
              <li>Pon <b>Dev Mode Status</b> en ON (la TV se reinicia) y luego <b>Key Server</b> en ON.</li>
              <li>Escribe aquí la <b>IP</b> y la <b>Passphrase</b> que muestra la TV. La PC debe estar en la misma red.</li>
            </ol>
            <div className="ti-fields" data-fx-group="">
              {field('ti-ip', 'IP de la TV', ip, setIp, '192.168.1.50')}
              {field('ti-pass', 'Passphrase', pass, (v) => setPass(v.toUpperCase()), 'ABC123')}
            </div>
            <div className="row-btns" data-fx-group="">
              <Fx className="btn primary big" id="ti-install" onClick={() => !busy && d && run(() => d.lgInstall(ip, pass, ipk))}>{busy ? 'Instalando…' : `Instalar ${name}`}</Fx>
              <Fx className="btn" id="ti-renew" onClick={() => !busy && d && run(() => d.lgRenew(ip, pass))}>Renovar 50 h</Fx>
            </div>
            {renewed > 0 && <p className="muted small">Última renovación: {new Date(renewed).toLocaleString('es-MX', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })}. La TV también se renueva sola cada vez que abres la app.</p>}
          </>
        ) : (
          <>
            <ol className="ti-steps">
              <li>Descarga e instala <b>Apps2Samsung</b> para Windows (gratis, botón de abajo).</li>
              <li>Descarga <b>{name} para Samsung</b> (archivo .wgt).</li>
              <li>En la TV: <b>Apps</b> → con el control escribe <b>1 2 3 4 5</b> → Developer mode <b>ON</b> → escribe la <b>IP de esta PC</b> → apaga y prende la TV.</li>
              <li>En Apps2Samsung elige tu TV → <b>archivo .wgt propio</b> → el archivo que bajaste. La primera vez pide tu cuenta Samsung (gratis).</li>
            </ol>
            <div className="row-btns" data-fx-group="">
              <Fx className="btn primary" id="ti-a2s" onClick={() => d?.openExternal(APPS2SAMSUNG)}>Descargar Apps2Samsung</Fx>
              <Fx className="btn" id="ti-wgt" onClick={() => d?.openExternal(wgt)}>Descargar {name} para Samsung</Fx>
            </div>
          </>
        )}
        {msg && <p className={msg.ok === false ? 'error-box' : msg.ok ? 'ok-box' : 'muted'} id="ti-msg">{msg.text}</p>}
        <div className="row-btns" data-fx-group="">
          <Fx className="btn" onClick={() => d?.openExternal(HELP)}>Guía con fotos</Fx>
          <Fx className="btn" id="ti-close" onClick={onClose}>Cerrar</Fx>
        </div>
      </div>
    </div>
  );
}
