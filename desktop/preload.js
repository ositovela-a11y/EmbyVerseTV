// Puente mínimo entre la app y Windows: abrir archivos de lista, cabeceras por canal y pantalla completa.
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('evDesktop', {
  platform: 'pc',
  pickListFile: () => ipcRenderer.invoke('pick-list-file'),
  readListFile: (p) => ipcRenderer.invoke('read-list-file', p),
  setStreamHeaders: (url, ua, ref) => ipcRenderer.send('set-stream-headers', String(url), ua ? String(ua) : '', ref ? String(ref) : ''),
  toggleFullscreen: () => ipcRenderer.send('toggle-fullscreen'),
});
