#!/usr/bin/env python3
"""Install over flaky wireless ADB using bounded small transfers. Stop the pad first."""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial', help='Current watch IP:port or exact ADB serial')
parser.add_argument('apk', type=Path)
parser.add_argument('--adb-port', help='Optional ADB server port (default: standard ADB configuration)')
args = parser.parse_args()
apk = args.apk.resolve()
root = Path(__file__).resolve().parent.parent
adb = ['adb'] + (['-P', args.adb_port] if args.adb_port else []) + ['-s', args.serial]

def run(command, timeout=15):
    return subprocess.check_output(adb + command, timeout=timeout, text=True, stderr=subprocess.STDOUT)

if 'feature:android.hardware.type.watch' not in run(['shell', 'pm', 'list', 'features']).splitlines():
    raise SystemExit('Target does not advertise the Android watch feature; no files sent.')
if int(run(['shell', 'getprop', 'ro.build.version.sdk']).strip()) < 33:
    raise SystemExit('This app requires a Wear OS watch with Android API 33 or newer.')
print('Watch: ' + run(['shell', 'getprop', 'ro.product.model']).strip(), flush=True)
(root / 'qa').mkdir(exist_ok=True)
data = apk.read_bytes()
digest = hashlib.sha256(data).hexdigest()
remote = '/data/local/tmp/stride-upload-' + digest[:12]
run(['shell', 'mkdir', '-p', remote])
with tempfile.TemporaryDirectory(prefix='watch-upload-', dir=root / 'qa') as temporary:
    total = (len(data) + 131071) // 131072
    for index, offset in enumerate(range(0, len(data), 131072)):
        part = Path(temporary) / f'part-{index:03d}'
        part.write_bytes(data[offset:offset + 131072])
        run(['shell', 'input', 'keyevent', '224'])
        run(['push', '-Z', str(part), remote + '/' + part.name])
        print(f'Copied {index + 1}/{total}', flush=True)
run(['shell', f'cat {remote}/part-* > {remote}/app.apk'])
if run(['shell', 'sha256sum', remote + '/app.apk']).split()[0] != digest:
    raise SystemExit('Checksum mismatch; nothing installed. Temporary files retained for inspection.')
print('Full APK checksum verified', flush=True)
result = run(['shell', 'pm', 'install', '-r', remote + '/app.apk'], timeout=90)
print(result, flush=True)
if result.strip() != 'Success':
    raise SystemExit('Installation did not report success; verify watch package state before retrying.')
for line in run(['shell', 'dumpsys', 'package', 'dev.kahtaf.walkpad']).splitlines():
    if line.strip().startswith(('versionName=', 'versionCode=')):
        print(line.strip(), flush=True)
print(run(['shell', 'am', 'start', '-W', '-n', 'dev.kahtaf.walkpad/.MainActivity']), flush=True)
run(['shell', 'rm', '-r', remote])
