# Seguridad

## App Android

| Medida | Implementación |
|---|---|
| Contraseñas de proveedores | AES-256-GCM con clave no extraíble en **Android Keystore** (`CredentialStore`). Si la clave se invalida, se pide de nuevo la contraseña |
| Sin credenciales en la base de datos | Las URLs Xtream se guardan con marcadores `{u}/{p}` y se completan en memoria al reproducir |
| Sin contraseñas en logs | No hay interceptor de logs HTTP; `UrlSafety.redact` limpia credenciales de cualquier URL; `toString()` de credenciales oculta la contraseña; R8 elimina `Log.d/v` en release |
| Copias de seguridad | `allowBackup=false` y reglas de exclusión para credenciales, base de datos y archivos |
| Sin secretos en el APK | Solo se incluye la URL pública del backend (no es secreta). La firma de producción la aporta el propietario fuera del repositorio |
| Configuración remota | Solo HTTPS en builds de producción; validación estricta del esquema; es datos, nunca código; rutas internas cerradas; imágenes solo https |
| Entradas del usuario | Solo URLs http/https; esquemas `file:`, `content:`, `javascript:` y `data:` rechazados en listas; límites de tamaño de lista, guía y configuración |
| Tráfico HTTP sin cifrar | Permitido solo porque muchos proveedores IPTV lo usan; la app lo indica en la fuente. Si tu despliegue solo usa HTTPS, cambia `cleartextTrafficPermitted` a `false` en `res/xml/network_security_config.xml` |

## Backend y panel

| Medida | Implementación |
|---|---|
| Contraseñas del panel | scrypt (N=16384, r=8, p=1, sal de 16 bytes), comparación en tiempo constante, política de 12+ caracteres con letras y números |
| Sesiones | Token aleatorio de 256 bits; en la base de datos solo su SHA-256; cookie `HttpOnly`, `SameSite=Strict`, `Secure` + `__Host-` en producción; caducidad por inactividad (2 h) y absoluta (12 h); se revocan al cambiar contraseña, rol o desactivar |
| CSRF | Token por sesión obligatorio en la cabecera `X-CSRF-Token` para toda modificación |
| Fuerza bruta | Bloqueo de 15 min tras 5 fallos por cuenta; límite por IP y por email; mensaje genérico que no revela si el email existe |
| Permisos | Comprobados en el servidor en cada endpoint (el panel solo oculta botones) |
| Abuso | Límites de peticiones (120/min API pública, 600/min panel), tamaño máximo de cuerpo, timeouts de cabecera y petición |
| Subida de archivos | Tipo detectado por los bytes (PNG/JPEG/WebP; **SVG rechazado**), 3 MB, 4096 px; nombre de archivo = hash; sin rutas del usuario en el disco |
| Cabeceras | CSP estricta (`script-src 'self'`, `frame-ancestors 'none'`), `X-Frame-Options`, `nosniff`, `Referrer-Policy: no-referrer`, HSTS en producción, `Permissions-Policy` |
| Auditoría | Inicios de sesión (correctos y fallidos), borradores, publicaciones, reversiones, imágenes, aprobaciones y usuarios, con IP |
| Secretos | Ninguno en el código. El propietario inicial se crea por variables de entorno o CLI. `PUBLIC_BASE_URL` debe ser https en producción (el servidor no arranca si no) |

**Separación de accesos:** las cuentas del panel (diseño de la app) no tienen relación con las credenciales de los proveedores IPTV, que nunca salen del dispositivo del usuario.

## Recomendaciones de operación

- Sirve el backend solo detrás de HTTPS (ver `docker-compose.example.yml`).
- Restringe el acceso al panel por IP o VPN si es posible (la API pública debe seguir abierta).
- Haz copia de seguridad de `DATA_DIR` y del keystore de firma.
- Revisa la auditoría periódicamente y desactiva las cuentas que ya no se usen.
