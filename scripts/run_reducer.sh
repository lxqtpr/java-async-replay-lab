#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
if [ "$#" -ne 1 ]; then
    printf '%s\n' 'Usage: sh scripts/run_reducer.sh <empty-output-dir>' >&2
    exit 2
fi
BUILD_DIR="$ROOT_DIR/async-replay-reducer/target/runner"
mkdir -p "$BUILD_DIR/classes"
find async-replay-core/src/main/java async-replay-reducer/src/main/java -name '*.java' -print | LC_ALL=C sort > "$BUILD_DIR/sources.txt"
"${JAVAC_BIN:-javac}" --release 17 -Xlint:all -Werror -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
"${JAVA_BIN:-java}" "-Dasyncreplay.repoRoot=$ROOT_DIR" -cp "$BUILD_DIR/classes" dev.asyncreplay.reducer.ReducerExperiment "$@"
