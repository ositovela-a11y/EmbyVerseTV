# EmbyVerse en TVs Samsung y LG (modo desarrollador)

Las TVs Samsung (Tizen) y LG (webOS) no instalan APK. Para ellas existe **la misma app EmbyVerse en versión web para TV**: misma cuenta, mismos colores, logo, mensajes y suspensión de clientes de tu panel. Se instala **en modo desarrollador**, sin pasar por ninguna tienda, así que nadie la puede rechazar ni bajar.

| | Samsung | LG |
|---|---|---|
| Archivo | `EmbyVerse-Samsung.wgt` (se firma una vez con tu certificado) | `EmbyVerse-LG.ipk` (listo) |
| Qué necesitas | PC con Tizen Studio y cuenta Samsung (gratis) | PC con webOS CLI y cuenta LG Developer (gratis) |
| ¿Cuánto dura instalada? | Se queda instalada. Se pierde si restableces la TV de fábrica | **El modo desarrollador de LG caduca a las 50 h** si no se renueva; al caducar, LG borra la app (ver abajo) |
| Actualizaciones | **Solas**: al abrir, la app carga la versión nueva desde tu sitio (sin reinstalar) | **Solas**, igual |

Los archivos están en GitHub → **Releases** → `tv-latest` (o `tv-beta` para la versión de revisión), y también en tu sitio: `…/EmbyVerseTV/tv/`.

---

## Samsung (Tizen 4.0 o más nuevo, TVs 2018+)

### 1. Poner la TV en modo desarrollador (una sola vez)
1. En la TV abre **Apps** (Aplicaciones).
2. Con el control escribe **1 2 3 4 5**. Aparece «Developer mode».
3. Ponlo en **ON** y escribe la **IP de tu PC** (en Windows: `ipconfig`). Acepta y **reinicia la TV** (apágala y préndela con el control, espera 1 minuto).

### 2. Preparar la PC (una sola vez)
1. Instala **Tizen Studio** (versión con IDE) desde developer.tizen.org.
2. Abre **Package Manager** → pestaña *Extension SDK* → instala **TV Extensions** y **Samsung Certificate Extension**.
3. Abre **Device Manager** → *Remote Device Manager* → **+** → escribe la IP de la TV → conecta. (Para ver la IP de la TV: Configuración → General → Red → Estado de red.)
4. Abre **Certificate Manager** → **+** → **Samsung** → tipo **TV** → crea el certificado de autor y el de distribuidor iniciando sesión con tu **cuenta Samsung**. En «DUID» elige la TV conectada (puedes agregar más TVs después).

### 3. Firmar e instalar
En la carpeta de Tizen Studio (`tizen-studio/tools/ide/bin`) abre una terminal y ejecuta:
```
tizen package -t wgt -s <nombre-de-tu-certificado> -- RUTA/EmbyVerse-Samsung.wgt
sdb connect IP_DE_LA_TV
tizen install -n RUTA/EmbyVerse-Samsung.wgt -t <nombre-de-la-tv-que-muestra-sdb-devices>
```
La app **EmbyVerse** aparece en Apps. Para más TVs: agrega su DUID al certificado de distribuidor y repite el paso 3.

---

## LG (webOS 4.0 o más nuevo, TVs 2018+)

### 1. Cuenta y modo desarrollador (una sola vez)
1. Crea una cuenta gratis en **webostv.developer.lge.com** (Sign in → Create account).
2. En la TV, desde **LG Content Store**, instala la app **Developer Mode**. Ábrela, entra con tu cuenta LG Developer.
3. Pon **Dev Mode Status** en **ON** (la TV se reinicia). Vuelve a abrir Developer Mode y pon **Key Server** en **ON**. Anota la **IP** que muestra.

### 2. Preparar la PC (una sola vez)
1. Instala Node.js y luego: `npm install -g @webos-tools/cli`
2. Agrega la TV: `ares-setup-device` → *add* → nombre `tv`, IP de la TV, puerto `9922`, usuario `prisoner`.
3. Descarga la llave: `ares-novacom --device tv --getkey` y escribe la **Passphrase** que muestra la app Developer Mode.

### 3. Instalar
```
ares-install --device tv RUTA/EmbyVerse-LG.ipk
```
La app **EmbyVerse** aparece en la lista de apps de la TV.

### Importante: renovar el modo desarrollador de LG
LG da sesiones de desarrollador de **50 horas**. Antes de que se acaben, abre la app **Developer Mode** en la TV y pulsa **Extend** (vuelve a 50 h). **Si se acaba, LG borra las apps instaladas en modo desarrollador** y hay que volver a instalar (paso 3). Desde la PC también se puede renovar: `ares-extend-dev --device tv`.

---

## Qué trae la versión de TV
Inicio con banner de novedades, continuar viendo, partidos del día, evento destacado con cuenta regresiva, canales destacados, sagas y colecciones · TV en vivo con carpetas, favoritos, historial y guía (ahora/sigue) · Guía de TV · Películas y series con carpetas, fichas, temporadas y episodios · Búsqueda inteligente (sin acentos, con faltas de ortografía, por género) · Reproductor: cambio de canal con ▲▼ y CH+/−, número de canal con el teclado del control, canal anterior, lista de canales con ◀, audio y subtítulos, formato de pantalla, favoritos, saltar intro, siguiente episodio automático, continuar donde te quedaste · Perfiles con avatar, perfil infantil (solo contenido para niños, colores alegres, límite diario y PIN para salir), ocultar adultos, idioma preferido por perfil · Mensajes del panel, suspensión de clientes, servidor fijo o pedido por URL, colores y logo del panel · 60 fondos animados de temporada, protector de pantalla, sonidos de navegación, modo para equipos lentos, prueba de velocidad · Menú lateral o barra superior.

**Diferencias con Android:** no hay Chromecast, imagen en imagen, widget, notificaciones con la app cerrada ni recordatorios en segundo plano (las TVs no lo permiten a apps instaladas en modo desarrollador); el modo noche de audio no está disponible en el reproductor de las TVs.

## Si algo falla
- **No abre / pantalla negra:** la TV debe ser 2018 o más nueva (Samsung Tizen 4+, LG webOS 4+).
- **«No se pudo conectar con el servidor»:** revisa la dirección del servidor en tu panel. Algunas TVs no aceptan servidores `https` con certificado vencido.
- **Un canal no reproduce:** la app prueba sola el otro formato (HLS ↔ .ts). Si aún falla, el canal no es compatible con el reproductor de esa TV.
