#!/usr/bin/env bash
# Reproducibility check: clones the committed HEAD twice into two fresh directories,
# builds the unsigned release APK in each with its own tools/gw.sh, and compares the results.
# Prints REPRODUCIBLE (exit 0) or NOT REPRODUCIBLE with the differing files (exit 1).
# Uncommitted changes are NOT part of the check: only the commit is cloned.
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mkdir -p "$repo/logs"
log="$repo/logs/repro_check.log"
exec > >(tee "$log") 2>&1

base=${TMPDIR:-/tmp}
rev=$(git -C "$repo" rev-parse HEAD)
dir_a=$(mktemp -d "$base/nightfleet-repro-a.XXXXXX")
dir_b=$(mktemp -d "$base/nightfleet-repro-b.XXXXXX")
printf 'Commit under test: %s\n' "$rev"

build_clone() {
    local dir=$1
    rm -rf "$dir"
    git clone --no-local --quiet "$repo" "$dir"
    git -C "$dir" checkout --quiet --detach "$rev"
    # Signing credentials must not leak into the comparison: the release stays unsigned, also when
    # ~/.gradle/gradle.properties holds the release key.
    # --no-build-cache forces both builds to run, so the cache cannot make them look identical.
    (cd "$dir" && env -u SIGNING_KEYSTORE_FILE -u SIGNING_PASSWORD -u SIGNING_KEY_ALIAS \
        tools/gw.sh --no-build-cache -PnightFleet.release.unsigned=true :app:assembleRelease)
    local apk=$dir/app/build/outputs/apk/release/app-release-unsigned.apk
    [[ -f $apk ]] || { echo "No unsigned release APK in $dir" >&2; return 1; }
}

build_clone "$dir_a"
build_clone "$dir_b"
apk_a=$dir_a/app/build/outputs/apk/release/app-release-unsigned.apk
apk_b=$dir_b/app/build/outputs/apk/release/app-release-unsigned.apk
sha_a=$(sha256sum "$apk_a" | cut -d' ' -f1)
sha_b=$(sha256sum "$apk_b" | cut -d' ' -f1)
printf 'SHA-256 A (%s): %s\nSHA-256 B (%s): %s\n' "$dir_a" "$sha_a" "$dir_b" "$sha_b"

if [[ $sha_a == "$sha_b" ]]; then
    echo 'REPRODUCIBLE'
    rm -rf "$dir_a" "$dir_b"
    exit 0
fi

echo 'NOT REPRODUCIBLE: the two APKs differ. Comparing their contents.'
unzip_a=$dir_a.unzipped
unzip_b=$dir_b.unzipped
rm -rf "$unzip_a" "$unzip_b"
unzip -q "$apk_a" -d "$unzip_a"
unzip -q "$apk_b" -d "$unzip_b"
# Signature files are not part of the reproducible content (only relevant if a signed APK was compared).
for d in "$unzip_a" "$unzip_b"; do
    rm -f "$d"/META-INF/*.SF "$d"/META-INF/*.RSA "$d"/META-INF/*.DSA "$d"/META-INF/*.EC
done
list_a=$(cd "$unzip_a" && find . -type f | LC_ALL=C sort)
list_b=$(cd "$unzip_b" && find . -type f | LC_ALL=C sort)
if [[ $list_a != "$list_b" ]]; then
    echo 'File lists differ:'
    diff <(printf '%s\n' "$list_a") <(printf '%s\n' "$list_b") || true
fi
differing=0
while IFS= read -r file; do
    [[ -z $file ]] && continue
    ha=$( [[ -f "$unzip_a/$file" ]] && sha256sum "$unzip_a/$file" | cut -d' ' -f1 || echo missing)
    hb=$( [[ -f "$unzip_b/$file" ]] && sha256sum "$unzip_b/$file" | cut -d' ' -f1 || echo missing)
    if [[ $ha != "$hb" ]]; then
        echo "DIFFERENT: $file"
        differing=$((differing + 1))
    fi
done < <(printf '%s\n%s\n' "$list_a" "$list_b" | LC_ALL=C sort -u)
echo "Differing files: $differing. Kept for inspection: $dir_a and $dir_b (unzipped in $unzip_a and $unzip_b)"
echo 'NOT REPRODUCIBLE'
exit 1
