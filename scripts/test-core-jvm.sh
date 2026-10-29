#!/usr/bin/env bash
# Compila y prueba el módulo :core (Kotlin JVM puro) SIN Android SDK ni red, usando el
# compilador Kotlin embebido que trae la distribución de Gradle (lib/). Útil en CI ligeros.
# En un entorno normal usa:  cd android-app && ./gradlew :core:test
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_LIB="${GRADLE_LIB:-$(dirname "$(readlink -f "$(command -v gradle)")")/../lib}"
L="$GRADLE_LIB"
jar() { { ls "$L"/$1 "$L"/plugins/$1 2>/dev/null || true; } | head -1; }
STD=$(jar 'kotlin-stdlib-2*.jar'); SER=$(jar 'kotlinx-serialization-json-jvm-*.jar'); SERC=$(jar 'kotlinx-serialization-core-jvm-*.jar')
COR=$(jar 'kotlinx-coroutines-core-jvm-*.jar'); JUNIT=$(jar 'junit-4*.jar'); HAM=$(jar 'hamcrest-core-*.jar')
[ -z "$HAM" ] && HAM=$(jar 'hamcrest-*.jar')
COMPILER_CP="$(jar 'kotlin-compiler-embeddable-*.jar'):$STD:$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar'):$(jar 'trove4j-*.jar'):$(jar 'annotations-*.jar'):$COR"
LIBS="$STD:$SER:$SERC:$COR"
OUT="${OUT:-$ROOT/android-app/core/build/jvm}"
rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
kotlinc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true; }
echo "== Compilando core/main"
kotlinc -cp "$LIBS" -d "$OUT/main" $(find "$ROOT/android-app/core/src/main/kotlin" -name '*.kt')
test -n "$(find "$OUT/main" -name '*.class' | head -1)" || { echo "Fallo de compilación (main)"; exit 1; }
echo "== Compilando core/test"
kotlinc -cp "$LIBS:$OUT/main:$JUNIT:$HAM" -d "$OUT/test" $(find "$ROOT/android-app/core/src/test/kotlin" -name '*.kt')
test -n "$(find "$OUT/test" -name '*Test.class' | head -1)" || { echo "Fallo de compilación (test)"; exit 1; }
cp -r "$ROOT/android-app/core/src/test/resources/." "$OUT/test/" 2>/dev/null || true
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort)
echo "== Ejecutando JUnit: $(echo $CLASSES | wc -w) clases"
EMBYVERSE_ROOT="$ROOT" java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$LIBS:$OUT/main:$OUT/test:$JUNIT:$HAM" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v JAVA_TOOL_OPTIONS
