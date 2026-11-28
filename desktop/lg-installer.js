// Instalador de la app en TVs LG (webOS) desde la PC, sin herramientas de LG: hace lo mismo que el instalador del
// celular Android (LgTvInstaller.kt) y que ares-install. La TV debe estar en modo desarrollador y en la misma red.
//  1. Con «Key Server» encendido, la TV entrega su llave en http://IP:9991/webos_rsa, cifrada con la «Passphrase».
//  2. Con esa llave se entra por SSH (usuario prisoner, puerto 9922), se sube el .ipk y se instala.
//  3. «Renovar» abre Developer Mode con {extend:true}: vuelve a 50 horas.
// La IP, la passphrase y la llave se guardan solo en esta PC (carpeta de datos de la app).
const { Client, utils } = require('ssh2');
const fs = require('fs');
const path = require('path');

const DEVMODE = 'com.palmdts.devmode';
const APP_ID = 'com.embyverse.tv';
const ALGOS = {
  kex: ['curve25519-sha256', 'curve25519-sha256@libssh.org', 'ecdh-sha2-nistp256', 'diffie-hellman-group14-sha256', 'diffie-hellman-group14-sha1', 'diffie-hellman-group1-sha1'],
  serverHostKey: ['ssh-ed25519', 'ecdsa-sha2-nistp256', 'rsa-sha2-512', 'rsa-sha2-256', 'ssh-rsa'],
};

class LgInstaller {
  constructor(dataDir) { this.file = path.join(dataDir, 'tv-lg.json'); }
  saved() { try { return JSON.parse(fs.readFileSync(this.file, 'utf8')); } catch { return {}; } }
  save(x) { try { fs.writeFileSync(this.file, JSON.stringify({ ...this.saved(), ...x }), { mode: 0o600 }); } catch { /* */ } }

