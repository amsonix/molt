#!/usr/bin/env bash
# Full AGP probe: smoke + APK/AAB transform + rename (5 probes per matrix row).
# Matrix: tools/agp-compat-matrix.txt
# Report: build/reports/agp-compat/report.md
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

MATRIX_FILE="${AGP_COMPAT_MATRIX:-$ROOT/tools/agp-compat-matrix.txt}"
REPORT_DIR="$ROOT/build/reports/agp-compat"
REPORT_MD="$REPORT_DIR/report.md"
REPORT_TSV="$REPORT_DIR/report.tsv"

# Default: Aliyun mirrors first (Maven Central TLS often fails in CN). Set MOLT_PROBE_CHINA_MIRROR=0 to disable.
export MOLT_PROBE_CHINA_MIRROR="${MOLT_PROBE_CHINA_MIRROR:-1}"
export MOLT_REPO_ROOT="$ROOT"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.protocols=TLSv1.2,TLSv1.3 -Djdk.tls.client.protocols=TLSv1.2,TLSv1.3"

# Host Gradle 8.13 + TestKit Gradle 8.0–8.4 both need JDK 17–21; never use JDK 22+.
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

android_studio_jbr_homes() {
  local app sub
  for app in \
    "/Applications/Android Studio.app" \
    "$HOME/Applications/Android Studio.app" \
    "/Applications/Android Studio Preview.app"; do
    [[ -d "$app" ]] || continue
    for sub in "Contents/jbr/Contents/Home" "Contents/jre/Contents/Home"; do
      [[ -d "$app/$sub" ]] && echo "$app/$sub"
    done
  done
}

