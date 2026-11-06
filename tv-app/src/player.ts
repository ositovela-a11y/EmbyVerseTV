// Reproductor: Samsung usa AVPlay (nativo, reproduce .ts/HLS/MP4/MKV); LG y navegador usan <video>
// con hls.js (HLS) o mpegts.js (.ts en vivo) cuando el navegador no los reproduce solo.
import Hls from 'hls.js';
import mpegts from 'mpegts.js';
import { isTizen } from './platform';

declare const webapis: any;
export interface Track { index: number; label: string; lang?: string; kind: 'audio' | 'text' }
export type PlayerEvent = 'ready' | 'playing' | 'buffering' | 'error' | 'ended' | 'time';

export interface Engine {
  load(url: string, opts: { live: boolean; startMs?: number }): void;
  play(): void; pause(): void; stop(): void;
  seek(ms: number): void;
  position(): number; duration(): number; paused(): boolean;
  tracks(): Track[]; selectTrack(t: Track | null, kind: 'audio' | 'text'): void;
  videoHeight(): number;
  setAspect(mode: number): void;
  on(cb: (e: PlayerEvent, detail?: string) => void): void;
  destroy(): void;
}

// ------------------------------------------------------------------ Samsung AVPlay
class AvPlayEngine implements Engine {
  private cb: (e: PlayerEvent, d?: string) => void = () => {};
  private el = document.getElementById('avplayer') as HTMLElement;
  private live = false;
  private timer: any = null;
  on(cb: (e: PlayerEvent, d?: string) => void) { this.cb = cb; }
  load(url: string, opts: { live: boolean; startMs?: number }) {
    this.live = opts.live;
    const av = webapis.avplay;
    try { av.stop(); av.close(); } catch { /* */ }
    this.el.style.display = 'block';
    av.open(url);
    av.setDisplayRect(0, 0, 1920, 1080);
    av.setDisplayMethod('PLAYER_DISPLAY_MODE_LETTER_BOX');
    try { av.setStreamingProperty('ADAPTIVE_INFO', 'BITRATES=LOWEST|HIGHEST'); } catch { /* */ }
    try { if (opts.live) av.setTimeoutForBuffering(15); } catch { /* */ }
    av.setListener({
      onbufferingstart: () => this.cb('buffering'),
      onbufferingcomplete: () => this.cb('playing'),
      onstreamcompleted: () => this.cb('ended'),
      oncurrentplaytime: () => this.cb('time'),
      onerror: (e: any) => this.cb('error', String(e)),
      onevent: () => {},
    });
    av.prepareAsync(() => {
      if (opts.startMs && !opts.live) try { av.seekTo(opts.startMs); } catch { /* */ }
      av.play(); this.cb('ready'); this.cb('playing');
    }, (e: any) => this.cb('error', String(e)));
  }
  play() { try { webapis.avplay.play(); } catch { /* */ } }
  pause() { try { webapis.avplay.pause(); } catch { /* */ } }
  stop() { try { webapis.avplay.stop(); webapis.avplay.close(); } catch { /* */ } this.el.style.display = 'none'; }
  seek(ms: number) { try { webapis.avplay.seekTo(Math.max(0, ms)); } catch { /* */ } }
  position() { try { return webapis.avplay.getCurrentTime(); } catch { return 0; } }
  duration() { try { return this.live ? 0 : webapis.avplay.getDuration(); } catch { return 0; } }
  paused() { try { return webapis.avplay.getState() === 'PAUSED'; } catch { return false; } }
  tracks(): Track[] {
    try {
      return webapis.avplay.getTotalTrackInfo().filter((t: any) => t.type === 'AUDIO' || t.type === 'TEXT').map((t: any) => {
        let lang = ''; try { lang = JSON.parse(t.extra_info).language ?? ''; } catch { /* */ }
        return { index: t.index, kind: t.type === 'AUDIO' ? 'audio' : 'text', lang, label: lang || `${t.type === 'AUDIO' ? 'Audio' : 'Subtítulos'} ${t.index}` } as Track;
      });
    } catch { return []; }
  }
  selectTrack(t: Track | null, kind: 'audio' | 'text') {
    try {
      if (!t) { if (kind === 'text') webapis.avplay.setSilentSubtitle(true); return; }
      if (kind === 'text') webapis.avplay.setSilentSubtitle(false);
      webapis.avplay.setSelectTrack(kind === 'audio' ? 'AUDIO' : 'TEXT', t.index);
    } catch { /* */ }
  }
  videoHeight() { try { const v = webapis.avplay.getCurrentStreamInfo().find((s: any) => s.type === 'VIDEO'); return JSON.parse(v.extra_info).Height ?? 0; } catch { return 0; } }
  setAspect(mode: number) {
    try { webapis.avplay.setDisplayMethod(mode === 1 || mode === 2 ? 'PLAYER_DISPLAY_MODE_FULL_SCREEN' : 'PLAYER_DISPLAY_MODE_LETTER_BOX'); } catch { /* */ }
  }
  destroy() { clearInterval(this.timer); this.stop(); }
}