  async key(ip) {
    try {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 6000);
      const r = await fetch(`http://${ip}:9991/webos_rsa`, { signal: ctl.signal }); clearTimeout(t);
      const b = Buffer.from(await r.arrayBuffer());
      if (r.ok && b.length > 100 && b.toString().includes('PRIVATE KEY')) return b;
    } catch { /* se usa la guardada */ }
    const s = this.saved(); if (s.key && s.ip === ip) return Buffer.from(s.key, 'base64');
    throw new Error('KEYSERVER');
  }

  async connect(ip, pass) {
    if (!ip) throw new Error('Escribe la IP de la TV');
    const key = await this.key(ip);
    const parsed = utils.parseKey(key, pass);
    if (!parsed || parsed instanceof Error) throw new Error('PASSPHRASE');
    const c = new Client();
    await new Promise((ok, bad) => {
      c.on('ready', ok).on('error', bad)
        .connect({ host: ip, port: 9922, username: 'prisoner', privateKey: key, passphrase: pass, readyTimeout: 15000, algorithms: ALGOS });
    });
    this.save({ ip, pass, key: key.toString('base64') });
    return c;
  }

  exec(c, cmd, ms = 20000, until) {
    return new Promise((ok, bad) => {
      c.exec(cmd, (err, st) => {
        if (err) return bad(err);
        let out = ''; const t = setTimeout(() => { st.close(); ok(out); }, ms);
        const on = (d) => { out += d; if (until && until(out)) { clearTimeout(t); st.close(); ok(out); } };
        st.on('data', on); st.stderr.on('data', on);
        st.on('close', () => { clearTimeout(t); ok(out); });
      });
    });
  }

  upload(c, dest, data) {
    return new Promise((ok, bad) => {
      c.exec(`cat > '${dest}'`, (err, st) => {
        if (err) return bad(err);
        let e = ''; let done = false;
        const fin = (code) => { if (done) return; done = true; code === 0 || code == null ? ok() : bad(new Error('No se pudo copiar a la TV: ' + e)); };
        st.stderr.on('data', (d) => { e += d; });
        st.resume(); // hay que leer la salida para que el canal termine
        st.on('exit', fin); st.on('close', fin);
        st.end(data);
      });
    });
  }

  async launch(c, id, params) {
    const p = JSON.stringify(params ? { id, params } : { id });
    const out = await this.exec(c, `luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '${p}'`);
    if (out.includes('"returnValue":false')) throw new Error('La TV no abrió ' + id);
  }

  async download(urls) {
    let last;
    for (const u of [...new Set(urls)]) {
      try {
        const r = await fetch(u); if (!r.ok) throw new Error('HTTP ' + r.status);
        const b = Buffer.from(await r.arrayBuffer());
        if (b.length > 10000 && b.subarray(0, 8).toString() === '!<arch>\n') return b;
        throw new Error('no es un paquete .ipk');
      } catch (e) { last = e; }
    }
    throw new Error('DOWNLOAD ' + (last && last.message));
  }

  async install(ip, pass, urls, progress) {
    try {
      progress('Conectando con la TV…');
      const c = await this.connect(ip.trim(), pass.trim());
      try {
        progress('Descargando la app para LG…');
        const ipk = await this.download(urls);
        progress(`Enviando a la TV (${Math.round(ipk.length / 1024)} KB)…`);
        await this.exec(c, 'mkdir -p /media/developer/temp');
        await this.upload(c, '/media/developer/temp/app.ipk', ipk);
        progress('Instalando en la TV…');
        const out = await this.exec(c, `luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' '{"id":"com.ares.defaultName","ipkUrl":"/media/developer/temp/app.ipk","subscribe":true}'`,
          240000, (o) => o.includes('"installed"') || /fail/i.test(o) || o.includes('"returnValue":false'));
        await this.exec(c, 'rm -f /media/developer/temp/app.ipk');
        if (!out.includes('"installed"')) return { ok: false, message: 'La TV no aceptó la instalación: ' + out.slice(-240) };
        await this.launch(c, APP_ID).catch(() => {});
        await this.launch(c, DEVMODE, { extend: true }).then(() => this.save({ renew: Date.now() })).catch(() => {});
        return { ok: true, message: 'La app quedó instalada en la TV y el modo desarrollador renovado por 50 horas.' };
      } finally { c.end(); }
    } catch (e) { return { ok: false, message: explain(e) }; }
  }

  async renew(ip, pass) {
    try {
      const s = this.saved();
      const c = await this.connect((ip || s.ip || '').trim(), (pass || s.pass || '').trim());
      try { await this.launch(c, DEVMODE, { extend: true }); this.save({ renew: Date.now() }); return { ok: true, message: 'Modo desarrollador renovado por 50 horas.' }; }
      finally { c.end(); }
    } catch (e) { return { ok: false, message: explain(e) }; }
  }
}

function explain(e) {
  const m = String((e && e.message) || e);
  if (m.includes('KEYSERVER')) return 'No se pudo pedir la llave a la TV. En la TV abre «Developer Mode», revisa que «Key Server» esté en ON y que la PC esté en la misma red que la TV. Revisa también la IP.';
  if (m.includes('PASSPHRASE') || /passphrase|decrypt/i.test(m)) return 'La Passphrase no es correcta. Escríbela igual que aparece en la app Developer Mode de la TV (mayúsculas).';
  if (/authentication|auth/i.test(m)) return 'La TV rechazó la llave. Apaga y enciende «Key Server» en Developer Mode y vuelve a intentar con la Passphrase nueva.';
  if (m.includes('DOWNLOAD')) return 'No se pudo descargar la app para LG. Revisa el internet de la PC.';
  if (/timed out|timeout|ECONNREFUSED|EHOSTUNREACH|ENETUNREACH|connect/i.test(m)) return 'No se pudo conectar con la TV. Revisa que esté encendida, en modo desarrollador, en la misma red y que la IP sea correcta.';
  return 'Error: ' + m.slice(0, 200);
}

module.exports = { LgInstaller, explain };
