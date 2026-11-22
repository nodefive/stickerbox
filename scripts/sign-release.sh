#!/bin/bash
# Build the release APK and sign it with your own key.
#   STICKERBOX_KEYSTORE   path to your keystore (.jks or .p12)
#   STICKERBOX_KEY_ALIAS  key alias inside it
# The password is asked once, checked with keytool, then handed to apksigner through the
# environment of those two commands only; nothing is stored.
set -euo pipefail

source "$(dirname "$0")/env.sh"

ks="${STICKERBOX_KEYSTORE:-}"
alias="${STICKERBOX_KEY_ALIAS:-}"
if [ -z "$ks" ] || [ -z "$alias" ]; then
    echo "set STICKERBOX_KEYSTORE and STICKERBOX_KEY_ALIAS (see README, Signing)" >&2
    exit 1
fi
[ -f "$ks" ] || { echo "keystore not found: $ks" >&2; exit 1; }

keytool="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
IFS= read -rsp "Keystore password: " pass </dev/tty
echo
# Check the password before building, so a typo shows up at once and with a clear message.
if ! SB_KS_PASS="$pass" "$keytool" -list -keystore "$ks" -alias "$alias" -storepass:env SB_KS_PASS >/dev/null 2>&1; then
    echo "keytool does not accept that password for alias '$alias' in $ks (${#pass} characters typed)" >&2
    exit 1
fi

"$root/scripts/build.sh"

version=$(grep -m1 -E '^\s*versionName\s*=' "$root/app/build.gradle.kts" | sed -E 's/.*"(.*)".*/\1/')
mkdir -p "$root/dist"
out="$root/dist/stickerbox-$version.apk"

SB_KS_PASS="$pass" "$bt/apksigner" sign --ks "$ks" --ks-key-alias "$alias" \
    --ks-pass env:SB_KS_PASS --key-pass env:SB_KS_PASS \
    --out "$out" "$root/build/manual/app-unsigned.apk"
unset pass
rm -f "$out.idsig"
"$bt/apksigner" verify --print-certs "$out" | grep -m2 -E 'DN|SHA-256'
sha256sum "$out"
