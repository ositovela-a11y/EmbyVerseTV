// Ejecuta el relay en Node (pruebas locales):  node relay/node-dev.mjs 8790
import { createServer } from 'node:http';
import { Readable } from 'node:stream';
import { handle } from './worker.js';
const port = +(process.argv[2] ?? 8790);
const env = { KEY: process.env.KEY ?? '', ALLOWED_ORIGINS: process.env.ALLOWED_ORIGINS ?? '', ALLOW_PRIVATE: process.env.ALLOW_PRIVATE ?? '' };
createServer(async (req, res) => {
  const r = await handle(new Request(`http://${req.headers.host}${req.url}`, { method: req.method, headers: req.headers }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  if (!r.body) return res.end();
  const s = Readable.fromWeb(r.body); s.pipe(res); res.on('close', () => s.destroy());
}).listen(port, () => console.log('relay en', port));
