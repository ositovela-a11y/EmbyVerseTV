"""Rearma un .ipk de LG con el formato EXACTO de ares-package (herramienta oficial de LG), sin cambiar la app:
agrega usr/palm/packages/<id>/packageinfo.json, rutas de tar sin «./» y encabezados «ar» como los de ares
(nombre sin «/», modo 100644). Sin esto algunas TVs dicen «Failed to extract package» (errorCode -5).
Uso: python3 ipk-ares.py entrada.ipk salida.ipk"""
import io, json, os, subprocess, sys, tarfile, tempfile, time

src, dst = sys.argv[1], sys.argv[2]
def ar_members(b):
    assert b[:8] == b'!<arch>\n', 'no es un paquete ar'
    i, out = 8, {}
    while i + 60 <= len(b):
        h = b[i:i+60]; name = h[:16].decode().strip().rstrip('/'); size = int(h[48:58].decode().strip())
        out[name] = b[i+60:i+60+size]; i += 60 + size + (size % 2)
    return out
m = ar_members(open(src, 'rb').read())
w = tempfile.mkdtemp()
for part in ('control', 'data'):
    os.makedirs(f'{w}/{part}', exist_ok=True)
    tarfile.open(fileobj=io.BytesIO(m[f'{part}.tar.gz'])).extractall(f'{w}/{part}', filter='data')
apps = os.listdir(f'{w}/data/usr/palm/applications')
app = apps[0]
info = json.load(open(f'{w}/data/usr/palm/applications/{app}/appinfo.json'))
os.makedirs(f'{w}/data/usr/palm/packages/{app}', exist_ok=True)
open(f'{w}/data/usr/palm/packages/{app}/packageinfo.json', 'w').write(json.dumps({'app': app, 'id': app, 'version': info['version']}, indent=2) + '\n')
size = subprocess.run(['du', '-sk', f'{w}/data'], capture_output=True, text=True).stdout.split()[0]
open(f'{w}/control/control', 'w').write(f"Package: {app}\nVersion: {info['version']}\nSection: misc\nPriority: optional\nArchitecture: all\n"
    f"Installed-Size: {size}\nMaintainer: N/A <nobody@example.com>\nDescription: This is a webOS application.\n"
    "webOS-Package-Format-Version: 2\nwebOS-Packager-Version: x.y.x\n")
subprocess.run(f'cd {w}/control && tar --owner=0 --group=0 --numeric-owner -czf ../control.tar.gz control', shell=True, check=True)
subprocess.run(f'cd {w}/data && tar --owner=0 --group=0 --numeric-owner -czf ../data.tar.gz usr', shell=True, check=True)
now = str(int(time.time()))
head = lambda n, l: (n.ljust(16) + now.ljust(12) + '0     ' + '0     ' + '100644  ' + str(l).ljust(10) + '`\n').encode('latin1')
out = [b'!<arch>\n']
for n, dat in [('debian-binary', b'2.0\n'), ('control.tar.gz', open(f'{w}/control.tar.gz', 'rb').read()), ('data.tar.gz', open(f'{w}/data.tar.gz', 'rb').read())]:
    out += [head(n, len(dat)), dat] + ([b'\n'] if len(dat) % 2 else [])
open(dst, 'wb').write(b''.join(out))
print(f'{dst}: {app} {info["version"]} con packageinfo.json ({sum(map(len, out))} bytes)')
