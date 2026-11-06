# EmbyVerse en TVs Samsung y LG, sin computadora

Las TVs Samsung y LG no instalan APK. Por eso existe **la misma app EmbyVerse en versión para TV**. Usa la misma cuenta, tus colores y logo, y respeta los mensajes y las suspensiones de tu panel.

Se instala **en modo desarrollador**, sin pasar por ninguna tienda, así que nadie la puede rechazar ni bajar. **No hace falta PC.** El cliente lo hace desde su **celular Android con EmbyVerse**, conectado al **mismo Wi-Fi** que la TV.

| | LG | Samsung |
|---|---|---|
| Con qué se instala | La app EmbyVerse del celular (Ajustes → **Instalar en TV Samsung o LG**) | El instalador gratuito **Apps2Samsung** en el celular |
| Cuenta que se necesita | Cuenta **LG Developer** (gratis) | Cuenta **Samsung** (gratis) |
| ¿Caduca? | LG da 50 h de modo desarrollador. **EmbyVerse lo renueva sola** (ver abajo) | No caduca |
| Actualizaciones | Solas, al abrir la app | Solas, al abrir la app |
| TVs compatibles | webOS 4 o más nueva (2018+) | Tizen 4 o más nueva (2018+) |

---

## LG paso a paso (lo que hace el cliente)

**En la TV (una sola vez):**
1. Abre **LG Content Store**, busca **Developer Mode** e instálala.
2. Abre **Developer Mode** e inicia sesión con una cuenta **LG Developer**. Se crea gratis en `webostv.developer.lge.com` → *Sign in* → *Create account*. Puede ser la del cliente o una que tú le des; mira «Cuentas LG» abajo.
3. Pon **Dev Mode Status** en **ON**. La TV se reinicia sola.
4. Vuelve a abrir **Developer Mode** y pon **Key Server** en **ON**. Deja esa pantalla abierta: ahí se ven la **IP** y la **Passphrase** (6 letras).

**En el celular:**

5. Abre **EmbyVerse** → **Ajustes** → **Instalar en TV Samsung o LG** → **TV LG**.
6. Escribe la **IP** y la **Passphrase** que muestra la TV y toca **Instalar EmbyVerse**.
7. En menos de un minuto aparece «EmbyVerse quedó instalada». La app se abre sola en la TV y el modo desarrollador queda renovado por 50 h.

### Cómo se mantiene la LG sin que el cliente haga nada
- **Sola:** cada vez que el cliente abre EmbyVerse en la TV, si pasaron 20 horas desde la última renovación, la app vuelve a pedir las 50 h. La pantalla de Developer Mode parpadea 3 segundos y regresa a EmbyVerse. Nunca lo hace a media película o canal.
- **Desde tu celular, a distancia:** en tu panel (Design Studio) → **Temporada y eventos** → **TVs LG: modo desarrollador** → **Renovar en todas las LG** → **Publicar**. Las LG con la app abierta lo reciben en menos de 10 minutos y las demás al abrirla. El panel funciona en el navegador del celular.
- **Desde el celular del cliente:** la misma pantalla del instalador tiene **Renovar 50 h** (en el mismo Wi-Fi).
- En la TV, **Ajustes → Modo desarrollador LG** muestra cuándo se renovó y tiene un botón para renovar al momento.

**Lo único que no se puede evitar:** si la TV pasa **más de 50 horas sin abrir EmbyVerse** (vacaciones, TV desconectada), LG borra la app. El cliente repite los pasos 4 a 7. La IP y la Passphrase quedan guardadas en su celular, así que es un solo toque. Recomiéndale abrir EmbyVerse al menos una vez cada dos días.

### Cuentas LG
- Lo más simple es que **cada cliente cree su propia cuenta LG Developer** (gratis, con su correo).
- Si prefieres dársela tú, crea una cuenta **solo para eso**, nunca tu cuenta personal. Puede usarse en varias TVs, pero LG no publica un límite, y si LG la bloquea afectaría a todas esas TVs a la vez.

---

## Samsung paso a paso (lo que hace el cliente)

**En el celular:**
1. En EmbyVerse → **Ajustes** → **Instalar en TV Samsung o LG** → **TV Samsung**, toca **Descargar Apps2Samsung** e instálala.
   - Es un instalador gratuito de código abierto, independiente de Samsung.
   - Android pedirá permitir instalar apps de esa fuente: acepta.
2. En la misma pantalla toca **Descargar EmbyVerse para Samsung**. Se guarda `EmbyVerse-Samsung.wgt` en Descargas.
3. Anota la **IP del celular**: Ajustes del celular → Wi-Fi → toca tu red.

**En la TV (una sola vez):**

4. Entra a **Apps**. Con el control escribe **1 2 3 4 5**. Aparece «Developer mode».
5. Ponlo en **ON** y escribe la **IP del celular**. Acepta.
6. **Apaga y prende la TV** con el control y espera 1 minuto.

**En el celular:**

7. Abre **Apps2Samsung**. Encuentra la TV (o escribe su IP: TV → Configuración → General → Red → Estado de red).
8. Elige **instalar un archivo .wgt propio** y selecciona `EmbyVerse-Samsung.wgt` de Descargas.
9. La primera vez te pide **iniciar sesión con una cuenta Samsung** para firmar la app para esa TV. Inicia sesión.
10. Listo: **EmbyVerse** aparece en Apps de la TV. En Samsung no caduca y se actualiza sola.

---

## Qué trae la versión de TV
Inicio con banner, continuar viendo, partidos del día, evento con cuenta regresiva, canales destacados, sagas · TV en vivo con carpetas, favoritos y guía · películas y series con fichas, temporadas y episodios · búsqueda inteligente · cambio de canal con flechas, CH+/− y número, canal anterior, audio y subtítulos, formato de pantalla, saltar intro, siguiente episodio · perfiles, perfil infantil, ocultar adultos · mensajes del panel, suspensión de clientes · 60 fondos animados, protector de pantalla, modo para equipos lentos, prueba de velocidad.

**No disponible en TVs:**
- Chromecast
- Imagen en imagen
- Widget
- Avisos con la app cerrada
- Modo noche de audio

## Si algo falla
- **«No se pudo pedir la llave a la TV» (LG):**
  - **Key Server** debe estar en ON en Developer Mode.
  - El celular debe estar en el mismo Wi-Fi que la TV, no con datos móviles.
  - Revisa la IP.
- **«La Passphrase no es correcta» (LG):** escríbela igual que en la TV. Si la TV la cambió, apaga y prende Key Server y usa la nueva.
- **Apps2Samsung no encuentra la TV:** la IP en «Developer mode» de la TV debe ser la del celular, y la TV se debe reiniciar después de ponerla.
- **Un canal no reproduce:** la app prueba sola el otro formato (HLS ↔ .ts). Si aún falla, ese canal no es compatible con esa TV.

## Si el cliente tiene iPhone
El instalador de LG está en la app Android. Con iPhone hace falta que alguien con un celular Android en esa casa lo haga una vez. Apps2Samsung para iPhone está en prueba y requiere computadora para instalarse.
