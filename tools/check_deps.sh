#!/usr/bin/env bash
# Fails if the release runtime classpath contains a non-free or tracking dependency.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p logs
out=logs/dependencies-releaseRuntimeClasspath.txt
tools/gw.sh :app:dependencies --configuration releaseRuntimeClasspath >"$out"
forbidden='com\.google\.android\.gms|com\.google\.firebase|com\.google\.android\.play|com\.crashlytics|io\.fabric|com\.google\.mlkit'
status=0
matches=$(grep -E "$forbidden" "$out") || status=$?
if (( status == 0 )); then
    printf 'Forbidden dependency groups found:\n%s\n' "$matches" >&2
    exit 1
fi
printf 'check_deps: PASS (no forbidden groups; full tree in %s)\n' "$out"
