import { useEffect, useState } from 'react';
import { api, ApiError, type Asset, type User, type Validation, type VersionInfo } from '../api';
import { fmtDate, Group, Modal, Notice, Segmented, Select, TextField } from './ui';

const errText = (e: unknown) => (e instanceof ApiError ? e.message : 'No se pudo completar la acción. Comprueba la conexión.');

// ---------------------------------------------------------------- RECURSOS
export function AssetsPanel({ user, assets, reload, onUpload }: { user: User; assets: Asset[]; reload: () => void; onUpload: (f: File, k: Asset['kind']) => Promise<Asset | null> }) {
  const [kind, setKind] = useState<Asset['kind']>('banner');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const canWrite = user.permissions.includes('assets:write');
  const canApprove = user.permissions.includes('assets:approve');
  const run = async (f: () => Promise<unknown>, ok: string) => { try { await f(); setMsg({ kind: 'ok', text: ok }); reload(); } catch (e) { setMsg({ kind: 'error', text: errText(e) }); } };
  return (
    <>
      {canWrite && (
        <Group title="Subir imagen" hint="PNG, JPEG o WebP hasta 3 MB y 4096 px. Se comprueba el formato real del archivo. Los logotipos necesitan la aprobación de un propietario.">
          <Segmented value={kind} onChange={setKind} options={[{ value: 'banner', label: 'Banner' }, { value: 'image', label: 'Imagen' }, { value: 'logo', label: 'Variante de logotipo' }]} />
          <label className="dropzone">
            <span>Elige un archivo o arrástralo aquí</span>
            <input type="file" accept="image/png,image/jpeg,image/webp" onChange={async (e) => { const f = e.target.files?.[0]; if (f && await onUpload(f, kind)) setMsg({ kind: 'ok', text: 'Imagen subida' }); e.target.value = ''; }} />
          </label>
        </Group>
      )}
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <Group title={`Biblioteca (${assets.length})`}>
        {assets.length === 0 && <p className="hint">Aún no hay imágenes.</p>}
        <div className="asset-grid">
          {assets.map((a) => (
            <figure key={a.id} className="asset">
              <div className="asset-img" style={{ backgroundImage: `url("${a.previewUrl}")` }} />
              <figcaption>
                <b title={a.filename}>{a.filename}</b>
                <span className="hint">{a.kind === 'logo' ? 'Logotipo' : a.kind === 'banner' ? 'Banner' : 'Imagen'} · {a.width}×{a.height} · {Math.round(a.size / 1024)} KB</span>
                {a.kind === 'logo' && <span className={`pill pill-${a.approved ? 'ok' : 'warn'}`}>{a.approved ? 'Aprobado' : 'Pendiente de aprobación'}</span>}
                <span className="asset-actions">
                  <button className="link" onClick={() => navigator.clipboard?.writeText(a.url)}>Copiar URL</button>
                  {a.kind === 'logo' && canApprove && <button className="link" onClick={() => run(() => api.approve(a.id, !a.approved), a.approved ? 'Aprobación retirada' : 'Logotipo aprobado')}>{a.approved ? 'Retirar aprobación' : 'Aprobar'}</button>}
                  {canWrite && <button className="link danger" onClick={() => { if (confirm(`¿Eliminar ${a.filename}?`)) void run(() => api.deleteAsset(a.id), 'Imagen eliminada'); }}>Eliminar</button>}
                </span>
              </figcaption>
            </figure>
          ))}
        </div>
      </Group>
    </>
  );
}

