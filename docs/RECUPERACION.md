# EmbyVerse: recuperación rápida

Para levantar todo otra vez, rápido y como estaba: apps (Android, Samsung, LG, PC), sitio eversetv.com,
panel, estadísticas y bloqueos. Empieza por la tabla. Si no alcanza, usa el **prompt de recuperación** del final.

Respaldos automáticos: cada lunes el flujo **Respaldo** crea la publicación `respaldo-AAAA-MM-DD` en GitHub
(Code → Releases) con todo lo necesario. Se guardan los últimos 6. Puedes correrlo a mano cuando quieras:
GitHub → Actions → **Respaldo** → Run workflow.

---

## 1. Qué hacer según lo que ves

| Lo que ves | Causa probable | Solución rápida | Tiempo |
|---|---|---|---|
| La app se ve mal **justo después de publicar un diseño** | Diseño con un problema | Panel → Versiones → restaurar la anterior → Publicar (las apps la toman en 2 min) | 2 min |
| No cargan canales en **ningún** cliente | Cambió o cayó el servidor del proveedor | Panel → editar la app → Servidor del proveedor → Publicar | 3 min |
| La app falla **después de instalar una versión nueva** | Error en esa versión | Sección 3 | 15 min |
| eversetv.com no abre, pero el enlace de github.io sí | Falla el Worker de Cloudflare | Actions → **Dominio propio** → Run workflow. Mientras, comparte `ositovela-a11y.github.io/EmbyVerseTV` | 5 min |
| Ni eversetv.com ni github.io abren | No se publicó el sitio | Actions → **Sitio (descarga y panel)** → Run workflow | 5 min |
| Estadísticas vacías, bloqueos o perfiles que no sincronizan | Falla el servidor de estadísticas | Actions → **Servidor de estadísticas** → Run workflow (revisa los secretos de Cloudflare) | 5 min |
| No llegan actualizaciones a las apps | No se generó `update.json` | Vuelve a correr "Sitio (descarga y panel)" | 5 min |
| El panel no deja entrar | Venció el token de GitHub | Sección 4 | 5 min |
| **Se perdió el repositorio o la cuenta de GitHub** | — | Sección 5 (desde el respaldo) | 30–60 min |

---

## 2. Dónde está cada cosa

- **Código (privado):** GitHub `ositovela-a11y/EmbyVerse-codigo`. `main` = lo que reciben tus clientes · `beta` = revisión.
- **Sitio y descargas (público):** `ositovela-a11y/EmbyVerseTV` (rama `gh-pages` y publicaciones `apk-latest`, `pc-latest`, `tv-latest`).
- **Contexto completo para Claude:** `CLAUDE.md` en la raíz (partes, flujos, reglas, detalles técnicos).
- **Diseños publicados:** `web-player/config/app.json` (tu app) y `web-player/config/vendors/*.json` (vendedores).
- **Activaciones de PC:** `web-player/config/licenses.json` · **Bloqueos:** `web-player/config/blocked.json`.
- **Sitio, panel y APK publicados:** rama `gh-pages` (GitHub Pages) y eversetv.com (Worker `eversetv-site`).
- **Estadísticas y perfiles sincronizados:** Cloudflare, Worker `embyverse-stats` + base D1 `embyverse-stats`.
- **Firma de las APK:** `android-app/app/embyverse-debug.keystore` (está en el repositorio). **No la cambies:**
  con otra firma los clientes tendrían que desinstalar y volver a instalar.
