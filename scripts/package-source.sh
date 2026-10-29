#!/usr/bin/env bash
# Empaqueta el código fuente en EmbyVerse-source.zip excluyendo compilados, dependencias y secretos.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/../EmbyVerse-source.zip}"
cd "$ROOT/.."
rm -f "$OUT"
zip -qr "$OUT" "$(basename "$ROOT")" \
  -x "*/node_modules/*" "*/dist/*" "*/build/*" "*/.gradle/*" "*/data/*" "*.db" "*/local.properties" "*/keystore.properties" "*.jks" "*/.env" "*/out/*"
echo "Creado $OUT ($(du -h "$OUT" | cut -f1))"
