// Instalador LG de la PC contra una «TV» simulada: servidor de llave (9991) y SSH (9922) con la llave cifrada con la
// Passphrase, como el modo desarrollador de LG. Comprueba: llave, passphrase incorrecta, envío del .ipk, instalación,
// apertura de la app y renovación de 50 h. Uso: node tests/lg-installer.test.cjs (necesita ssh-keygen).
const { Server, utils } = require('ssh2');
const http = require('http'); const fs = require('fs'); const os = require('os'); const path = require('path');
const { LgInstaller } = require('../lg-installer');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lg-'));
const PASS = 'ABC123';
const crypto = require('crypto');
// Llave como la de la TV: RSA en PEM tradicional cifrada con la Passphrase.
const priv = Buffer.from(crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicKeyEncoding: { type: 'pkcs1', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs1', format: 'pem', cipher: 'aes-128-cbc', passphrase: PASS } }).privateKey);
const pub = utils.parseKey(priv, PASS);
const hostKey = crypto.generateKeyPairSync('rsa', { modulusLength: 2048, publicKeyEncoding: { type: 'pkcs1', format: 'pem' }, privateKeyEncoding: { type: 'pkcs1', format: 'pem' } }).privateKey;
const ipk = Buffer.concat([Buffer.from('!<arch>\n'), Buffer.alloc(20000, 7)]);
const got = { file: null, cmds: [] };
let fails = 0; const ok = (c, m) => { console.log(c ? '✔' : '✘', m); if (!c) fails++; };

const keySrv = http.createServer((q, s) => { if (q.url === '/webos_rsa') { s.end(priv); } else if (q.url === '/app.ipk') { s.end(ipk); } else { s.statusCode = 404; s.end(); } }).listen(9991);
const ssh = new Server({ hostKeys: [hostKey] }, (c) => {
  c.on('authentication', (ctx) => {
    if (ctx.username === 'prisoner' && ctx.method === 'publickey' && ctx.key.algo === pub.type && Buffer.from(ctx.key.data).equals(pub.getPublicSSH())) {
      if (!ctx.signature || pub.verify(ctx.blob, ctx.signature, ctx.hashAlgo)) return ctx.accept();
    }
    ctx.reject(['publickey']);
  }).on('ready', () => c.on('session', (accept) => accept().on('exec', (acc, _r, info) => {
    const st = acc(); got.cmds.push(info.command); 
    if (info.command.startsWith('cat > ')) {
      const parts = []; let done = false;
      const fin = () => { if (done) return; done = true; got.file = Buffer.concat(parts); try { st.exit(0); st.end(); } catch { /* */ } };
      st.on('data', (d) => parts.push(d)); st.on('end', fin); st.on('finish', fin);
      return;
    }
    if (info.command.includes('appInstallService/dev/install')) st.write('{"returnValue":true}\n{"details":{"state":"installing"}}\n{"details":{"state":"installed"}}\n');
    else if (info.command.includes('applicationManager/launch')) st.write('{"returnValue":true}\n');
    st.exit(0); st.end();
  })));
}).listen(9922, '127.0.0.1');

(async () => {
  const lg = new LgInstaller(dir);
  const bad = await lg.install('127.0.0.1', 'XXXXXX', ['http://127.0.0.1:9991/app.ipk'], () => {});
  ok(!bad.ok && /Passphrase/.test(bad.message), 'passphrase incorrecta → mensaje claro');
  const steps = [];
  const r = await lg.install('127.0.0.1', PASS, ['http://127.0.0.1:9991/app.ipk'], (m) => steps.push(m));
  ok(r.ok, 'instalación: ' + r.message);
  ok(got.file && got.file.equals(ipk), 'el .ipk llegó completo a la TV');
  ok(got.cmds.some((c) => c.includes('com.embyverse.tv')), 'abre la app en la TV');
  ok(got.cmds.some((c) => c.includes('com.palmdts.devmode') && c.includes('extend')), 'renueva el modo desarrollador');
  console.log('Pasos:', steps.join(' → '));
  got.cmds = [];
  const rn = await lg.renew();
  ok(rn.ok && got.cmds.some((c) => c.includes('extend')), 'renovar con los datos guardados: ' + rn.message);
  const off = await new LgInstaller(dir).install('127.0.0.2', PASS, ['http://127.0.0.1:9991/app.ipk'], () => {});
  ok(!off.ok, 'TV apagada → ' + off.message);
  keySrv.close(); ssh.close();
  console.log(fails ? `RESULTADO: ${fails} fallas` : 'RESULTADO: todo bien'); process.exit(fails ? 1 : 0);
})();
