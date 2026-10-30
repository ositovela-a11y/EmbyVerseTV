#!/usr/bin/env bash
# Compila EmbyVerse Web Local (un solo archivo, sin instalar nada) para Windows, macOS y Linux.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/local-server"
rm -rf web && mkdir web && cp -r "$ROOT/web-player/." web/
# Copia local de los motores de video (mismas versiones que la web en internet).
# LIBS_DIR permite pasar una carpeta con hls.min.js y mpegts.js si no hay acceso a npm.
mkdir -p web/vendor
if [ -n "${LIBS_DIR:-}" ]; then
  cp "$LIBS_DIR"/hls.min.js web/vendor/ 2>/dev/null || true; cp "$LIBS_DIR"/mpegts.js web/vendor/ 2>/dev/null || true
else
  TMP=$(mktemp -d)
  ( cd "$TMP" && npm pack --silent hls.js@1.5.17 mpegts.js@1.7.3 >/dev/null && for f in *.tgz; do mkdir "${f%.tgz}" && tar xzf "$f" -C "${f%.tgz}"; done )
  cp "$TMP"/hls.js-1.5.17/package/dist/hls.min.js "$TMP"/mpegts.js-1.7.3/package/dist/mpegts.js web/vendor/
  rm -rf "$TMP"
fi
ls web/vendor
mkdir -p "$ROOT/out"
VER=$(grep -oE 'const version = "[^"]+"' main.go | cut -d'"' -f2)
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o "$ROOT/out/EmbyVerse-Web-Local.exe" .
GOOS=darwin  GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o "$ROOT/out/EmbyVerse-Web-Local-mac" .
GOOS=linux   GOARCH=amd64 go build -trimpath -ldflags "-s -w" -o "$ROOT/out/EmbyVerse-Web-Local-linux" .
echo "EmbyVerse Web Local $VER → out/"
ls -la "$ROOT/out"
