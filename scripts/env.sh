#!/bin/bash
# Shared tool paths for build.sh and sign-release.sh. Source it, do not run it.
#   SDK:          ANDROID_HOME, ANDROID_SDK_ROOT, or sdk.dir in local.properties
#   build-tools:  BUILD_TOOLS (version), default: newest installed
#   platform:     PLATFORM (directory name), default: android-36.1
#   javac:        $JAVA_HOME/bin/javac, or javac on PATH

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] && [ -f "$root/local.properties" ]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | tail -1)
fi
[ -n "$sdk" ] && [ -d "$sdk" ] || {
    echo "Android SDK not found: set ANDROID_HOME or sdk.dir in local.properties" >&2
    exit 1
}

bt_version="${BUILD_TOOLS:-$(ls "$sdk/build-tools" | sort -V | tail -1)}"
bt="$sdk/build-tools/$bt_version"
jar="$sdk/platforms/${PLATFORM:-android-36.1}/android.jar"
javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"

[ -x "$bt/aapt2" ] || { echo "build-tools not found: $bt" >&2; exit 1; }
[ -f "$jar" ] || { echo "platform not found: $jar (install it or set PLATFORM)" >&2; exit 1; }
command -v "$javac" >/dev/null || { echo "javac not found: set JAVA_HOME" >&2; exit 1; }
