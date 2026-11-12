// App para PC: la misma app de TV (React) dentro de una ventana de Windows.
// A diferencia de un navegador, aquí los canales «http://», las listas de otros servidores y los
// User-Agent / Referer que pide cada lista funcionan sin bloqueos.
const { app, BrowserWindow, ipcMain, dialog, session, shell } = require('electron');
const path = require('node:path');
const fs = require('node:fs');

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 EmbyVersePC/' + app.getVersion();

/** Cabeceras por canal (de la lista M3U): se aplican a la dirección y a sus segmentos (misma carpeta). */
const streamHeaders = new Map(); // prefijo → { ua, ref }
const prefixOf = (u) => { try { const x = new URL(u); return x.origin + x.pathname.replace(/[^/]*$/, ''); } catch { return null; } };

let win;
function fit() {
  if (!win) return;
  const [w, h] = win.getContentSize();
  win.webContents.setZoomFactor(Math.max(0.25, Math.min(w / 1920, h / 1080)));
}

function create() {
  win = new BrowserWindow({
    width: 1280, height: 720, minWidth: 640, minHeight: 360, backgroundColor: '#03070F', autoHideMenuBar: true,
    title: app.getName(), icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true, nodeIntegration: false, sandbox: true,
      // Reproductor IPTV: debe abrir listas y canales de cualquier servidor (http o https).
      webSecurity: false, allowRunningInsecureContent: true, backgroundThrottling: false,
      // Instalada: sin herramientas de desarrollador (protege la activación y la configuración).
      devTools: !app.isPackaged,
    },
  });
  win.removeMenu();
  win.webContents.setUserAgent(UA);
  win.on('resize', fit);
  win.webContents.on('did-finish-load', fit);
  win.webContents.on('zoom-changed', fit);
  // Enlaces externos (WhatsApp, tráileres) en el navegador del sistema.
  win.webContents.setWindowOpenHandler(({ url }) => { if (/^https?:/i.test(url)) shell.openExternal(url); return { action: 'deny' }; });
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    if (input.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    if (input.key === 'Escape' && win.isFullScreen()) { win.setFullScreen(false); }
  });
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
}

app.whenReady().then(() => {
  session.defaultSession.webRequest.onBeforeSendHeaders((d, cb) => {
    const h = d.requestHeaders;
    const pre = prefixOf(d.url);
    const s = pre && streamHeaders.get(pre);
    if (s) { if (s.ua) h['User-Agent'] = s.ua; if (s.ref) h['Referer'] = s.ref; }
    cb({ requestHeaders: h });
  });
  ipcMain.on('set-stream-headers', (_e, url, ua, ref) => { const p = prefixOf(url); if (p) streamHeaders.set(p, { ua: ua || undefined, ref: ref || undefined }); });
  ipcMain.on('toggle-fullscreen', () => win && win.setFullScreen(!win.isFullScreen()));
  ipcMain.handle('pick-list-file', async () => {
    const r = await dialog.showOpenDialog(win, { title: 'Abrir lista M3U', properties: ['openFile'], filters: [{ name: 'Listas M3U', extensions: ['m3u', 'm3u8', 'txt'] }] });
    return r.canceled || !r.filePaths[0] ? null : r.filePaths[0];
  });
  ipcMain.handle('read-list-file', async (_e, p) => {
    if (typeof p !== 'string' || !/\.(m3u8?|txt)$/i.test(p)) throw new Error('Solo se pueden abrir listas .m3u, .m3u8 o .txt');
    return fs.promises.readFile(p, 'utf8');
  });
  create();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) create(); });
});
app.on('window-all-closed', () => app.quit());
