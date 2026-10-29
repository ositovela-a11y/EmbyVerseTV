# API del backend (v1)

Base: la URL pública del servidor (`PUBLIC_BASE_URL`). Todas las respuestas son JSON salvo imágenes. Errores: `{ "error": "codigo", "message": "texto legible", "details"?: …, "requestId": "…" }`.

## Pública (la app Android)

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/healthz` | Estado y versión publicada |
| GET | `/api/v1/public/config?platform=android&appVersion=N` | Última configuración **compatible** con `appVersion` (`minAppVersionCode ≤ N`). `ETag` + `Cache-Control: public, max-age=60`. `304` con `If-None-Match`. `404 not_published` si no hay ninguna |
| GET | `/api/v1/public/assets/{id}.{png|jpg|webp}` | Imagen de la biblioteca, caché de un año (inmutable, el nombre es el hash) |

La configuración pública no contiene secretos. Límite: 120 peticiones/min por IP.

## Panel (sesión)

Autenticación por cookie `HttpOnly`, `SameSite=Strict` (`Secure` y prefijo `__Host-` en producción). Toda petición que modifica datos exige la cabecera `X-CSRF-Token` con el token devuelto por `login` o `me`. Sesión: 2 h de inactividad, 12 h máximo. Límite: 600 peticiones/min por IP.

| Método | Ruta | Permiso | Descripción |
|---|---|---|---|
| POST | `/api/v1/auth/login` `{email,password}` | — | Bloqueo de 15 min tras 5 fallos; 20 intentos/15 min por IP |
| POST | `/api/v1/auth/logout` | sesión | |
| GET | `/api/v1/auth/me` | sesión | Usuario, permisos y token CSRF |
| POST | `/api/v1/auth/password` `{current,next}` | sesión | Cierra las demás sesiones |
| GET | `/api/v1/config/draft` | config:read | Borrador (o la última versión), validación y URL pública |
| PUT | `/api/v1/config/draft` `{config}` | config:edit | Guarda aunque no sea válido; devuelve la validación |
| DELETE | `/api/v1/config/draft` | config:edit | Descarta el borrador |
| POST | `/api/v1/config/validate` `{config}` | config:read | Valida sin guardar |
| POST | `/api/v1/config/publish` `{note?, force?}` | config:publish | `422` si no es válida; `409 stale_draft` si otra persona publicó entretanto |
| GET | `/api/v1/config/versions` | config:read | Historial |
| GET | `/api/v1/config/versions/{v}` | config:read | Contenido de una versión |
| POST | `/api/v1/config/versions/{v}/restore-draft` | config:edit | Carga una versión como borrador |
| POST | `/api/v1/config/rollback` `{toVersion}` | config:publish | Publica el contenido de `toVersion` como versión nueva |
| GET | `/api/v1/assets` | assets:read | Biblioteca |
| POST | `/api/v1/assets?kind=banner|image|logo` (cuerpo binario, `X-Filename`) | assets:write | PNG/JPEG/WebP ≤ 3 MB y ≤ 4096 px, detectado por contenido. Los logotipos quedan pendientes de aprobación |
| POST | `/api/v1/assets/{id}/approve` `{approved}` | assets:approve | Solo propietarios |
| DELETE | `/api/v1/assets/{id}` | assets:write | `409` si se usa en el borrador o en la versión publicada |
| GET | `/api/v1/audit?limit=` | audit:read | Registro de operaciones |
| GET/POST | `/api/v1/users` | users:manage | Listar / crear |
| PATCH/DELETE | `/api/v1/users/{id}` | users:manage | Rol, nombre, desactivar, restablecer contraseña / eliminar. Siempre queda un propietario activo |

## Roles

| Permiso | Propietario | Publicador | Editor | Solo lectura |
|---|:-:|:-:|:-:|:-:|
| Ver diseño, versiones y recursos | ✓ | ✓ | ✓ | ✓ |
| Editar borrador, subir imágenes | ✓ | ✓ | ✓ | |
| Publicar y revertir | ✓ | ✓ | | |
| Ver auditoría | ✓ | ✓ | | |
| Aprobar logotipos, gestionar usuarios | ✓ | | | |

## Reglas de publicación adicionales del servidor

- Logotipo `asset`: debe ser un recurso de tipo logotipo **aprobado** de esta biblioteca.
- `imageUrl` de banners: un recurso de esta biblioteca o un host de `ALLOWED_IMAGE_HOSTS`.
- Cada publicación asigna `version` creciente y `publishedAt`; el historial nunca se reescribe.
