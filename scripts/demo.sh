#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
BUILD_DIR="$ROOT_DIR/async-replay-core/target/standalone-demo"
mkdir -p "$BUILD_DIR/classes"
find async-replay-core/src/main/java -name '*.java' -print | LC_ALL=C sort > "$BUILD_DIR/sources.txt"
"${JAVAC_BIN:-javac}" --release 17 -Xlint:all -Werror -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
"${JAVA_BIN:-java}" -cp "$BUILD_DIR/classes" dev.asyncreplay.example.TimeoutRaceDemo "$@"
