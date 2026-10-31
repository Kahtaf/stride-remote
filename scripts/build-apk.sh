#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
mkdir -p artifacts qa
./gradlew testDebugUnitTest lint assembleRelease

stride_version=$(sed -n 's/^appVersionName=//p' gradle.properties)
stride_signing_dir="${STRIDE_SIGNING_DIR:-$HOME/.local/share/stride-remote/signing}"
umask 077
mkdir -p "$stride_signing_dir"
chmod 700 "$stride_signing_dir"
stride_key="$stride_signing_dir/stride-remote.jks"
stride_password="$stride_signing_dir/password"
if [ ! -f "$stride_key" ]; then
    if [ ! -f "$stride_password" ]; then openssl rand -base64 32 > "$stride_password"; fi
    keytool -genkeypair -noprompt -keystore "$stride_key" -storepass:file "$stride_password" -keypass:file "$stride_password" \
        -alias stride-remote -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Stride Remote' -storetype JKS
fi
stride_apk="artifacts/stride-remote-$stride_version.apk"
stride_aligned="artifacts/stride-remote-$stride_version-aligned.apk"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk "$stride_aligned"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" sign --ks "$stride_key" --ks-key-alias stride-remote \
    --ks-pass "file:$stride_password" --out "$stride_apk" "$stride_aligned"
rm "$stride_aligned"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$stride_apk" > qa/apk-signature.txt
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -v 4 "$stride_apk" > qa/apk-alignment.txt
python3 - "$stride_apk" <<'PY'
import hashlib
from pathlib import Path
import sys
apk = Path(sys.argv[1])
# A basename keeps checksums usable after downloading the release assets.
Path('artifacts/SHA256SUMS').write_text(hashlib.sha256(apk.read_bytes()).hexdigest() + '  ' + apk.name + '\n')
PY
python3 scripts/verify-apk.py "$stride_apk"
echo "Signed APK: $stride_apk"
