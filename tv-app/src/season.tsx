// Fondos animados del panel (60, iguales a Android): partículas con CSS (ligeras para la TV).
const G: Record<string, [string[], 'fall' | 'rise' | 'twinkle']> = {
  cine: [['🎬', '🎞', '⭐', '🎥'], 'fall'], palomitas: [['🍿', '🥤', '🎟'], 'fall'], estreno: [['✨', '⭐', '🎬'], 'twinkle'], terror: [['👻', '🕷', '🕸', '🦇'], 'fall'],
  superheroes: [['💥', '⚡', '🦸', '⭐'], 'fall'], anime: [['🌸', '✨', '⭐', '🍥'], 'fall'], galaxia: [['✦', '✧', '🪐', '☄'], 'twinkle'], estrellas: [['✦', '✧', '⋆'], 'twinkle'],
  espacio: [['🚀', '🛸', '🌙', '🪐'], 'fall'], luciernagas: [['•', '∙', '•'], 'twinkle'], noche: [['🌙', '⭐', '✧'], 'twinkle'], deportes: [['⚽', '🏀', '🏈', '⚾', '🎾'], 'fall'],
  futbol: [['⚽', '🥅', '🏆'], 'fall'], basquet: [['🏀', '⛹'], 'fall'], americano: [['🏈', '🏟'], 'fall'], beisbol: [['⚾', '🧢'], 'fall'], box: [['🥊', '🏆', '⭐'], 'fall'],
  carreras: [['🏎', '🏁', '🏆'], 'fall'], musica: [['🎵', '🎶', '🎧'], 'rise'], concierto: [['🎸', '🎤', '✨'], 'twinkle'], videojuegos: [['🎮', '👾', '🕹', '⭐'], 'fall'],
  primavera: [['🌸', '🦋', '🌼'], 'fall'], verano: [['🌴', '☀', '🏖', '🍉'], 'fall'], otono: [['🍂', '🍁', '🍃'], 'fall'], invierno: [['❄', '☃', '❅'], 'fall'],
  lluvia: [['💧', '☔', '☁'], 'fall'], playa: [['🐚', '🌊', '🏖', '⛱'], 'fall'], oceano: [['🐠', '🐟', '🐙', '🫧'], 'rise'], selva: [['🌿', '🦜', '🐒', '🍃'], 'fall'],
  flores: [['🌷', '🌻', '🌹', '🌼'], 'fall'], mariposas: [['🦋', '🌸'], 'rise'], burbujas: [['○', '◦', '○'], 'rise'], globos: [['🎈', '🎈', '✨'], 'rise'],
  confeti: [['🎉', '🎊', '✨'], 'fall'], corazones: [['❤', '💕', '💖'], 'rise'], dulces: [['🍭', '🍬', '🍫', '🧁'], 'fall'], comida: [['🍕', '🍔', '🌮', '🍟'], 'fall'],
  cafe: [['☕', '🥐', '🍩'], 'rise'], dinosaurios: [['🦕', '🦖', '🌋'], 'fall'], unicornios: [['🦄', '🌈', '✨'], 'rise'], arcoiris: [['🌈', '☁', '⭐'], 'rise'], mascotas: [['🐶', '🐱', '🐾'], 'fall'],
  navidad: [['❄', '❅', '•'], 'fall'], navidad2: [['🎄', '🎁', '⭐', '🔔'], 'fall'], posadas: [['🪅', '⭐', '🍬'], 'fall'], anonuevo: [['🎆', '🎇', '✨', '🥂'], 'rise'],
  reyes: [['👑', '⭐', '🎁', '🐫'], 'fall'], sanvalentin: [['❤', '💕', '💖'], 'rise'], pascua: [['🐣', '🥚', '🐰', '🌷'], 'fall'], dianino: [['🎈', '🧸', '🪁', '⭐'], 'rise'],
  madres: [['💐', '❤', '🌹'], 'fall'], padre: [['👔', '🏆', '⭐'], 'fall'], patrias: [['🎉', '🎊', '⭐'], 'fall'], halloween: [['🦇', '🎃', '🕸'], 'fall'],
  muertos: [['🌼', '💀', '🕯'], 'fall'], buenfin: [['🛍', '💸', '🏷'], 'fall'], mundial: [['⚽', '🏆', '🎉'], 'fall'], cumple: [['🎂', '🎈', '🎉'], 'rise'],
  graduacion: [['🎓', '⭐', '🎉'], 'fall'], kids: [['🎈', '⭐', '🌈', '🧸'], 'rise'],
};
const GENERAL = ['cine', 'palomitas', 'estreno', 'terror', 'superheroes', 'anime', 'galaxia', 'estrellas', 'espacio', 'luciernagas', 'noche', 'deportes', 'futbol', 'basquet', 'americano', 'beisbol', 'box', 'carreras', 'musica', 'concierto', 'videojuegos', 'primavera', 'verano', 'otono', 'invierno', 'lluvia', 'playa', 'oceano', 'selva', 'flores', 'mariposas', 'burbujas', 'globos', 'confeti', 'corazones', 'dulces', 'comida', 'cafe', 'dinosaurios', 'unicornios', 'arcoiris', 'mascotas'];

function holiday(d = new Date()): string | null {
  const m = d.getMonth() + 1, day = d.getDate();
  if ((m === 12 && day === 31) || (m === 1 && day <= 2)) return 'anonuevo';
  if (m === 1 && day >= 5 && day <= 6) return 'reyes';
  if (m === 12 && day >= 16 && day <= 24) return 'posadas';
  if (m === 12 && day >= 25) return 'navidad2';
  if (m === 12) return 'navidad';
  if (m === 2 && day >= 10 && day <= 14) return 'sanvalentin';
  if (m === 4 && day === 30) return 'dianino';
  if (m === 5 && day === 10) return 'madres';
  if (m === 9 && day >= 10 && day <= 16) return 'patrias';
  if (m === 10 && day >= 24) return 'halloween';
  if (m === 11 && day <= 2) return 'muertos';
  return null;
}

export function seasonFor(texts: Record<string, string>, kids: boolean): string | null {
  if (kids) return 'kids';
  const v = (texts.season ?? '').trim().toLowerCase();
  const every = Math.min(48, Math.max(1, parseInt(texts['season.every'] ?? '3', 10) || 3));
  const rot = GENERAL[Math.floor(Date.now() / (every * 3_600_000)) % GENERAL.length];
  if (!v || v === 'ninguna' || v === 'none') return null;
  if (v === 'rotar') return rot;
  if (v === 'auto') return holiday() ?? rot;
  return G[v] ? v : null;
}

export function Season({ kind }: { kind: string }) {
  const [glyphs, motion] = G[kind] ?? G.estrellas;
  const n = 18;
  return (
    <div className={`season ${motion}`} aria-hidden="true">
      {Array.from({ length: n }, (_, i) => {
        const left = (i * 53) % 100; const delay = (i * 1.7) % 12; const dur = 12 + ((i * 7) % 10); const size = 22 + ((i * 13) % 22);
        return <span key={i} style={{ left: `${left}%`, top: motion === 'twinkle' ? `${(i * 37) % 95}%` : undefined, animationDelay: `-${delay}s`, animationDuration: `${dur}s`, fontSize: `${size}px` }}>{glyphs[i % glyphs.length]}</span>;
      })}
      {kind === 'muertos' && <div className="papel-picado" />}
    </div>
  );
}