// ---------------------------------------------------------------- PUBLICAR Y VERSIONES
export function VersionsPanel({ user, latest, onRestored, onRolledBack }: { user: User; latest: number; onRestored: () => void; onRolledBack: () => void }) {
  const [list, setList] = useState<VersionInfo[] | null>(null);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const [confirmRb, setConfirmRb] = useState<number | null>(null);
  const load = () => api.versions().then((r) => setList(r.versions)).catch((e) => setMsg({ kind: 'error', text: errText(e) }));
  useEffect(() => { void load(); }, [latest]);
  const canEdit = user.permissions.includes('config:edit');
  const canPublish = user.permissions.includes('config:publish');
  return (
    <>
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <Group title="Historial de publicaciones" hint="Revertir crea una versión nueva con el contenido elegido: el historial nunca se borra y las apps la reciben en su siguiente comprobación.">
        {list === null ? <p className="hint">Cargando…</p> : list.length === 0 ? <p className="hint">Todavía no se ha publicado ninguna versión. La app usa el diseño integrado.</p> : (
          <ol className="timeline">
            {list.map((v) => (
              <li key={v.version} className={v.version === latest ? 'current' : ''}>
                <div className="tl-head">
                  <b>Versión {v.version}</b>{v.version === latest && <span className="pill pill-ok">Publicada</span>}
                  {v.rollback_of && <span className="pill pill-info">Reversión a v{v.rollback_of}</span>}
                </div>
                <div className="hint">{fmtDate(v.published_at)} · {v.published_by ?? 'usuario eliminado'}</div>
                {v.note && <p className="tl-note">{v.note}</p>}
                <div className="row-actions">
                  {canEdit && <button className="link" onClick={async () => { try { await api.restoreDraft(v.version); setMsg({ kind: 'ok', text: `La versión ${v.version} se cargó como borrador` }); onRestored(); } catch (e) { setMsg({ kind: 'error', text: errText(e) }); } }}>Cargar como borrador</button>}
                  {canPublish && v.version !== latest && <button className="link danger" onClick={() => setConfirmRb(v.version)}>Revertir a esta versión</button>}
                </div>
              </li>
            ))}
          </ol>
        )}
      </Group>
      {confirmRb !== null && (
        <Modal title={`Revertir a la versión ${confirmRb}`} onClose={() => setConfirmRb(null)} actions={<>
          <button className="btn-ghost" onClick={() => setConfirmRb(null)}>Cancelar</button>
          <button className="btn-danger" onClick={async () => {
            try { const r = await api.rollback(confirmRb); setMsg({ kind: 'ok', text: `Publicada la versión ${r.version} con el contenido de la ${confirmRb}` }); setConfirmRb(null); onRolledBack(); void load(); }
            catch (e) { setMsg({ kind: 'error', text: errText(e) }); setConfirmRb(null); }
          }}>Revertir y publicar</button>
        </>}>
          <p>Las apps recibirán el diseño de la versión {confirmRb} como una publicación nueva. El borrador actual se descartará.</p>
        </Modal>
      )}
    </>
  );
}

export function PublishDialog({ validation, dirty, onClose, onPublish }: { validation: Validation | null; dirty: boolean; onClose: () => void; onPublish: (note: string) => Promise<void> }) {
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const blocking = !validation || !validation.ok;
  return (
    <Modal title="Publicar diseño" onClose={onClose} actions={<>
      <button className="btn-ghost" onClick={onClose}>Cancelar</button>
      <button className="btn-publish" disabled={blocking || busy} onClick={async () => { setBusy(true); try { await onPublish(note); } finally { setBusy(false); } }}>{busy ? 'Publicando…' : 'Publicar'}</button>
    </>}>
      {dirty && <Notice kind="info">Se guardará el borrador antes de publicar.</Notice>}
      {validation === null && <p className="hint">Validando en el servidor…</p>}
      {validation && validation.ok && <Notice kind="ok">La configuración es válida. Las apps la aplicarán en su próxima comprobación (al abrirse o cada 12 h).</Notice>}
      {validation && [...validation.errors, ...validation.assetErrors].length > 0 && (
        <Notice kind="error"><b>Corrige estos problemas antes de publicar:</b><ul>{[...validation.errors, ...validation.assetErrors].map((e) => <li key={e}>{e}</li>)}</ul></Notice>
      )}
      {validation && validation.warnings.length > 0 && <Notice kind="warn"><ul>{validation.warnings.map((w) => <li key={w}>{w}</li>)}</ul></Notice>}
      <TextField label="Nota de la publicación (opcional)" value={note} max={300} multiline placeholder="Qué cambia en esta versión" onChange={setNote} />
    </Modal>
  );
}

// ---------------------------------------------------------------- USUARIOS
const ROLE_NAMES: Record<User['role'], string> = { owner: 'Propietario', publisher: 'Publicador', editor: 'Editor', viewer: 'Solo lectura' };
const ROLE_HINT = 'Propietario: todo, incluidos usuarios y aprobación de logotipos · Publicador: editar, publicar y revertir · Editor: editar borradores y subir imágenes · Solo lectura: ver.';

