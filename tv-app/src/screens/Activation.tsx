// Pantalla de activación: muestra el código del equipo para enviarlo al proveedor y revisa sola cada 30 s.
import { useEffect, useState } from 'react';
import { Fx, Logo, useApp } from '../ui';
import { checkLicense, deviceCode, type LicState } from '../license';

export function Activation({ status, onActive, onTrial }: { status: LicState; onActive: () => void; onTrial: () => void }) {
  const { config } = useApp();
  const [st, setSt] = useState(status);
  const [checking, setChecking] = useState(false);
  const code = deviceCode();
  // Contacto para activar: «license.whatsapp» (quien cobra la activación) o, si no hay, el de soporte.
  const wa = (config.texts['license.whatsapp'] || config.texts['support.whatsapp'] || '').replace(/\D/g, '');
  const check = async () => {
    setChecking(true);
    const r = await checkLicense(config.texts).catch(() => st);
    setChecking(false); setSt(r);
    if (r.state === 'active') onActive();
  };
  useEffect(() => { const i = setInterval(() => { void check(); }, 30_000); return () => clearInterval(i); }, []);
  return (
    <div className="screen activation" data-fx-layer="">
      <Logo className="login-logo" />
      <h1>{st.state === 'expired' ? 'Tu activación venció' : 'Activa este equipo'}</h1>
      <p className="muted">{st.state === 'expired' ? `Venció el ${st.exp}. Envía este código a tu proveedor para renovarla.` : 'Envía este código a tu proveedor para que active el reproductor en este equipo.'}</p>
      <div className="act-code" id="device-code">{code}</div>
      {wa && <p className="act-wa">WhatsApp: <b>+{wa}</b></p>}
      <div className="row-btns" data-fx-group="">
        <Fx className="btn primary big" def onClick={() => void check()} id="act-check">{checking ? 'Revisando…' : 'Ya me activaron: revisar'}</Fx>
        {st.state === 'trial' && <Fx className="btn big" onClick={onTrial} id="act-trial">Usar prueba ({st.daysLeft} {st.daysLeft === 1 ? 'día' : 'días'})</Fx>}
      </div>
      <p className="muted small">Se revisa sola cada 30 segundos. Este reproductor no incluye contenido: agregas tu propia lista o cuenta.</p>
    </div>
  );
}
