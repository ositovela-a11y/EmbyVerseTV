// Datos guardados en la TV: cuenta, perfiles, favoritos, «continuar viendo», búsquedas y ajustes.
export interface Account { host: string; username: string; password: string; expiresAt?: number | null }
export interface Profile { id: string; name: string; avatar: string; kids?: boolean; pin?: string | null; blockAdult?: boolean; kidsMinutes?: number; audio?: string; subs?: string }
export interface Fav { kind: 'live' | 'movie' | 'series'; id: string; title: string; image?: string | null; at: number }
export interface Progress { kind: 'movie' | 'episode'; id: string; seriesId?: string; title: string; image?: string | null; pos: number; dur: number; at: number; ext?: string; season?: number; episode?: number }
export interface Prefs { style: string; navSounds: boolean; screensaver: boolean; intro: boolean; subSize: number; subYellow: boolean; subBg: number; night: boolean; aspect: number; lite: boolean; lastChannelStart: boolean }

const get = <T>(k: string, d: T): T => { try { const v = localStorage.getItem(k); return v ? (JSON.parse(v) as T) : d; } catch { return d; } };
const set = (k: string, v: unknown) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* lleno */ } };

export const store = {
  account: () => get<Account | null>('ev.account', null),
  setAccount: (a: Account | null) => (a ? set('ev.account', a) : localStorage.removeItem('ev.account')),

  profiles: () => { const p = get<Profile[]>('ev.profiles', []); return p.length ? p : [{ id: 'p1', name: 'Principal', avatar: '🦁' }]; },
  setProfiles: (p: Profile[]) => set('ev.profiles', p),
  activeProfileId: () => get<string>('ev.profile', 'p1'),
  setActiveProfile: (id: string) => set('ev.profile', id),
  activeProfile(): Profile { const id = this.activeProfileId(); return this.profiles().find((p) => p.id === id) ?? this.profiles()[0]; },

  favs: (pid: string) => get<Fav[]>(`ev.fav.${pid}`, []),
  setFavs: (pid: string, f: Fav[]) => set(`ev.fav.${pid}`, f),
  toggleFav(pid: string, f: Omit<Fav, 'at'>): boolean {
    const l = this.favs(pid); const i = l.findIndex((x) => x.kind === f.kind && x.id === f.id);
    if (i >= 0) { l.splice(i, 1); this.setFavs(pid, l); return false; }
    l.unshift({ ...f, at: Date.now() }); this.setFavs(pid, l.slice(0, 300)); return true;
  },
  isFav(pid: string, kind: string, id: string) { return this.favs(pid).some((x) => x.kind === kind && x.id === id); },

  progress: (pid: string) => get<Progress[]>(`ev.prog.${pid}`, []),
  saveProgress(pid: string, p: Progress) {
    const l = this.progress(pid).filter((x) => !(x.kind === p.kind && x.id === p.id) && !(p.seriesId && x.seriesId === p.seriesId));
    l.unshift(p); set(`ev.prog.${pid}`, l.slice(0, 60));
  },
  lastLive: (pid: string) => get<string[]>(`ev.lastlive.${pid}`, []),
  pushLive(pid: string, id: string) { const l = this.lastLive(pid).filter((x) => x !== id); l.unshift(id); set(`ev.lastlive.${pid}`, l.slice(0, 30)); },

  searches: () => get<string[]>('ev.searches', []),
  addSearch(q: string) { const v = q.trim(); if (v.length < 2) return; set('ev.searches', [v, ...this.searches().filter((x) => x.toLowerCase() !== v.toLowerCase())].slice(0, 8)); },

  prefs: (): Prefs => ({ style: '', navSounds: true, screensaver: true, intro: true, subSize: 1, subYellow: false, subBg: 1, night: false, aspect: 0, lite: false, lastChannelStart: false, ...get<Partial<Prefs>>('ev.prefs', {}) }),
  setPrefs: (p: Partial<Prefs>) => set('ev.prefs', { ...get<Partial<Prefs>>('ev.prefs', {}), ...p }),

  seenMessages: () => get<string[]>('ev.seenmsg', []),
  markSeen(id: string) { set('ev.seenmsg', [...this.seenMessages(), id].slice(-50)); },

  kidsUsed(pid: string): number { const d = new Date(); return get<number>(`ev.kids.${pid}.${d.getFullYear()}${d.getMonth()}${d.getDate()}`, 0); },
  addKidsMinute(pid: string) { const d = new Date(); const k = `ev.kids.${pid}.${d.getFullYear()}${d.getMonth()}${d.getDate()}`; set(k, get<number>(k, 0) + 1); },

  blockedFlag: () => get<boolean>('ev.blocked', false),
  setBlocked: (b: boolean) => set('ev.blocked', b),
  wipe() { try { Object.keys(localStorage).filter((k) => k.startsWith('ev.') && k !== 'ev.config' && k !== 'ev.blocked').forEach((k) => localStorage.removeItem(k)); } catch { /* */ } },
};
