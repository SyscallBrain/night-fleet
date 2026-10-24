#!/usr/bin/env bash
# JVM-independent check of the release dex (R8 output). Reads the APK with
# unzip and the SDK's dexdump; nothing is executed. It checks what app/proguard-rules.pro must keep:
#   1. the generated $$serializer classes of key @Serializable DTOs and models exist;
#   2. every enum class still has values() and valueOf(String), which the app calls by name.
# Usage: bash tools/check_release_dex.sh [path/to/release.apk]
# Prints SERIALIZERS AND ENUMS PRESENT (exit 0) or the missing entries (exit 1).
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
apk=${1:-app/build/outputs/apk/release/app-release-unsigned.apk}
[[ -f $apk ]] || { echo "No APK at $apk. Run tools/gw.sh :app:assembleRelease first." >&2; exit 2; }
android_home=${ANDROID_HOME:-/opt/android-sdk}
dexdump=$(ls -d "$android_home"/build-tools/*/dexdump | sort -V | tail -1)
work=$(mktemp -d "${TMPDIR:-/tmp}/nightfleet-dex.XXXXXX")
trap 'rm -rf "$work"' EXIT
unzip -q "$apk" 'classes*.dex' -d "$work"

# Class descriptors of every dex file in the APK, as 'Lpkg/Name;' lines.
for dex in "$work"/classes*.dex; do
    "$dexdump" "$dex" | sed -n "s/^  Class descriptor  : '\(.*\)'$/\1/p"
done > "$work/descriptors.txt"

fail=0
core=Lpt/tiago/nightfleet/core
data=Lpt/tiago/nightfleet/data
required=(
    "$core/content/MissionDto\$\$serializer;"
    "$core/content/CatalogDto\$\$serializer;"
    "$core/content/CatalogManifestDto\$\$serializer;"
    "$core/content/SeaDto\$\$serializer;"
    "$core/content/PatrolConfigDto\$\$serializer;"
    "$core/puzzle/PuzzleDefinition\$\$serializer;"
    "$core/engine/BattleState\$\$serializer;"
    "$core/engine/Action\$Fire\$\$serializer;"
    "$data/Settings\$\$serializer;"
    "$data/Profile\$\$serializer;"
    "$data/SavedMatch\$\$serializer;"
    "$data/LogEntry\$\$serializer;"
    "$data/Formation\$\$serializer;"
    "$data/AnalysisSummary\$\$serializer;"
)
for descriptor in "${required[@]}"; do
    if ! grep -qxF "$descriptor" "$work/descriptors.txt"; then
        echo "MISSING serializer: $descriptor"
        fail=1
    fi
done
echo "serializer classes in dex: $(grep -c '\$\$serializer;$' "$work/descriptors.txt")"

# Enum classes: superclass java/lang/Enum. Each must keep values() and valueOf(String).
"$dexdump" -f "$work"/classes*.dex > "$work/headers.txt"
awk '/Class descriptor/ { match($0, /L[^ ]*;/); d = substr($0, RSTART, RLENGTH) }
     /Superclass.*java\/lang\/Enum/ { print d }' "$work/headers.txt" | sort -u > "$work/enums.txt"
for dex in "$work"/classes*.dex; do
    "$dexdump" -d "$dex"
done | awk '/Class descriptor/ { match($0, /L[^ ]*;/); cur = substr($0, RSTART, RLENGTH) }
            /name +: .valueOf./ { vo[cur] = 1 }
            /name +: .values./ { vs[cur] = 1 }
            END { for (c in vo) if (c in vs) print c }' | sort -u > "$work/enum_methods.txt"
enum_total=$(wc -l < "$work/enums.txt")
enum_ok=$(grep -c -x -F -f "$work/enums.txt" "$work/enum_methods.txt" || true)
echo "enum classes: $enum_total, with values() and valueOf(String): $enum_ok"
if (( enum_ok != enum_total )); then
    grep -v -x -F -f "$work/enum_methods.txt" "$work/enums.txt" | sed 's/^/MISSING enum methods: /'
    fail=1
fi

if (( fail == 0 )); then
    echo 'SERIALIZERS AND ENUMS PRESENT'
else
    echo 'SERIALIZERS OR ENUMS MISSING' >&2
fi
exit "$fail"
