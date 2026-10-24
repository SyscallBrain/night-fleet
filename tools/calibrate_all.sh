#!/usr/bin/env bash
# Calibrates the whole campaign of a content root, sea by sea, then seals it and
# runs the strict checks. Each sea runs `calibrate --sea N --resume`, so an interrupted run continues where it
# stopped. Logs go to logs/calibrate-sNN.log; every step reports its real exit code and the first failing
# step stops the script with that code.
#
#   tools/calibrate_all.sh --root DIR [--threads N] [--games N]
#
# DIR must already hold the generated campaign (tools/content_pipeline.sh --sea N generates one sea at a time).
# The leveltool runs through tools/gw.sh with the -Xmx2g heap of the :leveltool:run task, never ./gradlew.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

usage() {
    printf 'usage: tools/calibrate_all.sh --root DIR [--threads N] [--games N]\n' >&2
    exit 2
}

root=""
threads=4
games=64
while (( $# > 0 )); do
    case "$1" in
        --root) root=${2:-}; shift 2 ;;
        --threads) threads=${2:-}; shift 2 ;;
        --games) games=${2:-}; shift 2 ;;
        *) usage ;;
    esac
done
[[ -n $root ]] || usage
[[ -d $root ]] || { printf 'calibrate_all: %s is not a directory\n' "$root" >&2; exit 2; }
[[ $threads =~ ^[1-9][0-9]*$ ]] || { printf 'calibrate_all: --threads must be a positive integer\n' >&2; exit 2; }
[[ $games =~ ^[1-9][0-9]*$ ]] || { printf 'calibrate_all: --games must be a positive integer\n' >&2; exit 2; }
root=$(cd -- "$root" && pwd)
mkdir -p logs

leveltool() {
    tools/gw.sh :leveltool:run --args="$*"
}

# Runs one step, appending its output to [log]; on failure prints the log and exits with the step's code.
step() {
    local name=$1 log=$2
    shift 2
    local result=0
    printf 'calibrate_all: %s...\n' "$name"
    "$@" >>"$log" 2>&1 || result=$?
    printf 'calibrate_all: %s exit %s\n' "$name" "$result"
    if (( result != 0 )); then
        printf 'calibrate_all: FAILED at %s, see %s\n' "$name" "$log" >&2
        exit "$result"
    fi
}

for sea in 1 2 3 4 5 6 7 8 9 10 11 12; do
    log=$(printf 'logs/calibrate-s%02d.log' "$sea")
    : >"$log"
    step "calibrate sea $sea" "$log" leveltool "calibrate --root $root --sea $sea --games $games --threads $threads --resume"
    grep -E '^(calibrate:|s[0-9]{2}m[0-9]{3} )' "$log" | tail -n 4 || true
done

step "calibrate daily hunts" logs/calibrate-daily.log leveltool "calibrate --root $root --daily --games $games --threads $threads --resume"
grep -E '^(calibrate:|v[0-9]+-hunt-)' logs/calibrate-daily.log | tail -n 4 || true

step "seal" logs/calibrate-seal.log leveltool "seal --root $root"
step "validate --strict" logs/calibrate-validate.log leveltool "validate --strict --root $root"
step "verify-certificates" logs/calibrate-verify.log leveltool "verify-certificates --root $root"
step "report" logs/calibrate-report.log leveltool "report --root $root --output $root/report.md"

printf 'calibrate_all: done, logs in logs/, report in %s/report.md\n' "$root"
