#!/usr/bin/env bash
# Publish AeonRuntime AARs to the local Maven repository (~/.m2). Nothing is sent anywhere else.
#
# Usage:
#   scripts/publish_local.sh                      # core, os, terminal-view, shizuku
#   MODULES=":core :bootstrap-arm64" scripts/publish_local.sh
#
# Bootstrap modules are opt-in: building them downloads the bootstrap zip (network required).
set -euo pipefail
cd "$(dirname "$0")/.."

MODULES="${MODULES:-:core :os :terminal-view :shizuku}"
GROUP="$(grep '^LIB_GROUP_ID=' gradle.properties | cut -d= -f2)"
VERSION="$(grep '^LIB_VERSION=' gradle.properties | cut -d= -f2)"

echo "Publishing to local Maven repository: ${MODULES}"
tasks=()
for m in ${MODULES}; do tasks+=("${m}:publishToMavenLocal"); done
./gradlew "${tasks[@]}"

echo ""
echo "Add to your project:"
echo "  repositories { mavenLocal() }"
echo "  implementation(\"${GROUP}:aeonruntime-core:${VERSION}\")"
echo "  // optional: aeonruntime-os, aeonruntime-terminal, aeonruntime-shizuku, aeonruntime-bootstrap-<abi>"
