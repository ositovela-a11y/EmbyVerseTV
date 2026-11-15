// Servidor de estadísticas local (para probar el panel y las apps sin Cloudflare). Uso: node test/serve.mjs 8788
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import worker from '../src/index.js';
import { makeDB } from './d1-shim.mjs';
const env = { DB: makeDB(fileURLToPath(new URL('../schema.sql', import.meta.url))), ADMIN_KEY: process.env.ADMIN_KEY || 'clave-de-prueba' };
const port = Number(process.argv[2] || 8788);
createServer(async (req, res) => {
  const chunks = []; for await (const c of req) chunks.push(c);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const headers = { ...req.headers, 'cf-ipcountry': req.headers['x-country'] || 'MX' };
  const r = await worker.fetch(new Request(`http://localhost:${port}${req.url}`, { method: req.method, headers, body: ['GET', 'HEAD'].includes(req.method) ? undefined : body }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers)); res.end(Buffer.from(await r.arrayBuffer()));
}).listen(port, () => console.log('estadísticas en :' + port));
