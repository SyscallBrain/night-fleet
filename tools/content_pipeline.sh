#!/usr/bin/env bash
# Content pipeline for one sea: generate -> calibrate -> seal -> validate -> report.
# Every step keeps its output in logs/ and reports its real exit code. Calibration simulates the human
# players of the sea; its variants stay UNCALIBRATED only when a step is skipped.
# Leveltool runs through tools/gw.sh with the -Xmx2g heap of the :leveltool:run task (never ./gradlew).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

usage() {
    printf 'usage: tools/content_pipeline.sh --sea N --output DIR [--seed X] [--threads N] [--games N]\n' >&2
    exit 2
}

sea=""
output=""
seed=""
threads=2
games=64
while (( $# > 0 )); do
    case "$1" in
        --sea) sea=${2:-}; shift 2 ;;
        --output) output=${2:-}; shift 2 ;;
        --seed) seed=${2:-}; shift 2 ;;
        --threads) threads=${2:-}; shift 2 ;;
        --games) games=${2:-}; shift 2 ;;
        *) usage ;;
    esac
done
[[ -n $sea && -n $output ]] || usage
[[ $sea =~ ^([1-9]|1[0-2])$ ]] || { printf 'pipeline: --sea must be 1..12\n' >&2; exit 2; }

mkdir -p logs
log="logs/content-pipeline-s$(printf '%02d' "$sea").log"
: > "$log"
seed_arg=()
if [[ -n $seed ]]; then seed_arg=(--seed "$seed"); fi

# Runs one step, logs its output and stops the pipeline with the step's exit code on failure.
step() {
    local name=$1
    shift
    local result=0
    printf 'pipeline: %s...\n' "$name"
    "$@" >>"$log" 2>&1 || result=$?
    printf 'pipeline: %s exit %s\n' "$name" "$result"
    if (( result != 0 )); then
        printf 'pipeline: FAILED at %s, see %s\n' "$name" "$log" >&2
        exit "$result"
    fi
}

leveltool() {
    tools/gw.sh :leveltool:run --args="$*"
}

step generate leveltool "generate --sea $sea --output $output ${seed_arg[*]:-}"

step calibrate leveltool "calibrate --root $output --sea $sea --games $games --threads $threads --resume"

step seal leveltool "seal --root $output"

step validate leveltool "validate --root $output"

# Strict mode may only report what the calibration still has to fill (UNCALIBRATED) and the campaign
# size of a partial run. Anything else is a real error and fails the pipeline.
strict_log="$log.strict"
strict_result=0
leveltool "validate --strict --root $output" >"$strict_log" 2>&1 || strict_result=$?
printf 'pipeline: validate --strict exit %s (%s UNCALIBRATED problem(s))\n' "$strict_result" "$(grep -c '^ERROR .*UNCALIBRATED' "$strict_log" || true)"
cat "$strict_log" >>"$log"
unexpected=$(grep '^ERROR' "$strict_log" | grep -v 'UNCALIBRATED' | grep -v 'strict:' | grep -v 'is missing' || true)
if [[ -n $unexpected ]]; then
    printf '%s\n' "$unexpected" >&2
    printf 'pipeline: FAILED at validate --strict: unexpected errors, see %s\n' "$log" >&2
    exit 1
fi

step report leveltool "report --root $output --output $output/report.md"

printf 'pipeline: done, log in %s, report in %s/report.md\n' "$log" "$output"
