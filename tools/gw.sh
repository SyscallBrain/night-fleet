#!/usr/bin/env bash
# Gradle wrapper: sets the toolchain (JAVA_HOME and ANDROID_HOME can be overridden) and allows at most
# two concurrent builds on the machine, so parallel checkouts never exhaust memory.
set -euo pipefail
cd -- "$(git rev-parse --show-toplevel)"
export JAVA_HOME=${JAVA_HOME:-/opt/android-studio/jbr} ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
[[ -f local.properties ]] || printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
lockdir=/tmp/nightfleet-gradle-locks
mkdir -p "$lockdir"
while true; do
    for slot in 1 2; do
        exec {fd}>"$lockdir/slot$slot"
        if flock -n "$fd"; then
            # A private daemon registry keeps other projects' `gradlew --stop` from killing our builds.
            ./gradlew --console=plain --max-workers=2 -Dorg.gradle.daemon.registry.base="$HOME/.gradle/nightfleet-daemons" "$@"
            exit $?
        fi
        exec {fd}>&-
    done
    sleep 5
done
