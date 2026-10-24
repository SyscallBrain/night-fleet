#!/usr/bin/env bash
# Full local verification: unit tests, content checks (when a catalog exists), lint, debug build,
# manifest permissions and dependency policy. Each step keeps its log in logs/ and reports its real
# exit code. Gradle always runs through tools/gw.sh (never ./gradlew directly, never --stop).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p logs
# A partial catalog (fewer seas than the full campaign, see leveltool --partial) is only accepted when asked:
# NIGHTFLEET_PARTIAL_CONTENT=1 passes -PnightFleet.partialContent=true to the content and APK steps.
content_props=()
if [[ ${NIGHTFLEET_PARTIAL_CONTENT:-0} == 1 ]]; then content_props=(-PnightFleet.partialContent=true); fi
run_check() {
    local name=$1
    shift
    printf 'Running %s...\n' "$name"
    local result=0
    "$@" >"logs/$name.log" 2>&1 || result=$?
    if (( result == 0 )); then
        printf '%s: PASS (exit 0)\n' "$name"
    else
        tail -70 "logs/$name.log" >&2
        printf '%s: FAIL (exit %s)\n' "$name" "$result" >&2
        exit "$result"
    fi
}
run_check tests tools/gw.sh "${content_props[@]}" :core:test :leveltool:test :app:testDebugUnitTest
# Content checks need a published catalog (phase M5); skipped until content/ exists.
if [[ -f content/catalog.json ]]; then
    run_check content tools/gw.sh "${content_props[@]}" :leveltool:validateCatalog
    run_check certificates tools/gw.sh :leveltool:verifyCertificates
fi
run_check apk tools/gw.sh "${content_props[@]}" :app:lintDebug :app:assembleDebug
run_check permissions bash tools/check_permissions.sh
run_check dependencies bash tools/check_deps.sh
printf '%s\n' 'All checks passed. Debug APK at app/build/outputs/apk/debug/app-debug.apk'
