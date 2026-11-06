// Pila de manejadores de teclas: la pantalla/capa de arriba recibe primero cada tecla.
type Handler = (e: KeyboardEvent) => boolean;
const stack: Handler[] = [];
export function pushKeys(h: Handler) { stack.push(h); return () => { const i = stack.lastIndexOf(h); if (i >= 0) stack.splice(i, 1); }; }
export function dispatchKeys(e: KeyboardEvent): boolean { for (let i = stack.length - 1; i >= 0; i--) if (stack[i](e)) return true; return false; }
