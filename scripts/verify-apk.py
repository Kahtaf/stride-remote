#!/usr/bin/env python3
"""Inspect the exact distributable, including manifest, signature and release-only contents."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
properties = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line)
version = properties['appVersionName']
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', nargs='?', type=Path, default=root / f'artifacts/stride-remote-{version}.apk')
apk = parser.parse_args().apk.resolve()
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'

def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True)

badging = run(sdk / 'aapt2', 'dump', 'badging', apk)
manifest = run(sdk / 'aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
assert "name='dev.kahtaf.walkpad'" in badging
assert f"versionCode='{properties['appVersionCode']}'" in badging
assert f"versionName='{version}'" in badging
assert "minSdkVersion:'33'" in badging
assert "uses-feature: name='android.hardware.type.watch'" in badging
assert "uses-feature: name='android.hardware.bluetooth_le'" in badging
assert 'android.permission.INTERNET' not in badging
assert 'android.permission.ACCESS_FINE_LOCATION' not in badging
assert 'com.google.android.wearable.standalone' in manifest
assert 'android:debuggable' not in manifest
assert 'PreviewActivity' not in manifest
with zipfile.ZipFile(apk) as archive:
    dex = b''.join(archive.read(name) for name in archive.namelist() if name.endswith('.dex'))
    for absent in (b'PreviewActivity', b'SmokeInstrumentation', b'SIMULATED PAD'):
        assert absent not in dex, absent
    for present in (b'PadService', b'ClassicProtocol', b'MainActivity'):
        assert present in dex, present
    assert b'Apache License' in archive.read('assets/LICENSE')
    assert b'Belt Control' in archive.read('assets/NOTICE')
signature = run(sdk / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
assert 'Verifies' in signature
run(sdk / 'zipalign', '-c', '4', apk)
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
expected = (apk.parent / 'SHA256SUMS').read_text().split()[0]
assert digest == expected
print('PASS release package, watch/BLE features, standalone declaration, no internet/location permission')
print('PASS release is not debuggable; simulator and instrumentation excluded')
print('PASS upstream licence and attribution bundled')
print('PASS signature, ZIP alignment and SHA-256')
print(f'APK {apk.stat().st_size} bytes; SHA256 {digest}')
