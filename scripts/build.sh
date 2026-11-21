#!/bin/bash
# Offline build with the SDK tools only (no Gradle, no downloads).
# Produces an unsigned, aligned APK at build/manual/app-unsigned.apk.
# Usage: scripts/build.sh [debug]   -> with "debug", also signs with ~/.android/debug.keystore
set -euo pipefail

source "$(dirname "$0")/env.sh"
gradle_file="$root/app/build.gradle.kts"
src="$root/app/src/main"
out="$root/build/manual"

# Single source of truth for ids and versions: app/build.gradle.kts
val() { grep -m1 -E "^\s*$1\s*=" "$gradle_file" | sed -E 's/.*=\s*"?([^"]*)"?\s*$/\1/'; }
app_id=$(val applicationId)
min_sdk=$(val minSdk)
target_sdk=$(val targetSdk)
version_code=$(val versionCode)
version_name=$(val versionName)

rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex"

# The manifest has no package attribute (Gradle namespace); add it for aapt2.
sed -E "0,/<manifest /s//<manifest package=\"$app_id\" /" "$src/AndroidManifest.xml" > "$out/AndroidManifest.xml"

"$bt/aapt2" compile --dir "$src/res" -o "$out/res.zip"
# Debug builds are debuggable (adb run-as can read the app's files); release builds are not.
debug_flag=()
[ "${1:-}" = "debug" ] && debug_flag=(--debug-mode)

"$bt/aapt2" link -I "$jar" "${debug_flag[@]}" \
    --manifest "$out/AndroidManifest.xml" \
    --min-sdk-version "$min_sdk" --target-sdk-version "$target_sdk" \
    --version-code "$version_code" --version-name "$version_name" \
    --java "$out/gen" \
    -o "$out/base.apk" "$out/res.zip"

mapfile -t sources < <(find "$src/java" "$out/gen" -name '*.java')
"$javac" --release 17 -nowarn -Xlint:-options -classpath "$jar" -d "$out/classes" "${sources[@]}"

mapfile -t classes < <(find "$out/classes" -name '*.class')
"$bt/d8" --release --min-api "$min_sdk" --lib "$jar" --output "$out/dex" "${classes[@]}"

cp "$out/base.apk" "$out/app-unaligned.apk"
(cd "$out/dex" && zip -q -j "$out/app-unaligned.apk" classes.dex)
"$bt/zipalign" -f -p 4 "$out/app-unaligned.apk" "$out/app-unsigned.apk"
echo "built $out/app-unsigned.apk ($app_id $version_name/$version_code)"

if [ "${1:-}" = "debug" ]; then
    "$bt/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
        --ks-key-alias androiddebugkey --out "$out/app-debug.apk" "$out/app-unsigned.apk"
    rm -f "$out/app-debug.apk.idsig"
    echo "debug APK: $out/app-debug.apk"
fi
