"""Compara las versiones compiladas que esperan aprobación (…-nueva) con las que tienen los clientes (…-latest).
Uso: versiones.py AAPT CARPETA aprobadas.json vendors/index.json [--estado]
  sin --estado: imprime lo que hay que publicar porque el dueño ya lo aprobó («apk ARCHIVO», «tv VERSIÓN», «pc VERSIÓN»).
  con --estado: imprime config/versiones.json para el panel."""
import hashlib, json, os, re, subprocess, sys, zipfile
from datetime import datetime, timezone

aapt, W, aprob_path, idx_path = sys.argv[1:5]
estado = '--estado' in sys.argv

def load(p, d):
    try: return json.load(open(p))
    except Exception: return d

aprob = load(aprob_path, {})
names = {'EmbyVerse.apk': ('', 'Tu app (EmbyVerse)')}
for v in load(idx_path, []):
    names[f"EmbyVerse-{v['slug']}.apk"] = (v['slug'], v.get('name') or v['slug'])

def apk(path):
    if not os.path.exists(path): return None
    out = subprocess.run([aapt, 'dump', 'badging', path], capture_output=True, text=True).stdout if aapt else ''
    m = re.search(r"versionCode='(\d+)' versionName='([^']*)'", out)
    sha = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    return {'code': int(m.group(1)) if m else 0, 'name': m.group(2) if m else '?', 'sha': sha}

def tv(folder):
    z = os.path.join(folder, 'EmbyVerse-TV-web.zip')
    if not os.path.exists(z): return None
    try:
        html = zipfile.ZipFile(z).read('index.html').decode('utf-8', 'replace')
        m = re.search(r"LOCAL = '([^']*)'", html)
        return {'name': m.group(1) if m else '?', 'sha': hashlib.sha256(open(z, 'rb').read()).hexdigest()}
    except Exception: return None

def pc(folder):
    d = load(os.path.join(folder, 'pc-version.json'), None)
    return {'name': str(d.get('version')), 'sha': str(d.get('build') or d.get('version'))} if isinstance(d, dict) and d.get('version') else None

def differs(new, live): return bool(new) and (not live or new['sha'] != live['sha'])
def pub(x): return {k: x[k] for k in ('code', 'name') if k in x} if x else None

assets = sorted(set(f for f in os.listdir(f'{W}/an') if f.endswith('.apk')) | set(f for f in os.listdir(f'{W}/al') if f.endswith('.apk')),
                key=lambda a: (a != 'EmbyVerse.apk', a))
android = []
for a in assets:
    new, live = apk(f'{W}/an/{a}'), apk(f'{W}/al/{a}')
    slug, name = names.get(a, (a.removeprefix('EmbyVerse-').removesuffix('.apk'), a))
    ok = (aprob.get('android') or {}).get(a)
    if not estado and new and differs(new, live) and ok is not None and int(ok) == new['code']:
        print('apk', a)
    android.append({'asset': a, 'slug': slug, 'name': name, 'live': pub(live), 'nueva': pub(new),
                    'pendiente': differs(new, live), 'aprobada': ok})
others = {}
for kind, fn in (('tv', tv), ('pc', pc)):
    new, live = fn(f'{W}/{kind[0]}n'), fn(f'{W}/{kind[0]}l')
    ok = aprob.get(kind)
    if not estado and new and differs(new, live) and ok is not None and str(ok) == new['name']:
        print(kind, new['name'])
    others[kind] = {'live': pub(live), 'nueva': pub(new), 'pendiente': differs(new, live), 'aprobada': ok}

if estado:
    print(json.dumps({'updated': datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'), 'android': android, **others},
                     ensure_ascii=False, indent=1))
