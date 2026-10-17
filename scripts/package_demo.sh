#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
if [ "$#" -gt 1 ]; then
    printf '%s\n' 'Usage: sh scripts/package_demo.sh [output-jar]' >&2
    exit 2
fi
OUTPUT_JAR=${1:-target/async-replay-reducer-demo.jar}
mkdir -p target "$(dirname -- "$OUTPUT_JAR")"
BUILD_DIR=$(mktemp -d "$ROOT_DIR/target/package-demo.XXXXXX")
trap 'rm -rf "$BUILD_DIR"' 0
trap 'exit 1' HUP INT TERM
mkdir -p "$BUILD_DIR/classes"
find async-replay-core/src/main/java async-replay-reducer/src/main/java -name '*.java' -print | LC_ALL=C sort > "$BUILD_DIR/sources.txt"
"${JAVAC_BIN:-javac}" --release 17 -Xlint:all -Werror -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
"${JAR_BIN:-jar}" --create --file "$OUTPUT_JAR" --main-class dev.asyncreplay.reducer.ReducerExperiment \
    -C "$BUILD_DIR/classes" . -C "$ROOT_DIR" LICENSE-NOTICE.md
printf 'Built %s\n' "$OUTPUT_JAR"