export function UsersPanel({ me }: { me: User }) {
  const [users, setUsers] = useState<Awaited<ReturnType<typeof api.users>>['users']>([]);
  const [form, setForm] = useState({ email: '', name: '', role: 'editor' as User['role'], password: '' });
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  const load = () => api.users().then((r) => setUsers(r.users)).catch((e) => setMsg({ kind: 'error', text: errText(e) }));
  useEffect(() => { void load(); }, []);
  const run = async (f: () => Promise<unknown>, ok: string) => { try { await f(); setMsg({ kind: 'ok', text: ok }); void load(); } catch (e) { setMsg({ kind: 'error', text: errText(e) }); } };
  return (
    <>
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <Group title="Usuarios del panel" hint={ROLE_HINT}>
        <table className="table">
          <thead><tr><th>Usuario</th><th>Rol</th><th>Estado</th><th /></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td><b>{u.name}</b><div className="hint">{u.email}</div></td>
                <td><select value={u.role} disabled={u.id === me.id} onChange={(e) => run(() => api.updateUser(u.id, { role: e.target.value }), 'Rol actualizado')}>
                  {Object.entries(ROLE_NAMES).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></td>
                <td>{u.disabled ? <span className="pill pill-muted">Desactivado</span> : <span className="pill pill-ok">Activo</span>}</td>
                <td className="row-actions">
                  {u.id !== me.id && <button className="link" onClick={() => run(() => api.updateUser(u.id, { disabled: !u.disabled }), u.disabled ? 'Usuario activado' : 'Usuario desactivado')}>{u.disabled ? 'Activar' : 'Desactivar'}</button>}
                  {u.id !== me.id && <button className="link danger" onClick={() => { if (confirm(`¿Eliminar a ${u.email}?`)) void run(() => api.deleteUser(u.id), 'Usuario eliminado'); }}>Eliminar</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Group>
      <Group title="Invitar usuario" hint="Comparte la contraseña inicial por un canal seguro y pide que la cambie al entrar.">
        <div className="two">
          <TextField label="Email" value={form.email} onChange={(v) => setForm({ ...form, email: v })} />
          <TextField label="Nombre" value={form.name} max={80} onChange={(v) => setForm({ ...form, name: v })} />
        </div>
        <div className="two">
          <Select label="Rol" value={form.role} onChange={(v) => setForm({ ...form, role: v })} options={Object.entries(ROLE_NAMES).map(([value, label]) => ({ value: value as User['role'], label }))} />
          <div className="field"><label>Contraseña inicial</label><input type="password" autoComplete="new-password" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} /></div>
        </div>
        <button className="btn" onClick={() => run(async () => { await api.createUser(form); setForm({ email: '', name: '', role: 'editor', password: '' }); }, 'Usuario creado')}>Crear usuario</button>
      </Group>
    </>
  );
}

// ---------------------------------------------------------------- AUDITORÍA
const ACTIONS: Record<string, string> = {
  'auth.login': 'Inicio de sesión', 'auth.login_failed': 'Intento de acceso fallido', 'auth.logout': 'Cierre de sesión', 'auth.password_changed': 'Cambio de contraseña',
  'config.draft_saved': 'Borrador guardado', 'config.draft_discarded': 'Borrador descartado', 'config.draft_restored': 'Versión cargada como borrador',
  'config.published': 'Diseño publicado', 'config.rolled_back': 'Reversión publicada', 'asset.uploaded': 'Imagen subida', 'asset.deleted': 'Imagen eliminada',
  'asset.approved': 'Logotipo aprobado', 'asset.unapproved': 'Aprobación retirada', 'user.created': 'Usuario creado', 'user.updated': 'Usuario modificado', 'user.deleted': 'Usuario eliminado',
};

export function AuditPanel() {
  const [rows, setRows] = useState<Awaited<ReturnType<typeof api.audit>>['entries']>([]);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => { api.audit().then((r) => setRows(r.entries)).catch((e) => setErr(errText(e))); }, []);
  return (
    <Group title="Registro de operaciones" hint="Últimas 200 acciones administrativas. No se registran contraseñas.">
      {err && <Notice kind="error">{err}</Notice>}
      <table className="table">
        <thead><tr><th>Fecha</th><th>Usuario</th><th>Acción</th><th>Detalle</th></tr></thead>
        <tbody>{rows.map((r) => (
          <tr key={r.id}><td className="nowrap">{fmtDate(r.at)}</td><td>{r.user_email ?? '—'}<div className="hint">{r.ip}</div></td>
            <td>{ACTIONS[r.action] ?? r.action}</td><td className="hint">{r.target}</td></tr>
        ))}</tbody>
      </table>
    </Group>
  );
}

// ---------------------------------------------------------------- CUENTA
export function AccountPanel({ me }: { me: User }) {
  const [cur, setCur] = useState(''); const [next, setNext] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  return (
    <Group title="Mi cuenta" hint={`${me.name} · ${me.email} · ${ROLE_NAMES[me.role]}`}>
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
      <div className="two">
        <div className="field"><label>Contraseña actual</label><input type="password" autoComplete="current-password" value={cur} onChange={(e) => setCur(e.target.value)} /></div>
        <div className="field"><label>Nueva contraseña (mín. 12, letras y números)</label><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} /></div>
      </div>
      <button className="btn" onClick={async () => { try { await api.changePassword(cur, next); setCur(''); setNext(''); setMsg({ kind: 'ok', text: 'Contraseña cambiada. Se cerraron tus otras sesiones.' }); } catch (e) { setMsg({ kind: 'error', text: errText(e) }); } }}>Cambiar contraseña</button>
    </Group>
  );
}
