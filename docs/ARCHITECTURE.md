# Arquitectura

```
┌───────────────────────────── Android (android-app/) ─────────────────────────────┐
│ :app  Compose UI (MVVM) ─ ViewModels ─ Repositorios ─ Room / DataStore / Keystore │
│       Media3 (PlayerActivity)   WorkManager (sincronización cada 12 h)            │
│ :core Kotlin/JVM puro: M3U · Xtream · XMLTV · RemoteConfig · Resolver · Diff      │
└───────────────┬───────────────────────────────────────────┬──────────────────────┘
                │ HTTPS: GET /api/v1/public/config (ETag)   │ HTTP/HTTPS directo
                ▼                                           ▼
┌──────── backend/ (Node 22 + TS) ────────┐        Proveedores IPTV / listas M3U
│ API pública · API del panel · SQLite    │        (del usuario; EmbyVerse no los aloja)
│ Biblioteca de imágenes · Auditoría      │
│ Sirve admin-web/dist (mismo origen)     │◄──── Design Studio (admin-web/, React)
└─────────────────────────────────────────┘
```

## Android

**Módulos.** `:core` no depende de Android, así que la lógica crítica (análisis de listas, adaptadores de proveedor, validación de la configuración) se prueba en la JVM sin emulador. `:app` contiene la interfaz y la integración con el sistema.

**Capas de `:app`.**

| Paquete | Responsabilidad |
|---|---|
| `data/db` | Room: fuentes, canales, películas, series, episodios, categorías, perfiles, favoritos, progreso/historial, EPG |
| `data/secure` | `CredentialStore`: contraseñas cifradas con AES-256-GCM, clave en Android Keystore |
| `data/net` | OkHttp y `OkHttpFetcher` (implementación de `HttpFetcher` del núcleo). Sin interceptor de logs |
| `data/prefs` | DataStore: preferencias por perfil (apariencia) y de reproducción |
| `data/repo` | `SourceRepository` (probar, guardar, sincronizar sin duplicar), `CatalogRepository`, `LibraryRepository`, `EpgRepository`, `ConfigRepository` |
| `ui/` | Tema derivado de la configuración, componentes con foco para TV, navegación adaptable, pantallas |
| `player/` | `PlayerActivity` + `PlayerViewModel` (Media3), cambio de canal, progreso, reconexión |
| `work/` | `SyncWorker`: configuración remota y catálogos en segundo plano |

**Inyección de dependencias manual** (`di/AppContainer`): un grafo pequeño sin reflexión ni procesadores adicionales.

**Sincronización sin duplicados.** Cada elemento tiene un id estable (id del proveedor Xtream o hash SHA-256 de la URL en M3U). `CatalogDiff` compara huellas y aplica solo altas, cambios y bajas dentro de una transacción.

**Credenciales fuera de la base de datos.** Las URL de streams Xtream se guardan con marcadores `{u}/{p}`; la contraseña se descifra e inserta en memoria justo antes de reproducir.

**Configuración remota.** `ConfigRepository` descarga con `If-None-Match`, `ConfigManager` (en `:core`) valida, guarda la versión activa y la anterior con escritura atómica, y revierte sola si la app no consigue abrir el inicio dos arranques seguidos con una versión nueva. La interfaz solo usa componentes nativos ya compilados; la configuración es datos, nunca código.

**Preferencias del usuario.** `ConfigResolver` combina el diseño del administrador con las preferencias del perfil local respetando los permisos (`userCustomization`) y las secciones bloqueadas.

## Backend

- Node.js 22 con TypeScript y **cero dependencias de ejecución**: `node:http`, `node:sqlite`, `node:crypto`. Menos superficie de ataque y menos mantenimiento.
- SQLite en modo WAL. Adecuado para un panel con pocos administradores y una API pública cacheable (ETag + `Cache-Control: max-age=60`). Para alta disponibilidad, coloca una CDN delante de `/api/v1/public/*` o migra a PostgreSQL (la capa de datos está concentrada en `db.ts`, `auth.ts`, `assets.ts` y `config/service.ts`).
- El validador (`src/config/schema.ts`) y el resolvedor (`src/config/resolve.ts`) no dependen de Node: el panel los importa para validar y previsualizar con las mismas reglas.

## Design Studio

React sin bibliotecas de UI. La vista previa (`preview/AppPreview.tsx`) reproduce la pantalla de inicio de la app con el borrador: paleta, tipografía, radios, espaciado, estilo de botón, menú (inferior, lateral o pestañas; lateral siempre en TV y tabletas grandes), secciones resueltas y banners activos. El contenido de muestra es inventado y los pósters son degradados generados, sin imágenes con derechos.

## Por qué estas tecnologías

| Elección | Motivo |
|---|---|
| Kotlin + Compose + Material 3 | Requisito; una sola UI para teléfono y TV con navegación por foco |
| Media3 ExoPlayer | Requisito; HLS, DASH, MPEG-TS y progresivo; cabeceras por stream vía OkHttp |
| Room + DataStore | Requisito; consultas reactivas con Flow |
| kotlinx.serialization (árbol JSON) | Los paneles IPTV mezclan tipos (`"1"`/`1`/`null`); se lee de forma tolerante sin clases generadas |
| Node sin dependencias | Desplegable en cualquier VPS o contenedor; nada que auditar en `node_modules` en producción |
| esbuild | Compilación del panel en milisegundos sin configuración compleja |