resolve_probe_java_home() {
  if [[ -n "${MOLT_PROBE_JAVA_HOME:-}" && -d "${MOLT_PROBE_JAVA_HOME}" ]]; then
    if is_probe_compatible_java_home "${MOLT_PROBE_JAVA_HOME}"; then
      echo "${MOLT_PROBE_JAVA_HOME}"
      return 0
    fi
    echo "[molt] WARN: MOLT_PROBE_JAVA_HOME=${MOLT_PROBE_JAVA_HOME} is JDK $(java_major_version "${MOLT_PROBE_JAVA_HOME}") (need 17–21), ignoring." >&2
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
  for base in \
    "$HOME/Library/Java/JavaVirtualMachines" \
    "/Library/Java/JavaVirtualMachines"; do
    [[ -d "$base" ]] || continue
    for dir in "$base"/*17* "$base"/temurin-17* "$base"/zulu-17*; do
      [[ -d "$dir" ]] || continue
      if [[ -d "$dir/Contents/Home" ]] && is_probe_compatible_java_home "$dir/Contents/Home"; then
        echo "$dir/Contents/Home"
        return 0
      fi
      if is_probe_compatible_java_home "$dir"; then
        echo "$dir"
        return 0
      fi
    done
  done
  for candidate in \
    "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
    "/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home" \
    "/usr/lib/jvm/java-17-openjdk" \
    "/usr/lib/jvm/temurin-17-jdk"; do
    if is_probe_compatible_java_home "$candidate"; then
      echo "$candidate"
      return 0
    fi
  done
  local jbr
  while IFS= read -r jbr; do
    if is_probe_compatible_java_home "$jbr"; then
      echo "$jbr"
      return 0
    fi
  done < <(android_studio_jbr_homes)
  if [[ -n "${JAVA_HOME:-}" ]] && is_probe_compatible_java_home "${JAVA_HOME}"; then
    echo "${JAVA_HOME}"
    return 0
  fi
  return 1
}

PROBE_JAVA_HOME="$(resolve_probe_java_home || true)"
if [[ -z "$PROBE_JAVA_HOME" ]]; then
  echo "[molt] ERROR: No JDK 17–21 found for AGP probes (shell JDK 22+ breaks Gradle 8.13)." >&2
  if [[ -n "${MOLT_PROBE_JAVA_HOME:-}" ]]; then
    echo "[molt] Hint: MOLT_PROBE_JAVA_HOME is set to JDK $(java_major_version "${MOLT_PROBE_JAVA_HOME}" 2>/dev/null || echo '?') — unset it or point to JDK 17/21:" >&2
    echo "[molt]   unset MOLT_PROBE_JAVA_HOME" >&2
  fi
  echo "[molt] Android Studio JBR (often JDK 21):" >&2
  echo "[molt]   export MOLT_PROBE_JAVA_HOME=\"/Applications/Android Studio.app/Contents/jbr/Contents/Home\"" >&2
  echo "[molt] Or Temurin 17: export MOLT_PROBE_JAVA_HOME=\$(/usr/libexec/java_home -v 17)" >&2
  exit 1
fi

export JAVA_HOME="$PROBE_JAVA_HOME"
export MOLT_PROBE_JAVA_HOME="$PROBE_JAVA_HOME"

echo "[molt] JAVA_HOME=$JAVA_HOME ($("$JAVA_HOME/bin/java" -version 2>&1 | head -1))"

mkdir -p "$REPORT_DIR"

run_probe() {
  local task=$1 agp=$2 gradle=$3
  if ./gradlew "$task" -PtestAgp="$agp" -PtestGradle="$gradle" --no-daemon -q; then
    echo "PASS"
  else
    echo "FAIL"
  fi
}

probe_rows=() probe_gradle=() probe_required=()
probe_smoke=() probe_apk=() probe_aab=() probe_apk_rename=() probe_aab_rename=()

while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%%#*}"
  line="$(echo "$line" | xargs)"
  [[ -z "$line" ]] && continue
  read -r agp gradle required <<< "$line"
  probe_rows+=("$agp")
  probe_gradle+=("$gradle")
  probe_required+=("$required")

  echo ""
  echo "========================================"
  echo "[molt] AGP probe: agp=$agp gradle=$gradle required=$required"
  echo "========================================"

  smoke=$(run_probe ":plugin:moltObfuscateAgpCompatTest" "$agp" "$gradle")
  echo "  smoke:        $smoke"
  sleep 2

  if [[ "$smoke" != "PASS" ]]; then
    apk=SKIP aab=SKIP apk_rename=SKIP aab_rename=SKIP
    echo "  apk e2e:      SKIP"
    echo "  aab e2e:      SKIP"
    echo "  apk rename:   SKIP"
    echo "  aab rename:   SKIP"
  else
    apk=$(run_probe ":plugin:moltObfuscateAgpCompatE2eTest" "$agp" "$gradle")
    echo "  apk e2e:      $apk"
    sleep 2
    aab=$(run_probe ":plugin:moltObfuscateAgpCompatBundleE2eTest" "$agp" "$gradle")
    echo "  aab e2e:      $aab"
    sleep 2
    apk_rename=$(run_probe ":plugin:moltObfuscateAgpCompatRenameApkE2eTest" "$agp" "$gradle")
    echo "  apk rename:   $apk_rename"
    sleep 2
    aab_rename=$(run_probe ":plugin:moltObfuscateAgpCompatRenameAabE2eTest" "$agp" "$gradle")
    echo "  aab rename:   $aab_rename"
  fi

  probe_smoke+=("$smoke")
  probe_apk+=("$apk")
  probe_aab+=("$aab")
  probe_apk_rename+=("$apk_rename")
  probe_aab_rename+=("$aab_rename")
  sleep 2
done < "$MATRIX_FILE"

{
  echo "# AGP compatibility probe report"
  echo
  echo "Generated: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  echo
  echo "| AGP | Gradle | Req | Smoke | APK | AAB | APK rename | AAB rename | Pass |"
  echo "|-----|--------|-----|-------|-----|-----|------------|------------|------|"
} > "$REPORT_MD"

echo -e "agp\tgradle\trequired\tsmoke\tapk\taab\tapk_rename\taab_rename\tpass" > "$REPORT_TSV"

pass_agps=()
required_failures=0

for i in "${!probe_rows[@]}"; do
  agp="${probe_rows[$i]}"
  gradle="${probe_gradle[$i]}"
  required="${probe_required[$i]}"
  smoke="${probe_smoke[$i]}"
  apk="${probe_apk[$i]}"
  aab="${probe_aab[$i]}"
  apk_rename="${probe_apk_rename[$i]}"
  aab_rename="${probe_aab_rename[$i]}"

  pass=$([[ "$smoke" == "PASS" && "$apk" == "PASS" && "$aab" == "PASS" && "$apk_rename" == "PASS" && "$aab_rename" == "PASS" ]] && echo PASS || echo FAIL)

  [[ "$pass" == "PASS" ]] && pass_agps+=("$agp")

  req_label=$([[ "$required" == "1" ]] && echo yes || echo no)
  echo "| $agp | $gradle | $req_label | $smoke | $apk | $aab | $apk_rename | $aab_rename | $pass |" >> "$REPORT_MD"
  echo -e "$agp\t$gradle\t$required\t$smoke\t$apk\t$aab\t$apk_rename\t$aab_rename\t$pass" >> "$REPORT_TSV"

  if [[ "$required" == "1" && "$pass" != "PASS" ]]; then
    required_failures=$((required_failures + 1))
  fi
done

{
  echo
  echo "## Inferred range (matrix subset only)"
  echo
  if [[ "${#pass_agps[@]}" -eq 0 ]]; then
    echo "- **Full pipeline pass (5 probes):** none"
  else
    echo "- **Full pipeline pass:** ${pass_agps[*]} → \`${pass_agps[0]}\` – \`${pass_agps[${#pass_agps[@]}-1]}\`"
  fi
  echo
  echo "## Probes"
  echo
  echo "| Probe | Gradle task | Validates |"
  echo "|-------|-------------|-----------|"
  echo "| Smoke | \`moltObfuscateAgpCompatTest\` | prepare / resources / junk / mapping wiring |"
  echo "| APK E2E | \`moltObfuscateAgpCompatE2eTest\` | assemble + APK transform (no rename) |"
  echo "| AAB E2E | \`moltObfuscateAgpCompatBundleE2eTest\` | bundle + AAB transform |"
  echo "| APK rename | \`moltObfuscateAgpCompatRenameApkE2eTest\` | assemble + transform + merge mapping |"
  echo "| AAB rename | \`moltObfuscateAgpCompatRenameAabE2eTest\` | bundle + transform + merge mapping |"
} >> "$REPORT_MD"

echo ""
echo "[molt] Report: $REPORT_MD"
cat "$REPORT_MD"

if [[ "$required_failures" -gt 0 ]]; then
  echo "[molt] AGP probe FAILED: required row(s) did not pass"
  exit 1
fi

echo "[molt] AGP probe finished"
