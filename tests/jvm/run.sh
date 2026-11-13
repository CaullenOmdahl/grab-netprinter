#!/usr/bin/env bash
# Offline JVM tests for the platform-independent GrabNetPrinter core (config + TCP bridge).
# Compiles Config/Log/Protocol/SocketBridge plus a JVM XposedBridge stub; no Android SDK needed.
#
#   tests/jvm/run.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/tests/jvm/build"
# Resolve a JDK portably (macOS Homebrew, Linux CI with JAVA_HOME, or PATH).
if [ -z "${JAVAC:-}" ] || [ ! -x "${JAVAC:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVAC="$JAVA_HOME/bin/javac"
  elif [ -x /opt/homebrew/opt/openjdk@17/bin/javac ]; then
    JAVAC=/opt/homebrew/opt/openjdk@17/bin/javac
  else
    JAVAC=$(command -v javac || true)
  fi
fi
if [ -z "${JAVA:-}" ] || [ ! -x "${JAVA:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1; then
    JAVA=$(command -v java)
  else
    JAVA="$(dirname "$JAVAC")/java"
  fi
fi
[ -x "$JAVAC" ] || { echo "javac not found; set JAVA_HOME" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes"

SRC=(
  "$ROOT/app/src/main/java/dev/blacksheep/grabnetprinter/Config.java"
  "$ROOT/app/src/main/java/dev/blacksheep/grabnetprinter/Log.java"
  "$ROOT/app/src/main/java/dev/blacksheep/grabnetprinter/Protocol.java"
  "$ROOT/app/src/main/java/dev/blacksheep/grabnetprinter/SocketBridge.java"
  "$ROOT/tests/jvm/stubs/de/robv/android/xposed/XposedBridge.java"
  "$ROOT/tests/jvm/src/dev/blacksheep/grabnetprinter/JvmTests.java"
)

"$JAVAC" -encoding UTF-8 -d "$OUT/classes" "${SRC[@]}"
"$JAVA" -cp "$OUT/classes" dev.blacksheep.grabnetprinter.JvmTests
