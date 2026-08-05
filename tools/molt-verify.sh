#!/usr/bin/env bash
# Molt Gradle 插件 nightly 验证
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VARIANT="${INTEGRATION_VARIANT:-googleRelease}"
RUN_MAPPING_PARITY="${RUN_MAPPING_PARITY:-0}"

GRADLE=(
  ./gradlew
  :plugin:moltObfuscateNightlyVerify
  -PintegrationVariant="$VARIANT"
  -PrunMappingParityCheck="$RUN_MAPPING_PARITY"
)

if [[ -n "${JAVA_HOME:-}" ]]; then
  export JAVA_HOME
elif [[ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if [[ -n "${MOLT_INTEGRATION_ROOT:-}" ]]; then
  GRADLE+=(-PintegrationRoot="$MOLT_INTEGRATION_ROOT")
fi

echo "[molt] variant=$VARIANT nightly (unit + E2E + sample + optional host parity=$RUN_MAPPING_PARITY)"
"${GRADLE[@]}"
