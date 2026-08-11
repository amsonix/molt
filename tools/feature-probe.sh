#!/usr/bin/env bash
# Feature probe: molt {} presets at fixed AGP (orthogonal to agp-compat-matrix.txt).
# Matrix: tools/feature-probe-matrix.txt
# Report: build/reports/feature-probe/report.md
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MATRIX_FILE="${FEATURE_PROBE_MATRIX:-$ROOT/tools/feature-probe-matrix.txt}"
REPORT_DIR="$ROOT/build/reports/feature-probe"
REPORT_MD="$REPORT_DIR/report.md"
REPORT_TSV="$REPORT_DIR/report.tsv"
TIER_FILTER="${FEATURE_PROBE_TIER:-all}"
SINGLE_FEATURE="${MOLT_FEATURE_PROBE:-}"
# 覆盖矩阵行 AGP/Gradle（ad-hoc 跨 AGP feature 探测）；须同时设置。
OVERRIDE_AGP="${MOLT_FEATURE_AGP:-${MOLT_TEST_AGP:-}}"
OVERRIDE_GRADLE="${MOLT_FEATURE_GRADLE:-${MOLT_TEST_GRADLE:-}}"

export MOLT_PROBE_CHINA_MIRROR="${MOLT_PROBE_CHINA_MIRROR:-1}"
export MOLT_REPO_ROOT="$ROOT"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"

java_major_version() {
  local home="$1"
  "$home/bin/java" -version 2>&1 | sed -En 's/.*version "([0-9]+).*/\1/p' | head -1
}

is_probe_compatible_java_home() {
  local home="$1" major
  [[ -d "$home" && -x "$home/bin/java" ]] || return 1
  major="$(java_major_version "$home")"
  [[ -n "$major" && "$major" -ge 17 && "$major" -le 21 ]]
}

resolve_probe_java_home() {
  if [[ -n "${MOLT_PROBE_JAVA_HOME:-}" && -d "${MOLT_PROBE_JAVA_HOME}" ]]; then
    if is_probe_compatible_java_home "${MOLT_PROBE_JAVA_HOME}"; then
      echo "${MOLT_PROBE_JAVA_HOME}"
      return 0
    fi
    echo "[molt] WARN: MOLT_PROBE_JAVA_HOME=${MOLT_PROBE_JAVA_HOME} is JDK $(java_major_version "${MOLT_PROBE_JAVA_HOME}" 2>/dev/null || echo '?') (need 17–21), ignoring." >&2
  fi
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jdk
    for jdk in "$(/usr/libexec/java_home -v 17 2>/dev/null)" "$(/usr/libexec/java_home -v 21 2>/dev/null)"; do
      [[ -n "$jdk" ]] || continue
      if is_probe_compatible_java_home "$jdk"; then
        echo "$jdk"
        return 0
      fi
    done
  fi
  for candidate in \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
    "/usr/lib/jvm/java-17-openjdk" \
    "/usr/lib/jvm/temurin-17-jdk"; do
    if is_probe_compatible_java_home "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done
  if [[ -n "${JAVA_HOME:-}" ]] && is_probe_compatible_java_home "${JAVA_HOME}"; then
    echo "${JAVA_HOME}"
    return 0
  fi
  return 1
}

PROBE_JAVA_HOME="$(resolve_probe_java_home || true)"
if [[ -z "$PROBE_JAVA_HOME" ]]; then
  echo "[molt] ERROR: No JDK 17–21 found for feature probes." >&2
  exit 1
fi

export JAVA_HOME="$PROBE_JAVA_HOME"
export MOLT_PROBE_JAVA_HOME="$PROBE_JAVA_HOME"
echo "[molt] JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

if [[ -n "$OVERRIDE_AGP" || -n "$OVERRIDE_GRADLE" ]]; then
  if [[ -z "$OVERRIDE_AGP" || -z "$OVERRIDE_GRADLE" ]]; then
    echo "[molt] ERROR: MOLT_FEATURE_AGP and MOLT_FEATURE_GRADLE must both be set (or both empty)." >&2
    exit 1
  fi
  echo "[molt] AGP override active: agp=$OVERRIDE_AGP gradle=$OVERRIDE_GRADLE (TestKit rows ignore matrix AGP column)"
fi

mkdir -p "$REPORT_DIR"

has_sample_project() {
  [[ -f "$ROOT/sample/app/build.gradle.kts" || -f "$ROOT/sample/app/build.gradle" ]]
}

has_integration_root() {
  if [[ -n "${MOLT_INTEGRATION_ROOT:-}" && -f "${MOLT_INTEGRATION_ROOT}/app/build.gradle.kts" ]]; then
    return 0
  fi
  if [[ -n "${MOLT_INTEGRATION_ROOT:-}" && -f "${MOLT_INTEGRATION_ROOT}/app/build.gradle" ]]; then
    return 0
  fi
  return 1
}

