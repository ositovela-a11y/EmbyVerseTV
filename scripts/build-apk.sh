#!/usr/bin/env bash
# Compila EmbyVerse-debug.apk (y el release si existe keystore.properties).
# Requisitos: JDK 17+ y Android SDK (Android Studio) con ANDROID_HOME o local.properties.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android-app"
"$ROOT/scripts/sync-shared.sh"
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug
mkdir -p "$ROOT/out"
cp app/build/outputs/apk/debug/app-debug.apk "$ROOT/out/EmbyVerse-debug.apk"
echo "APK de depuración: out/EmbyVerse-debug.apk"
if [ -f keystore.properties ]; then
  ./gradlew --no-daemon :app:assembleRelease
  cp app/build/outputs/apk/release/app-release.apk "$ROOT/out/EmbyVerse-release.apk"
  echo "APK de producción firmado: out/EmbyVerse-release.apk"
else
  echo "Sin keystore.properties: no se genera el APK de producción firmado (ver docs/BUILD.md)."
fi
