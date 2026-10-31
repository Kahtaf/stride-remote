#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
set -eu
cd "$(dirname "$0")/.."
mkdir -p .tooling
stride_formatter=.tooling/google-java-format-1.24.0-all-deps.jar
if [ ! -f "$stride_formatter" ]; then
    curl --fail --location --silent --show-error \
        https://github.com/google/google-java-format/releases/download/v1.24.0/google-java-format-1.24.0-all-deps.jar \
        --output "$stride_formatter"
fi
python3 - "$stride_formatter" <<'PY'
import hashlib
from pathlib import Path
import sys
expected = '812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1'
if hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() != expected:
    raise SystemExit('Formatter checksum mismatch; remove .tooling/ and retry.')
PY
case "${1:---check}" in
    --check) find app/src -name '*.java' -print0 | xargs -0 java -jar "$stride_formatter" --dry-run --set-exit-if-changed ;;
    --fix) find app/src -name '*.java' -print0 | xargs -0 java -jar "$stride_formatter" --replace ;;
    *) echo 'Usage: scripts/format-java.sh [--check|--fix]' >&2; exit 2 ;;
esac
