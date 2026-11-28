#!/usr/bin/env bash
# Re-signs the release APK with the Clau娘 key, rotating from the old public key.
#
#   sign-release.sh <apk>          (run from the android/ directory; needs $SIGNING_KEY)
#
# Android 9+ sees the private Clau娘 key plus a "rotation" record signed by the old key, so phones
# with 1.2.x installed update in place and keep their data; after that, only the new key can update.
# Android 8 (API 26-27) has no rotation support and keeps seeing the old key.
#
# The private key lives in signing/clauniang-release.enc (AES-256, PBKDF2), unlocked by the
# CLAU_SIGNING_KEY repository secret. Without that secret nobody can produce an update that
# installs over a rotated Clau娘.
set -euo pipefail

apk="$1"
expected="9e8b634eb818ebf8d08399bcc0e0f6410af69a8a0dcddc5bc5d01a9421711c30"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
bt="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in signing/clauniang-release.enc -pass env:SIGNING_KEY \
    | tar -xz -C "$work" 2>/dev/null; then
  echo "::error::Could not unlock the signing key: the CLAU_SIGNING_KEY secret is not the right value."
  exit 1
fi

"$bt/apksigner" sign \
  --ks app/pet-debug.keystore --ks-pass pass:android --ks-key-alias androiddebugkey \
  --next-signer --ks "$work/release.jks" --ks-pass "file:$work/password.txt" --ks-key-alias clauniang \
  --lineage signing/rotation.lineage --rotation-min-sdk-version 28 \
  --out "$work/signed.apk" "$apk"

# Android 9+ must see the new key; otherwise stop rather than publish something unexpected.
"$bt/apksigner" verify --min-sdk-version 28 --print-certs "$work/signed.apk" > "$work/certs.txt"
if ! grep -qi "SHA-256 digest: $expected" "$work/certs.txt"; then
  cat "$work/certs.txt"
  echo "::error::The signed APK does not carry the expected Clau娘 certificate."
  exit 1
fi
mv "$work/signed.apk" "$apk"
echo "Signed with the Clau娘 key (rotated from the old key)."
