#!/bin/sh
# Step 1: the signed App Bundle Play takes, checked the way Play checks it.
#
#   sh deploy/google-play/1-app-bundle/build.sh
#
# Builds :android:bundleRelease, signed with the PRIVATE key (password from the
# Keychain item android-keystore-hubidubitask-upload, or HUBIDUBITASK_KEYSTORE and
# HUBIDUBITASK_KEYSTORE_PASSWORD), then checks the signature, the version, the
# permissions and the 16 KB alignment of every native library, and copies it
# to out/hubidubitask-<version>-<code>.aab, the file to upload.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
apps="$repo/src/apps"
version=$(sed -n 's/^hubidubitask.version=//p' "$apps/gradle.properties")
code=$(sed -n 's/^hubidubitask.versionCode=//p' "$apps/gradle.properties")

echo "==> Building $version (versionCode $code)"
(cd "$apps" && ./gradlew -q :android:bundleRelease)
aab="$apps/android/build/outputs/bundle/release/android-release.aab"

echo "==> Signature"
jarsigner -verify "$aab" >/dev/null && echo "    verified"
keytool -printcert -jarfile "$aab" | sed -n 's/^Owner: /    signed by /p' | head -1

echo "==> Permissions (merged manifest)"
manifest=$(find "$apps/android/build/intermediates/merged_manifests/release" -name AndroidManifest.xml | head -1)
grep -o 'uses-permission[^>]*android:name="[^"]*"' "$manifest" | sed 's/.*name="//; s/"$//; s/^/    /' | sort -u
if grep -q "AD_ID" "$manifest"; then echo "    !! the advertising ID permission is in; Play's ads declaration must say so" >&2; fi

echo "==> 16 KB pages (64-bit native libraries)"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
unzip -q -o "$aab" 'base/lib/arm64-v8a/*' 'base/lib/x86_64/*' -d "$tmp" 2>/dev/null || true
python3 - "$tmp" <<'PY'
import glob, struct, sys
bad = 0
for f in sorted(glob.glob(sys.argv[1] + "/base/lib/*/*.so")):
    d = open(f, "rb").read()
    phoff, = struct.unpack_from("<Q", d, 0x20)
    size, n = struct.unpack_from("<HH", d, 0x36)
    aligns = [struct.unpack_from("<Q", d, phoff + i * size + 0x30)[0] for i in range(n)
              if struct.unpack_from("<I", d, phoff + i * size)[0] == 1]
    ok = min(aligns) >= 16384
    bad += not ok
    print("    %-42s %s" % (f.split("/lib/")[1], "ok" if ok else "NOT 16 KB-aligned"))
sys.exit(1 if bad else 0)
PY

mkdir -p "$here/out"
cp "$aab" "$here/out/hubidubitask-$version-$code.aab"
echo "==> $here/out/hubidubitask-$version-$code.aab is ready to upload"
