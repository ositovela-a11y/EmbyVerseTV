#!/usr/bin/env bash
# Copia los recursos compartidos (configuración predeterminada) a la app Android y al panel.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cp "$ROOT/shared/default-config.json" "$ROOT/android-app/app/src/main/assets/default_config.json"
echo "default_config.json sincronizado"
