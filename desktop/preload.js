// Puente mínimo entre la app y Windows: abrir archivos de lista, cabeceras por canal y pantalla completa.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('evDesktop', {
  platform: 'pc',
  pickListFile: () => ipcRenderer.invoke('pick-list-file'),
  readListFile: (p) => ipcRenderer.invoke('read-list-file', p),
  setStreamHeaders: (url, ua, ref) => ipcRenderer.send('set-stream-headers', String(url), ua ? String(ua) : '', ref ? String(ref) : ''),
  toggleFullscreen: () => ipcRenderer.send('toggle-fullscreen'),
  // Instalar la app en una TV LG de la misma red (modo desarrollador) y renovar sus 50 horas.
  lgSaved: () => ipcRenderer.invoke('lg-saved'),
  lgInstall: (ip, pass, urls) => ipcRenderer.invoke('lg-install', String(ip), String(pass), Array.isArray(urls) ? urls.map(String) : []),
  lgRenew: (ip, pass) => ipcRenderer.invoke('lg-renew', String(ip || ''), String(pass || '')),
  onLgProgress: (cb) => { const f = (_e, m) => cb(String(m)); ipcRenderer.on('lg-progress', f); return () => ipcRenderer.removeListener('lg-progress', f); },
  openExternal: (url) => ipcRenderer.send('open-external', String(url)),
});
