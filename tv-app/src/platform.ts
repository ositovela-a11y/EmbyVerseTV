// Detección de plataforma y teclas del control remoto (Samsung Tizen, LG webOS, navegador).
declare const tizen: any;
declare const webapis: any;

export const isTizen = typeof (window as any).tizen !== 'undefined';
export const isWebOS = /web0s|webos/i.test(navigator.userAgent) || typeof (window as any).webOS !== 'undefined' || typeof (window as any).PalmSystem !== 'undefined';
export const platformName = isTizen ? 'Samsung' : isWebOS ? 'LG' : 'Navegador';

export const KEY = {
  LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
  BACK: [10009, 461, 27, 8],
  PLAY_PAUSE: [10252, 179, 415, 19], PLAY: [415, 250], PAUSE: [19],
  STOP: [413], FF: [417, 228], RW: [412, 227],
  CH_UP: [427, 33], CH_DOWN: [428, 34], PREV_CH: [10190],
  INFO: [457], RED: [403], GREEN: [404], YELLOW: [405], BLUE: [406],
};

export const isKey = (code: number, k: number | number[]) => (Array.isArray(k) ? k.includes(code) : code === k);

/** Samsung necesita «registrar» las teclas de color, números y multimedia para recibirlas. */
export function registerKeys() {
  if (!isTizen) return;
  try {
    const want = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind', 'ChannelUp', 'ChannelDown',
      'PreviousChannel', 'Info', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
    const supported = tizen.tvinputdevice.getSupportedKeys().map((k: any) => k.name);
    want.filter((k) => supported.includes(k)).forEach((k) => { try { tizen.tvinputdevice.registerKey(k); } catch { /* sin esa tecla */ } });
  } catch { /* fuera de Tizen */ }
}

/** Cerrar la app (tecla Atrás en el inicio). */
export function exitApp() {
  try { if (isTizen) { tizen.application.getCurrentApplication().exit(); return; } } catch { /* */ }
  try { if (isWebOS) { (window as any).webOS?.platformBack?.(); (window as any).close(); return; } } catch { /* */ }
}

export function deviceInfo(): string {
  try { if (isTizen) return `Samsung ${webapis.productinfo.getRealModel()} · Tizen ${tizen.systeminfo.getCapability('http://tizen.org/feature/platform.version')}`; } catch { /* */ }
  const m = navigator.userAgent.match(/Chrome\/(\d+)/);
  return `${platformName} · Chromium ${m ? m[1] : '?'}`;
}
