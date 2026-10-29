// Prueba E2E del panel (Design Studio) en un navegador real con Playwright.
// Requiere el backend en marcha con el panel compilado:  BASE=http://localhost:8080 EMAIL=... PASSWORD=... node tests/e2e/admin-panel.e2e.mjs
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const BASE = process.env.BASE ?? 'http://localhost:8080';
const OUT = process.env.OUT ?? 'docs/screenshots';
mkdirSync(OUT, { recursive: true });
const results = [];
const step = async (name, fn) => { try { await fn(); results.push(['✔', name]); } catch (e) { results.push(['✘', name, e.message.split('\n')[0]]); } };

const browser = await pw.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const consoleErrors = [];
page.on('pageerror', (e) => consoleErrors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

await step('Pantalla de acceso y credenciales incorrectas', async () => {
  await page.goto(BASE);
  await page.getByLabel('Email').fill(process.env.EMAIL);
  await page.getByLabel('Contraseña').fill('incorrecta-123');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await page.getByText('Email o contraseña incorrectos').waitFor();
  await page.screenshot({ path: `${OUT}/01-login.png` });
});

await step('Inicio de sesión', async () => {
  await page.getByLabel('Contraseña').fill(process.env.PASSWORD);
  await page.getByRole('button', { name: 'Entrar' }).click();
  await page.getByText('Secciones del inicio').waitFor();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/02-studio-telefono.png` });
});

await step('Seleccionar sección desde la vista previa', async () => {
  await page.locator('.pv-section', { hasText: 'PELÍCULAS' }).first().click();
  await page.getByLabel('Título').first().waitFor();
  await page.getByLabel('Título').first().fill('Cine para esta noche');
  await page.locator('.pv-t-section', { hasText: 'Cine para esta noche' }).waitFor();
});

await step('Cambiar a cuadrícula y tamaño grande', async () => {
  await page.getByRole('radio', { name: 'Cuadrícula' }).click();
  await page.getByRole('radio', { name: 'Grande' }).click();
  await page.locator('.pv-grid').first().waitFor();
});

await step('Deshacer y rehacer', async () => {
  await page.getByRole('button', { name: 'Deshacer' }).click();
  await page.getByRole('button', { name: 'Rehacer' }).click();
  await page.locator('.pv-grid').first().waitFor();
});

await step('Editar color principal con aviso de contraste', async () => {
  await page.getByRole('button', { name: 'Colores y temas' }).click();
  const hex = page.getByLabel('Principal (hexadecimal)', { exact: true });
  await hex.fill('#22D3EE');
  await page.locator('.contrast').first().waitFor();
  await page.screenshot({ path: `${OUT}/03-colores.png` });
});

await step('Vista previa en tableta y Android TV', async () => {
  await page.getByRole('radio', { name: 'Tableta' }).click();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}/04-tableta.png` });
  await page.getByRole('radio', { name: 'Android TV' }).click();
  await page.locator('.pv-rail').waitFor();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}/05-android-tv.png` });
  await page.getByRole('radio', { name: 'Teléfono' }).click();
});

await step('Crear banner promocional con imagen subida', async () => {
  await page.getByRole('button', { name: 'Banners' }).click();
  await page.getByRole('button', { name: 'Añadir banner' }).click();
  await page.getByRole('radio', { name: 'Fila promocional' }).waitFor();
  await page.getByLabel('Título').last().fill('Nuevo: guía de programación');
  await page.locator('input[type=file]').setInputFiles(process.env.BANNER_IMAGE);
  await page.locator('.img-opt.on[aria-label]').waitFor({ timeout: 8000 });
  await page.locator('.pv-promo', { hasText: 'Nuevo: guía de programación' }).waitFor();
  await page.screenshot({ path: `${OUT}/06-banners.png` });
});

await step('Guardar borrador', async () => {
  await page.getByRole('button', { name: 'Guardar borrador' }).click();
  await page.getByText('Borrador guardado', { exact: true }).first().waitFor();
});

await step('Bloquear publicación con configuración inválida', async () => {
  await page.getByRole('button', { name: 'Identidad' }).click();
  await page.getByLabel('Nombre comercial').fill('');
  await page.getByText('problema(s) impiden publicar').waitFor();
  await page.getByRole('button', { name: 'Publicar…' }).click();
  await page.getByText('Corrige estos problemas antes de publicar').waitFor();
  await page.screenshot({ path: `${OUT}/07-publicar-invalido.png` });
  await page.getByRole('button', { name: 'Cancelar' }).click();
  await page.getByLabel('Nombre comercial').fill('EmbyVerse');
});

await step('Publicar versión', async () => {
  await page.getByRole('button', { name: 'Publicar…' }).click();
  await page.getByText('La configuración es válida').waitFor();
  await page.getByLabel('Nota de la publicación (opcional)').fill('Prueba E2E: cuadrícula de películas y banner');
  await page.getByRole('button', { name: 'Publicar', exact: true }).click();
  await page.getByText('Publicada la versión 1').waitFor();
});

await step('La API pública entrega el diseño publicado a la app', async () => {
  const r = await page.request.get(`${BASE}/api/v1/public/config?platform=android&appVersion=1`);
  const j = await r.json();
  if (j.version !== 1) throw new Error(`versión ${j.version}`);
  const movies = j.home.sections.find((s) => s.id === 'movies');
  if (movies.title !== 'Cine para esta noche' || movies.layout !== 'grid' || movies.card.size !== 'l') throw new Error('sección no actualizada');
  if (j.theme.palette.primary !== '#22D3EE') throw new Error('color no actualizado');
  if (!j.banners.some((b) => b.title === 'Nuevo: guía de programación' && b.imageUrl)) throw new Error('banner no publicado');
});

await step('Historial de versiones', async () => {
  await page.getByRole('button', { name: 'Versiones' }).click();
  await page.getByText('Prueba E2E: cuadrícula de películas y banner').waitFor();
  await page.screenshot({ path: `${OUT}/08-versiones.png` });
});

await step('Biblioteca de recursos', async () => {
  await page.getByRole('button', { name: 'Recursos' }).click();
  await page.locator('.asset').first().waitFor();
  await page.screenshot({ path: `${OUT}/09-recursos.png` });
});

await step('Panel adaptable en teléfono', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Inicio', exact: true }).click();
  await page.waitForTimeout(400);
  await page.screenshot({ path: `${OUT}/10-panel-movil.png`, fullPage: false });
});

await browser.close();
for (const r of results) console.log(r.join('  '));
if (consoleErrors.length) console.log('Errores de consola:', consoleErrors.slice(0, 5));
const failed = results.filter((r) => r[0] === '✘').length;
console.log(`\n${results.length - failed}/${results.length} pasos correctos`);
process.exit(failed ? 1 : 0);
