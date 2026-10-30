// Punto de entrada para Deno Deploy (console.deno.com → New App → este repositorio → entrypoint main.ts).
// @ts-ignore: módulo JavaScript compartido con Cloudflare Workers
import { handle } from './worker.js';

const env = {
  KEY: Deno.env.get('KEY') ?? '',
  ALLOWED_ORIGINS: Deno.env.get('ALLOWED_ORIGINS') ?? '',
  USER_AGENT: Deno.env.get('USER_AGENT') ?? '',
  ALLOW_PRIVATE: Deno.env.get('ALLOW_PRIVATE') ?? '', // solo para pruebas locales
};
const handler = (req: Request) => handle(req, env);
const port = Deno.env.get('PORT');
if (port) Deno.serve({ port: Number(port) }, handler);
else Deno.serve(handler);
