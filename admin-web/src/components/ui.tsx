import { useEffect, useId, useState, type ReactNode } from 'react';

export function Group({ title, hint, children, actions }: { title: string; hint?: string; children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="group">
      <header className="group-head"><div><h3>{title}</h3>{hint && <p className="hint">{hint}</p>}</div>{actions}</header>
      <div className="group-body">{children}</div>
    </section>
  );
}

export function TextField({ label, value, onChange, max, placeholder, multiline, disabled }: {
  label: string; value: string; onChange: (v: string) => void; max?: number; placeholder?: string; multiline?: boolean; disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}{max && <span className="count">{value.length}/{max}</span>}</label>
      {multiline
        ? <textarea id={id} value={value} maxLength={max} placeholder={placeholder} disabled={disabled} rows={3} onChange={(e) => onChange(e.target.value)} />
        : <input id={id} value={value} maxLength={max} placeholder={placeholder} disabled={disabled} onChange={(e) => onChange(e.target.value)} />}
    </div>
  );
}

/** Selector de color con campo hexadecimal. Acepta #RRGGBB y #AARRGGBB (formato Android). */
export function ColorField({ label, value, onChange, disabled, note }: { label: string; value: string; onChange: (v: string) => void; disabled?: boolean; note?: ReactNode }) {
  const [text, setText] = useState(value);
  useEffect(() => setText(value), [value]);
  const rgb = value.length === 9 ? `#${value.slice(3)}` : value;
  const commit = (v: string) => { if (/^#([0-9a-f]{6}|[0-9a-f]{8})$/i.test(v)) onChange(v.toUpperCase()); };
  return (
    <div className="color-field">
      <input type="color" aria-label={label} value={rgb.toLowerCase()} disabled={disabled}
        onChange={(e) => onChange((value.length === 9 ? `#${value.slice(1, 3)}` + e.target.value.slice(1) : e.target.value).toUpperCase())} />
      <div className="color-meta">
        <span className="color-label">{label}</span>
        <input className="hex" value={text} disabled={disabled} spellCheck={false} aria-label={`${label} (hexadecimal)`}
          onChange={(e) => { setText(e.target.value); commit(e.target.value); }} onBlur={() => setText(value)} />
      </div>
      {note}
    </div>
  );
}

export function Slider({ label, value, min, max, step = 1, unit = '', onChange, disabled }: {
  label: string; value: number; min: number; max: number; step?: number; unit?: string; onChange: (v: number) => void; disabled?: boolean;
}) {
  const id = useId();
  return (
    <div className="slider">
      <label htmlFor={id}>{label}<output>{step < 1 ? value.toFixed(2) : value}{unit}</output></label>
      <input id={id} type="range" min={min} max={max} step={step} value={value} disabled={disabled} onChange={(e) => onChange(Number(e.target.value))} />
    </div>
  );
}

export function Segmented<T extends string>({ label, value, options, onChange, disabled }: {
  label?: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; disabled?: boolean;
}) {
  return (
    <div className="seg-wrap">
      {label && <span className="seg-label">{label}</span>}
      <div className="seg" role="radiogroup" aria-label={label}>
        {options.map((o) => (
          <button key={o.value} type="button" role="radio" aria-checked={o.value === value} disabled={disabled}
            className={o.value === value ? 'on' : ''} onClick={() => onChange(o.value)}>{o.label}</button>
        ))}
      </div>
    </div>
  );
}

export function Toggle({ label, checked, onChange, hint, disabled }: { label: string; checked: boolean; onChange: (v: boolean) => void; hint?: string; disabled?: boolean }) {
  return (
    <label className={`toggle ${disabled ? 'is-disabled' : ''}`}>
      <span><span className="toggle-label">{label}</span>{hint && <span className="hint">{hint}</span>}</span>
      <input type="checkbox" role="switch" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span className="toggle-track" aria-hidden><span /></span>
    </label>
  );
}

export function Select<T extends string>({ label, value, options, onChange, disabled }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; disabled?: boolean }) {
  const id = useId();
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <select id={id} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as T)}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
    </div>
  );
}

export function Notice({ kind, children }: { kind: 'error' | 'warn' | 'ok' | 'info'; children: ReactNode }) {
  return <div className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</div>;
}

export function Modal({ title, children, onClose, actions }: { title: string; children: ReactNode; onClose: () => void; actions: ReactNode }) {
  useEffect(() => {
    const k = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <h2>{title}</h2>
        <div className="modal-body">{children}</div>
        <div className="modal-actions">{actions}</div>
      </div>
    </div>
  );
}

export const fmtDate = (ms: number) => new Date(ms).toLocaleString('es', { dateStyle: 'medium', timeStyle: 'short' });
