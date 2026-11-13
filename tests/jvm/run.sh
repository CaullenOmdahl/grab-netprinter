#!/usr/bin/env bash
# Offline JVM tests for the platform-independent GrabNetPrinter core (config + TCP bridge).
# Compiles Config/Log/Protocol/SocketBridge plus a JVM XposedBridge stub; no Android SDK needed.
#
#   tests/jvm/run.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT="$ROOT/tests/jvm/build"
JAVAC=${JAVAC:-/opt/homebrew/opt/openjdk@17/bin/javac}
JAVA=${JAVA:-/opt/homebrew/opt/openjdk@17/bin/java}

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