- **Secretos:** `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `STATS_ADMIN_KEY`, `SITE_TOKEN`, `BACKUP_PASSWORD`.
  Con el código privado, cada respaldo trae `CLAVES-cifradas.zip` (ábrelo con tu BACKUP_PASSWORD) o `CLAVES.txt`.
  Guarda BACKUP_PASSWORD fuera de GitHub (sin ella no se abre el zip). La `STATS_ADMIN_KEY` también abre el respaldo cifrado de la base de datos.
- **Dominio:** eversetv.com en tu cuenta de Cloudflare (DNS y Worker).

---

## 3. Volver a una versión anterior de la app

1. Avísale a Claude con el prompt de abajo: "la versión X falla, regresa a la Y".
2. Lo correcto es regresar el código **subiendo el número de versión** (`versionCode`): las apps solo se
   actualizan a un número mayor. Claude lo prueba en `beta` y publica cuando tú digas "publica".
3. Urgente y sin Claude: en el último respaldo están las APK que funcionaban; súbelas a la publicación
   `apk-latest` (reemplazando) y corre "Sitio (descarga y panel)". Ojo: quien ya tenga la versión mala no
   bajará a una con número menor; tendría que instalarla a mano.

---

## 4. Token nuevo para el panel

GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token.
Repositorios: `EmbyVerse-codigo` y `EmbyVerseTV`. Permisos: **Contents** y **Actions**: Read and write. Entra al panel con él.
El `SITE_TOKEN` (para publicar) también vence: créalo igual, solo para `EmbyVerseTV`, con **Contents**, **Pages** y
**Administration** en Read and write, y guárdalo en `EmbyVerse-codigo` → Settings → Secrets → `SITE_TOKEN`.

---

## 5. Levantar todo desde el respaldo (si se perdió el repositorio)

1. Descarga del último respaldo: `EmbyVerse-repo.bundle`, `CLAUDE.md` y este archivo. Guarda una copia de
   cada respaldo en tu computadora o en tu nube (si se pierde la cuenta de GitHub, se pierden sus releases).
2. Crea un repositorio nuevo en GitHub (idealmente con el mismo nombre `EmbyVerseTV`) y súbelo completo:
   ```
   git clone EmbyVerse-repo.bundle EmbyVerseTV && cd EmbyVerseTV
   git remote set-url origin https://github.com/<tu-usuario>/EmbyVerseTV.git
   git push origin --all && git push origin --tags
   ```
3. En el repositorio nuevo: Settings → Secrets → agrega `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
   `STATS_ADMIN_KEY`. Settings → Pages → rama `gh-pages`.
4. Corre en Actions, en este orden: **Servidor de estadísticas**, **Decodificador de video (FFmpeg)** y
   **Decodificador de audio (FFmpeg)** (o sube los .aar del respaldo a sus publicaciones), **APK Android**,
   **App TV**, **App PC**, **Sitio (descarga y panel)**, **Dominio propio**.
5. Si cambió el usuario de GitHub, las apps ya instaladas buscan su diseño en `ositovela-a11y.github.io`:
   pide a Claude que apunte el dominio eversetv.com (Worker) al repositorio nuevo; las apps nuevas usan la nueva dirección.
6. Base de datos (opcional, para conservar perfiles sincronizados e historial de equipos):
   `openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -in estadisticas-d1.sql.gz.cifrado -out d1.sql.gz -pass pass:TU_STATS_ADMIN_KEY`,
   descomprime y cárgala con `wrangler d1 execute embyverse-stats --remote --file=d1.sql` (o pídeselo a Claude).

---

## 6. Prompt de recuperación (cópialo completo en una conversación nueva con Claude)

```
Eres el desarrollador de mi reproductor IPTV marca blanca "EmbyVerse" (apps Android/Fire TV, Samsung, LG
y PC, panel Design Studio, sitio eversetv.com). Respóndeme en español de México y sé honesto con lo que
pruebas y lo que no.

Repositorio del código (privado): github.com/ositovela-a11y/EmbyVerse-codigo (agrégalo a la sesión y clónalo).
Sitio y descargas (público): github.com/ositovela-a11y/EmbyVerseTV.
Lee primero CLAUDE.md: ahí están las partes, los flujos de GitHub Actions, las reglas y los detalles técnicos.
Reglas clave: publicar a mis clientes (unir beta → main) SOLO cuando yo diga "publica"; antes prueba en beta y
mándame resultados y capturas. Nunca me pidas pegar claves en el chat. No cambies la firma de las APK.

SITUACIÓN: [describe qué pasa: qué ves, desde cuándo, en qué equipos; si perdiste el repositorio, di que
tienes el respaldo (EmbyVerse-repo.bundle) y en qué usuario de GitHub lo vas a subir]

Haz esto en orden:
1. Revisa los últimos commits de main y las últimas ejecuciones de Actions (gh run list) para ver qué cambió y qué falló.
2. Si es el diseño publicado, restaura la versión anterior de la config y publícala.
3. Si es una APK, regresa el código a la última versión buena SUBIENDO el versionCode, pruébala en beta y avísame.
4. Si es el sitio, el dominio o las estadísticas, vuelve a correr su flujo y verifica que responda.
5. Si es una recuperación desde el respaldo, sigue docs/RECUPERACION.md sección 5 conmigo, paso a paso.
6. Dime en pocas palabras qué estaba mal, qué hiciste, qué probaste y qué falta probar en un equipo real.
```
