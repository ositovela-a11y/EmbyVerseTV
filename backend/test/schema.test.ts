import assert from 'node:assert/strict';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, it } from 'node:test';
import { activeBanners, readableOn, resolvePalette, resolveSections } from '../src/config/resolve.js';
import { parseConfig, parseIso } from '../src/config/schema.js';
import { defaults, fixture, repoRoot } from './helpers.js';

const list = (dir: string) => readdirSync(join(repoRoot, 'tests/fixtures/config', dir)).filter((f) => f.endsWith('.json'));

describe('validación de configuración (mismos fixtures que la app Kotlin)', () => {
  it('la configuración predeterminada es válida y se reproduce idéntica', () => {
    const r = parseConfig(defaults, defaults);
    assert.equal(r.ok, true, r.errors.join('\n'));
    assert.deepEqual(r.config, defaults);
  });

  for (const f of list('valid')) {
    it(`acepta valid/${f}`, () => {
      const r = parseConfig(fixture(`config/valid/${f}`), defaults);
      assert.equal(r.ok, true, r.errors.join('\n'));
    });
  }

  for (const f of list('invalid')) {
    it(`rechaza invalid/${f}`, () => {
      const r = parseConfig(fixture(`config/invalid/${f}`), defaults);
      assert.equal(r.ok, false);
      assert.ok(r.errors.length > 0);
    });
  }

  it('tipos de sección futuros: aviso y se omiten al resolver', () => {
    const r = parseConfig(fixture('config/valid/future-section-type.json'), defaults);
    assert.ok(r.warnings.some((w) => w.includes('live_sports_scores')));
    assert.deepEqual(resolveSections(r.config!, {}).map((s) => s.id), ['hero', 'movies']);
  });

  it('fechas ISO con zona horaria', () => {
    assert.equal(parseIso('2026-10-29T00:00:00Z'), 1793232000);
    assert.equal(parseIso('2026-10-28T18:00:00-06:00'), 1793232000);
    assert.equal(parseIso('2026-13-01T00:00:00Z'), null);
  });

  it('rechaza configuraciones demasiado grandes', () => {
    assert.equal(parseConfig('x'.repeat(300 * 1024), defaults).ok, false);
  });
});

describe('resolución de preferencias de usuario (misma lógica que ConfigResolver.kt)', () => {
  const cfg = parseConfig(defaults, defaults).config!;

  it('tema y acento solo si están permitidos', () => {
    const p = resolvePalette(cfg, { themeId: 'electric', accentColor: '#A855F7' });
    assert.equal(p.primary, '#A855F7');
    assert.equal(p.buttonText, '#04110A');
    assert.equal(p.accent, '#22D3EE');
    assert.deepEqual(resolvePalette(cfg, { accentColor: '#123456' }), cfg.theme.palette);
    const locked = { ...cfg, userCustomization: { ...cfg.userCustomization, allowThemeSelection: false, allowAccentColor: false } };
    assert.deepEqual(resolvePalette(locked, { themeId: 'electric', accentColor: '#A855F7' }), cfg.theme.palette);
  });

  it('ocultar y reordenar respeta las secciones bloqueadas', () => {
    const prefs = { hiddenSections: ['hero', 'promos', 'recommended'], sectionOrder: ['series', 'movies', 'hero', 'live'] };
    assert.deepEqual(resolveSections(cfg, prefs).map((s) => s.id), ['hero', 'series', 'movies', 'live', 'continue', 'new', 'favorites']);
  });

  it('banners por ventana de publicación', () => {
    const c = parseConfig(fixture('config/valid/asset-logo-and-banners.json'), defaults).config!;
    assert.equal(activeBanners(c, 'promo', 1793232000).length, 1);
    assert.equal(activeBanners(c, 'promo', 1790812799).length, 0);
    assert.equal(activeBanners(c, 'promo', 1798848000).length, 0);
  });

  it('contraste legible', () => {
    assert.equal(readableOn('#7BE52F'), '#04110A');
    assert.equal(readableOn('#1E3A8A'), '#FFFFFF');
  });
});
