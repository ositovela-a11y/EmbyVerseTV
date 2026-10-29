# Configuración remota — esquema v1

Implementaciones que DEBEN coincidir (ambas se prueban con `tests/fixtures/config`):
`android-app/core/.../config/RemoteConfigParser.kt` y `backend/src/config/schema.ts`.
Ejemplo completo: `shared/default-config.json`.

## Principios

1. **Es datos, no código.** La app solo elige entre componentes nativos compilados (catálogo cerrado de secciones, rutas internas, estilos) y ajusta parámetros acotados. Nunca se ejecuta nada recibido del servidor.
2. **Campos desconocidos:** se ignoran (un panel nuevo no rompe apps antiguas).
3. **Campos ausentes:** se completan con el diseño empaquetado en la app.
4. **Valores inválidos:** se rechaza la configuración entera; la app mantiene la última válida.
5. **`schemaVersion` > 1:** rechazada por esta app. **`minAppVersionCode`:** el servidor entrega a cada app la última versión compatible.
6. **Tipos de sección desconocidos:** se aceptan con aviso y la app los omite.

## Campos y límites

| Ruta | Tipo / valores | Límites |
|---|---|---|
| `schemaVersion` | entero, obligatorio | = 1 |
| `version`, `publishedAt` | los asigna el servidor al publicar | |
| `minAppVersionCode` | entero | ≥ 1 |
| `brand.appName` | texto | 1–40 |
| `brand.tagline` | texto o null | 0–80 |
| `brand.logo.variant` | `bundled_full`, `bundled_planet`, `asset` | `asset` exige `url` https (logotipo aprobado) |
| `theme.palette.*` | `#RRGGBB` o `#AARRGGBB` | claves: primary, secondary, accent, background, surface, surfaceVariant, onBackground, onSurface, textSecondary, onPrimary, buttonBackground, buttonText, focusRing, error |
| `theme.background.style` | `solid`, `gradient`, `nebula` | + gradientStart/gradientEnd |
| `theme.typography.family` / `scale` | `system`, `inter`, `poppins` / número | 0,8–1,4 |
| `theme.shape.cardRadius` / `buttonRadius` / `bannerRadius` | entero dp | 0–32 / 0–32 / 0–40 |
| `theme.spacing.screenPadding` / `sectionGap` / `cardGap` | entero dp | 8–48 / 8–64 / 4–32 (TV: margen mínimo 40) |
| `theme.glow.enabled` / `intensity` | booleano / número | 0–1 |
| `theme.buttonStyle` | `filled`, `tonal`, `outlined` | |
| `theme.navigation.style` / `showLabels` | `bottom_bar`, `side_rail`, `top_tabs` / booleano | en TV y ≥ 840 dp siempre lateral |
| `themes[]` | `{id, name, palette (parcial)}` | ≤ 12, id `[a-z0-9_-]{1,32}` único |
| `userCustomization.allow*` | booleanos | ThemeSelection, AccentColor, CardSize, HideSections, ReorderSections |
| `userCustomization.accentChoices` | colores | ≤ 12 |
| `userCustomization.lockedSections` | ids de secciones existentes | |
| `home.sections[]` | `{id, type, enabled, title, subtitle?, layout, card{aspect,size}, maxItems}` | 1–30; title ≤ 60; subtitle ≤ 100; maxItems 1–100 |
| `type` | `hero_banner`, `promo_banners`, `continue_watching`, `live_tv`, `movies`, `series`, `recently_added`, `favorites`, `recommended` | |
| `layout` | `hero` (solo hero_banner), `carousel`, `grid` | |
| `card.aspect` / `size` | `poster`, `landscape`, `square` / `s`, `m`, `l` | |
| `banners[]` | `{id, placement, title, subtitle?, imageUrl?, action{route}, startsAt?, endsAt?}` | ≤ 20; title 1–80; subtitle ≤ 160; imagen solo https; `endsAt > startsAt` |
| `placement` | `hero`, `promo` | solo en la pantalla de inicio |
| `action.route` | `home`, `live`, `movies`, `series`, `search`, `favorites`, `settings`, `sources` o null | sin URLs externas |
| `texts` | `{clave: texto}` | ≤ 100; clave `[A-Za-z0-9_.]{1,64}`; texto ≤ 500 |
| Tamaño total | | ≤ 256 KB |

Textos usados hoy por la app: `home.greeting`, `home.emptySources`, `support.contact`.

## Ciclo de vida en el dispositivo

1. Al abrir la app (durante la animación de bienvenida, máx. 2,5 s) y cada 12 h, la app pide la configuración con `If-None-Match`.
2. Si es válida, compatible y más nueva, pasa a ser la activa; la anterior se guarda como respaldo (escritura atómica).
3. Si el inicio se muestra estable 3 s, se marca como sana.
4. Si la app arranca **dos veces seguidas** sin llegar a ese punto, se revierte sola a la anterior y la versión queda bloqueada en ese dispositivo.
5. Sin conexión se usa la última válida; si nunca hubo, el diseño empaquetado.
6. En *Ajustes › Información y soporte* el usuario ve la versión del diseño y puede volver a la anterior.

En el servidor, *Revertir* publica una versión **nueva** con el contenido antiguo: así las apps la detectan como más reciente.
