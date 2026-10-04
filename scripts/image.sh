#!/bin/bash
# Builds the jars and the canaryjudge:dev image (run inside WSL from the work tree).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon -q :target:bootJar :traffic:jar $( [ -d server ] && echo :server:bootJar )
CTX=$(mktemp -d)
cp target/build/libs/target-service.jar traffic/build/libs/traffic.jar "$CTX"/
[ -f server/build/libs/canaryjudge-server.jar ] && cp server/build/libs/canaryjudge-server.jar "$CTX"/
cp deploy/Dockerfile "$CTX"/
docker build -q -t canaryjudge:dev "$CTX"
rm -rf "$CTX"
