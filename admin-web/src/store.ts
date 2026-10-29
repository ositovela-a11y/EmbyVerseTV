import { useCallback, useMemo, useRef, useState } from 'react';
import { parseConfig, type RemoteConfig } from '../../backend/src/config/schema';
import defaultsJson from '../../shared/default-config.json';

export const DEFAULTS = defaultsJson as unknown as RemoteConfig;

/** Validación local instantánea (mismas reglas que el servidor y la app). */
export function validateLocal(c: RemoteConfig) { return parseConfig(c, DEFAULTS); }

interface History { past: RemoteConfig[]; future: RemoteConfig[] }

/**
 * Estado del editor con deshacer/rehacer. Los cambios continuos (deslizadores, selectores de
 * color) con la misma clave en menos de 700 ms se agrupan en un único paso de deshacer.
 */
export function useEditor(initial: RemoteConfig) {
  const [config, setConfig] = useState<RemoteConfig>(initial);
  const [savedJson, setSavedJson] = useState(() => JSON.stringify(initial));
  const hist = useRef<History>({ past: [], future: [] });
  const last = useRef<{ key: string; at: number } | null>(null);
  const [, force] = useState(0);

  const update = useCallback((mutate: (draft: RemoteConfig) => void, key = '') => {
    setConfig((prev) => {
      const next = structuredClone(prev);
      mutate(next);
      const now = Date.now();
      const coalesce = key && last.current?.key === key && now - last.current.at < 700;
      if (!coalesce) { hist.current.past.push(prev); if (hist.current.past.length > 100) hist.current.past.shift(); }
      hist.current.future = [];
      last.current = { key, at: now };
      return next;
    });
  }, []);

  const undo = useCallback(() => {
    const prev = hist.current.past.pop();
    if (!prev) return;
    setConfig((cur) => { hist.current.future.push(cur); return prev; });
    last.current = null; force((n) => n + 1);
  }, []);

  const redo = useCallback(() => {
    const next = hist.current.future.pop();
    if (!next) return;
    setConfig((cur) => { hist.current.past.push(cur); return next; });
    last.current = null; force((n) => n + 1);
  }, []);

  const reset = useCallback((c: RemoteConfig) => {
    setConfig(c); setSavedJson(JSON.stringify(c)); hist.current = { past: [], future: [] }; last.current = null;
  }, []);

  const markSaved = useCallback((c: RemoteConfig) => setSavedJson(JSON.stringify(c)), []);
  const dirty = useMemo(() => JSON.stringify(config) !== savedJson, [config, savedJson]);
  const validation = useMemo(() => validateLocal(config), [config]);

  return { config, update, undo, redo, reset, markSaved, dirty, validation,
    canUndo: hist.current.past.length > 0, canRedo: hist.current.future.length > 0 };
}

export type Editor = ReturnType<typeof useEditor>;

/** Relación de contraste WCAG entre dos colores #RRGGBB / #AARRGGBB. */
export function contrast(a: string, b: string): number {
  const lum = (hex: string) => {
    const h = hex.length === 9 ? hex.slice(3) : hex.slice(1);
    const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * ch(parseInt(h.slice(0, 2), 16)) + 0.7152 * ch(parseInt(h.slice(2, 4), 16)) + 0.0722 * ch(parseInt(h.slice(4, 6), 16));
  };
  const [x, y] = [lum(a), lum(b)].sort((m, n) => n - m) as [number, number];
  return (x + 0.05) / (y + 0.05);
}

/** Convierte #AARRGGBB (formato Android) a CSS rgba. */
export function css(hex: string): string {
  if (hex.length === 9) {
    const a = parseInt(hex.slice(1, 3), 16) / 255;
    return `rgba(${parseInt(hex.slice(3, 5), 16)}, ${parseInt(hex.slice(5, 7), 16)}, ${parseInt(hex.slice(7, 9), 16)}, ${a.toFixed(3)})`;
  }
  return hex;
}
