// Temas listos para las apps de vendedores: cada uno cambia colores, fondo, letra, bordes, botones
// y la distribución de la app (estilo de plataforma), para que no se vea igual que la app principal.

export interface VendorTheme {
  id: string; name: string; desc: string;
  palette: Record<string, string>;
  background: { style: 'solid' | 'gradient' | 'nebula'; gradientStart: string; gradientEnd: string };
  family: 'poppins' | 'inter' | 'system';
  radius: { card: number; button: number; banner: number };
  button: 'filled' | 'tonal' | 'outlined';
  nav: 'bottom_bar' | 'side_rail' | 'top_tabs';
  style: string; // texto «style.default» (distribución tipo plataforma)
}

const pal = (primary: string, accent: string, background: string, surface: string, surfaceVariant: string, onText: string, secondary: string, buttonText = '#FFFFFF') => ({
  primary, secondary, accent, background, surface, surfaceVariant,
  onBackground: '#F2F4F8', onSurface: '#E3E7EE', textSecondary: onText,
  onPrimary: buttonText, buttonBackground: primary, buttonText, focusRing: accent, error: '#FF5A6E',
});

export const VENDOR_THEMES: VendorTheme[] = [
  { id: 'cine', name: 'Cine Rojo', desc: 'Rojo intenso sobre negro, menú lateral desplegable y Top 10', style: 'style_cine_rojo',
    palette: pal('#E50914', '#FF4D57', '#0B0B0B', '#161616', '#232323', '#A3A3A3', '#B20710'),
    background: { style: 'solid', gradientStart: '#0B0B0B', gradientEnd: '#1A0405' }, family: 'inter', radius: { card: 6, button: 6, banner: 8 }, button: 'filled', nav: 'side_rail' },
  { id: 'cristal', name: 'Cristal Azul', desc: 'Azul elegante con barra de cristal flotante', style: 'style_cristal',
    palette: pal('#3D8BFF', '#8EC5FF', '#060B16', '#0E1728', '#18243B', '#93A4BF', '#1F4FA8'),
    background: { style: 'gradient', gradientStart: '#060B16', gradientEnd: '#0D2142' }, family: 'system', radius: { card: 18, button: 22, banner: 24 }, button: 'tonal', nav: 'top_tabs' },
  { id: 'purpura', name: 'Púrpura Premium', desc: 'Morado y rosa, logo al centro y colecciones', style: 'style_purpura',
    palette: pal('#A855F7', '#F472B6', '#0E0716', '#1A1027', '#271838', '#B6A3CC', '#6D28D9'),
    background: { style: 'nebula', gradientStart: '#0E0716', gradientEnd: '#2A0F3D' }, family: 'poppins', radius: { card: 16, button: 26, banner: 22 }, button: 'filled', nav: 'top_tabs' },
  { id: 'oceano', name: 'Océano', desc: 'Turquesa fresco con barra lateral fija', style: 'style_oceano',
    palette: pal('#14B8A6', '#5EEAD4', '#041417', '#0A2226', '#123137', '#8CB3B5', '#0E7490', '#03201C'),
    background: { style: 'gradient', gradientStart: '#041417', gradientEnd: '#06303A' }, family: 'inter', radius: { card: 12, button: 12, banner: 16 }, button: 'filled', nav: 'side_rail' },
  { id: 'neon', name: 'Verde Neón', desc: 'Verde eléctrico, secciones arriba y muro de pósters', style: 'style_verde_neon',
    palette: pal('#39FF14', '#00E5FF', '#050805', '#0E140E', '#182218', '#9DB59A', '#16A34A', '#041004'),
    background: { style: 'solid', gradientStart: '#050805', gradientEnd: '#0A1A0A' }, family: 'system', radius: { card: 4, button: 4, banner: 6 }, button: 'outlined', nav: 'top_tabs' },
  { id: 'naranja', name: 'Naranja Vibrante', desc: 'Naranja cálido con dock flotante', style: 'style_naranja',
    palette: pal('#FF7A00', '#FFC247', '#120A04', '#21140A', '#2E1D10', '#C2A58C', '#E85D04', '#1A0D00'),
    background: { style: 'gradient', gradientStart: '#120A04', gradientEnd: '#2B1405' }, family: 'poppins', radius: { card: 20, button: 30, banner: 26 }, button: 'filled', nav: 'bottom_bar' },
  { id: 'oro', name: 'Oro Negro', desc: 'Dorado de lujo sobre negro, dock dorado', style: 'style_oro',
    palette: pal('#D4AF37', '#F5D77A', '#0A0906', '#16140E', '#221F15', '#B8AE92', '#8C6E1C', '#1A1404'),
    background: { style: 'solid', gradientStart: '#0A0906', gradientEnd: '#1C170A' }, family: 'inter', radius: { card: 10, button: 8, banner: 12 }, button: 'outlined', nav: 'bottom_bar' },
  { id: 'estelar', name: 'Estelar', desc: 'Azul noche con tablero de mosaicos, sin barras', style: 'style_estelar',
    palette: pal('#6366F1', '#22D3EE', '#070A18', '#10152B', '#1A2140', '#98A2C8', '#4338CA'),
    background: { style: 'nebula', gradientStart: '#070A18', gradientEnd: '#16124A' }, family: 'poppins', radius: { card: 14, button: 18, banner: 20 }, button: 'tonal', nav: 'bottom_bar' },
];

/** Aplica un tema (y los colores que el usuario haya ajustado) a un diseño. */
export function applyVendorTheme(cfg: { theme: Record<string, unknown>; texts: Record<string, string> }, th: VendorTheme, colors?: { primary: string; accent: string; background: string }) {
  const t = cfg.theme as Record<string, any>;
  t.palette = { ...(t.palette ?? {}), ...th.palette };
  t.background = { ...(t.background ?? {}), ...th.background };
  t.typography = { ...(t.typography ?? {}), family: th.family };
  t.shape = { ...(t.shape ?? {}), cardRadius: th.radius.card, buttonRadius: th.radius.button, bannerRadius: th.radius.banner };
  t.buttonStyle = th.button;
  t.navigation = { ...(t.navigation ?? {}), style: th.nav };
  cfg.texts['style.default'] = th.style;
  if (colors) {
    const p = t.palette as Record<string, string>;
    const on = (hex: string) => { const n = parseInt(hex.slice(1), 16); const l = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255; return l > 0.6 ? '#0A0F18' : '#FFFFFF'; };
    p.primary = colors.primary; p.buttonBackground = colors.primary; p.onPrimary = on(colors.primary); p.buttonText = on(colors.primary);
    p.accent = colors.accent; p.focusRing = colors.accent;
    if (colors.background !== th.palette.background) { p.background = colors.background; t.background = { ...t.background, gradientStart: colors.background }; }
  }
}
