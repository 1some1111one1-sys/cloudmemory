#!/usr/bin/env bash
# Compiles the pure-Java core and runs the protocol tests against in-process mock WebDAV / S3 servers.
# Needs only a JDK (11+). Run from anywhere:  bash tests/run.sh
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac -Xlint:all -d "$OUT" app/src/main/java/com/cloudbridge/app/core/*.java tests/*.java
# UTF-8 locale so the mock WebDAV server can store files with non-ASCII names on disk.
LC_ALL=C.UTF-8 java -Dfile.encoding=UTF-8 -cp "$OUT" com.cloudbridge.app.core.TestMain