run_row() {
  local feature=$1 preset=$2 probe=$3 agp=$4 gradle=$5 tier=$6 required=$7
  case "$probe" in
    sample)
      if ! has_sample_project; then
        echo "SKIP"
        return 0
      fi
      if ./gradlew :plugin:moltObfuscateSampleAssemble --no-daemon -q; then
        echo "PASS"
      else
        echo "FAIL"
      fi
      ;;
    integration)
      if ! has_integration_root; then
        echo "SKIP"
        return 0
      fi
      local -a integration_args=()
      if [[ -n "${MOLT_INTEGRATION_ROOT:-}" ]]; then
        integration_args=(-PintegrationRoot="${MOLT_INTEGRATION_ROOT}")
      fi
      if ./gradlew :plugin:moltObfuscateIntegrationPrepare "${integration_args[@]}" --no-daemon -q; then
        echo "PASS"
      else
        echo "FAIL"
      fi
      ;;
    *)
      if ./gradlew :plugin:moltObfuscateFeatureProbeTest \
        -PmoltFeature="$feature" \
        -PtestAgp="$agp" \
        -PtestGradle="$gradle" \
        --no-daemon -q; then
        # TestKit skipped（如 runtime 探针无设备）退出码仍为 0——查结果 XML 区分，避免假 PASS。
        local results_xml
        results_xml=$(ls plugin/build/test-results/moltObfuscateFeatureProbeTest/TEST-*.xml 2>/dev/null | head -1)
        if [[ -n "$results_xml" ]] && grep -q 'skipped="[1-9]' "$results_xml" 2>/dev/null; then
          echo "SKIP"
        else
          echo "PASS"
        fi
      else
        echo "FAIL"
      fi
      ;;
  esac
}

probe_ids=() probe_presets=() probe_types=() probe_agps=() probe_gradles=() probe_tiers=() probe_required=() probe_results=()

while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%#*}"
  line="$(echo "$line" | xargs)"
  [[ -z "$line" ]] && continue

  read -r feature preset probe agp gradle tier required <<< "$line"

  if [[ -n "$SINGLE_FEATURE" && "$feature" != "$SINGLE_FEATURE" ]]; then
    continue
  fi
  if [[ "$TIER_FILTER" != "all" && "$tier" != "$TIER_FILTER" ]]; then
    continue
  fi

  effective_agp="$agp"
  effective_gradle="$gradle"
  if [[ -n "$OVERRIDE_AGP" && "$probe" != "sample" && "$probe" != "integration" ]]; then
    effective_agp="$OVERRIDE_AGP"
    effective_gradle="$OVERRIDE_GRADLE"
  fi

  echo ""
  echo "========================================"
  echo "[molt] Feature probe: $feature preset=$preset probe=$probe agp=$effective_agp gradle=$effective_gradle tier=$tier"
  echo "========================================"

  result=$(run_row "$feature" "$preset" "$probe" "$effective_agp" "$effective_gradle" "$tier" "$required")
  echo "  result: $result"

  probe_ids+=("$feature")
  probe_presets+=("$preset")
  probe_types+=("$probe")
  probe_agps+=("$effective_agp")
  probe_gradles+=("$effective_gradle")
  probe_tiers+=("$tier")
  probe_required+=("$required")
  probe_results+=("$result")
  sleep 2
done < "$MATRIX_FILE"

{
  echo "# Feature probe report"
  echo
  echo "Generated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  echo "Tier filter: $TIER_FILTER"
  if [[ -n "$OVERRIDE_AGP" ]]; then
    echo "AGP override: $OVERRIDE_AGP / Gradle $OVERRIDE_GRADLE"
  fi
  echo
  echo "| Feature | Preset | Probe | AGP | Gradle | Tier | Req | Result |"
  echo "|---------|--------|-------|-----|--------|------|-----|--------|"
} > "$REPORT_MD"

echo -e "feature\tpreset\tprobe\tagp\tgradle\ttier\trequired\tresult" > "$REPORT_TSV"

required_failures=0
pass_count=0

for i in "${!probe_ids[@]}"; do
  feature="${probe_ids[$i]}"
  preset="${probe_presets[$i]}"
  probe="${probe_types[$i]}"
  agp="${probe_agps[$i]}"
  gradle="${probe_gradles[$i]}"
  tier="${probe_tiers[$i]}"
  required="${probe_required[$i]}"
  result="${probe_results[$i]}"

  [[ "$result" == "PASS" ]] && pass_count=$((pass_count + 1))
  req_label=$([[ "$required" == "1" ]] && echo yes || echo no)
  echo "| $feature | $preset | $probe | $agp | $gradle | $tier | $req_label | $result |" >> "$REPORT_MD"
  echo -e "$feature\t$preset\t$probe\t$agp\t$gradle\t$tier\t$required\t$result" >> "$REPORT_TSV"

  if [[ "$required" == "1" && "$result" == "FAIL" ]]; then
    required_failures=$((required_failures + 1))
  fi
done

{
  echo
  echo "## Summary"
  echo
  echo "- **Pass:** $pass_count / ${#probe_ids[@]}"
  echo
  echo "Run single row: \`MOLT_FEATURE_PROBE=F01-overlay-rename ./tools/feature-probe.sh\`"
  echo "Gate tier: \`FEATURE_PROBE_TIER=gate ./tools/feature-probe.sh\`"
  echo "AGP 8.0 gate subset: \`MOLT_FEATURE_AGP=8.0.2 MOLT_FEATURE_GRADLE=8.0 FEATURE_PROBE_TIER=gate ./tools/feature-probe.sh\`"
} >> "$REPORT_MD"

echo ""
echo "[molt] Report: $REPORT_MD"
cat "$REPORT_MD"

if [[ "${#probe_ids[@]}" -eq 0 ]]; then
  echo "[molt] ERROR: No matrix rows matched (tier=$TIER_FILTER feature=$SINGLE_FEATURE)" >&2
  exit 1
fi

if [[ "$required_failures" -gt 0 ]]; then
  echo "[molt] Feature probe FAILED: required row(s) did not pass"
  exit 1
fi

echo "[molt] Feature probe finished"
