#!/usr/bin/env sh
set -eu
ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT_DIR"
# Arguments such as -o are passed to Maven. The Docker/PostgreSQL demo is opt-in.
mvn "$@" -q -pl async-replay-core,async-replay-junit5,async-replay-reducer,junit5-deterministic-async-extension,experiment-runner -am test
