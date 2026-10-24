#!/usr/bin/env bash
# Builds the unsigned release APK, then fails unless VIBRATE is the only permission
# (the app's own DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is reported, not failed) and cleartext traffic is off.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
mkdir -p logs
partial_prop=()
if [[ ${NIGHTFLEET_PARTIAL_CONTENT:-0} == 1 ]]; then partial_prop=(-PnightFleet.partialContent=true); fi
tools/gw.sh "${partial_prop[@]}" :app:assembleRelease >logs/assembleRelease.log 2>&1
apk=app/build/outputs/apk/release/app-release-unsigned.apk
if [[ ! -f $apk ]]; then
    apk=$(find app/build/outputs/apk/release -name '*.apk' | head -n1)
fi
if [[ -z ${apk:-} || ! -f $apk ]]; then
    echo "check_permissions: no release APK found" >&2
    exit 1
fi
sdk=${ANDROID_HOME:-/opt/android-sdk}
aapt2=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n1)
if [[ -z $aapt2 ]]; then
    echo "check_permissions: aapt2 not found under $sdk/build-tools" >&2
    exit 1
fi
perms=$("$aapt2" dump permissions "$apk")
printf '%s\n' "$perms" | tee logs/permissions.txt
status=0
names=$(printf '%s\n' "$perms" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort -u)
while IFS= read -r name; do
    [[ -z $name ]] && continue
    case $name in
        android.permission.VIBRATE) ;;
        *DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) echo "check_permissions: note: app's own permission $name (allowed)" ;;
        *) echo "check_permissions: FORBIDDEN permission $name" >&2; status=1 ;;
    esac
done <<<"$names"
xmltree=$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")
# A boolean true is 0xffffffff in compiled resources; xmltree prints it as "0xffffffff" or "=true".
if grep 'usesCleartextTraffic' <<<"$xmltree" | grep -qE '0xffffffff|=true'; then
    echo "check_permissions: FORBIDDEN usesCleartextTraffic=true" >&2
    status=1
fi
if (( status == 0 )); then
    echo "check_permissions: PASS (only VIBRATE; no cleartext traffic)"
fi
exit "$status"