// ------------------------------------------------------------------ <video> (LG webOS y navegador)
class VideoEngine implements Engine {
  private v = document.getElementById('bgvideo') as HTMLVideoElement;
  private hls: any = null; private ts: any = null;
  private cb: (e: PlayerEvent, d?: string) => void = () => {};
  private live = false;
  private handlers: [string, any][] = [];
  on(cb: (e: PlayerEvent, d?: string) => void) { this.cb = cb; }
  private clean() {
    // Se sueltan primero los eventos y luego se destruye (evita errores de hls.js/mpegts.js al cambiar rápido).
    const h = this.hls, t = this.ts; this.hls = null; this.ts = null;
    try { h?.off?.((Hls as any).Events.ERROR); h?.stopLoad?.(); h?.detachMedia?.(); h?.destroy(); } catch { /* */ }
    try { t?.pause?.(); t?.unload?.(); t?.detachMediaElement?.(); t?.destroy(); } catch { /* */ }
    this.handlers.forEach(([e, h]) => this.v.removeEventListener(e, h)); this.handlers = [];
  }
  private listen(e: string, h: any) { this.v.addEventListener(e, h); this.handlers.push([e, h]); }
  load(url: string, opts: { live: boolean; startMs?: number }) {
    this.clean(); this.live = opts.live;
    const v = this.v; v.style.display = 'block';
    this.listen('playing', () => this.cb('playing'));
    this.listen('waiting', () => this.cb('buffering'));
    this.listen('ended', () => this.cb('ended'));
    this.listen('timeupdate', () => this.cb('time'));
    this.listen('error', () => this.cb('error', v.error ? `video ${v.error.code}` : 'video'));
    this.listen('loadedmetadata', () => { if (opts.startMs && !opts.live) v.currentTime = opts.startMs / 1000; this.cb('ready'); });
    const isHls = /\.m3u8(\?|$)/i.test(url);
    const isTs = /\.ts(\?|$)/i.test(url);
    const nativeHls = !!v.canPlayType('application/vnd.apple.mpegurl') || !!v.canPlayType('application/x-mpegURL');
    if (isHls && !nativeHls && Hls && (Hls as any).isSupported?.()) {
      const h = new (Hls as any)({ lowLatencyMode: false, maxBufferLength: 30, startLevel: -1 });
      this.hls = h; h.loadSource(url); h.attachMedia(v);
      h.on((Hls as any).Events.ERROR, (_: any, d: any) => { if (d?.fatal) this.cb('error', `hls ${d.type}`); });
    } else if (isTs && mpegts && (mpegts as any).isSupported?.() && !v.canPlayType('video/mp2t')) {
      const p = (mpegts as any).createPlayer({ type: 'mpegts', isLive: opts.live, url }, { enableStashBuffer: true, liveBufferLatencyChasing: true });
      this.ts = p; p.attachMediaElement(v); p.load();
      p.on((mpegts as any).Events.ERROR, (t: any) => this.cb('error', `ts ${t}`));
    } else { v.src = url; v.load(); }
    const pr = v.play(); if (pr && pr.catch) pr.catch(() => { /* lo intenta al estar listo */ });
  }
  play() { const p = this.v.play(); if (p && p.catch) p.catch(() => {}); }
  pause() { this.v.pause(); }
  stop() { this.clean(); this.v.pause(); this.v.removeAttribute('src'); try { this.v.load(); } catch { /* */ } this.v.style.display = 'none'; }
  seek(ms: number) { try { this.v.currentTime = Math.max(0, ms / 1000); } catch { /* */ } }
  position() { return (this.v.currentTime || 0) * 1000; }
  duration() { const d = this.v.duration; return this.live || !Number.isFinite(d) ? 0 : d * 1000; }
  paused() { return this.v.paused; }
  tracks(): Track[] {
    const out: Track[] = [];
    const at: any = (this.v as any).audioTracks;
    if (this.hls) {
      (this.hls.audioTracks ?? []).forEach((t: any, i: number) => out.push({ index: i, kind: 'audio', lang: t.lang, label: t.name || t.lang || `Audio ${i + 1}` }));
      (this.hls.subtitleTracks ?? []).forEach((t: any, i: number) => out.push({ index: i, kind: 'text', lang: t.lang, label: t.name || t.lang || `Subtítulos ${i + 1}` }));
      return out;
    }
    if (at) for (let i = 0; i < at.length; i++) out.push({ index: i, kind: 'audio', lang: at[i].language, label: at[i].label || at[i].language || `Audio ${i + 1}` });
    const tt = this.v.textTracks;
    for (let i = 0; i < tt.length; i++) out.push({ index: i, kind: 'text', lang: tt[i].language, label: tt[i].label || tt[i].language || `Subtítulos ${i + 1}` });
    return out;
  }
  selectTrack(t: Track | null, kind: 'audio' | 'text') {
    if (this.hls) { if (kind === 'audio' && t) this.hls.audioTrack = t.index; if (kind === 'text') this.hls.subtitleTrack = t ? t.index : -1; return; }
    if (kind === 'audio') { const at: any = (this.v as any).audioTracks; if (at && t) for (let i = 0; i < at.length; i++) at[i].enabled = i === t.index; }
    else { const tt = this.v.textTracks; for (let i = 0; i < tt.length; i++) tt[i].mode = t && i === t.index ? 'showing' : 'disabled'; }
  }
  videoHeight() { return this.v.videoHeight || 0; }
  setAspect(mode: number) {
    const s = this.v.style;
    s.objectFit = mode === 1 ? 'fill' : mode === 2 ? 'cover' : 'contain';
    s.width = '1920px'; s.left = '0px';
    if (mode === 3 || mode === 4) { const w = mode === 3 ? 1920 : 1440; s.width = `${w}px`; s.left = `${(1920 - w) / 2}px`; s.objectFit = 'fill'; }
  }
  destroy() { this.stop(); }
}

let engine: Engine | null = null;
export function getEngine(): Engine {
  if (!engine) engine = isTizen && typeof webapis !== 'undefined' && webapis.avplay ? new AvPlayEngine() : new VideoEngine();
  return engine;
}

export const qualityLabel = (h: number) => (h >= 2000 ? '4K' : h >= 1000 ? 'FHD' : h >= 700 ? 'HD' : h > 0 ? 'SD' : '');
