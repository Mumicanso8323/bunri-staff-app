#!/usr/bin/env bash
# hub で署名済み release APK を作る。管理者パスワード入りなので、APK は hub の外に出さない。
#   android/scripts/build_release.sh            -> /data/ashwell/_bunri_staff_android/bunrisai-staff-<版>-android.apk
# 値(パスワード・keystore のパスワード)は環境変数でだけ Gradle に渡し、表示もファイルへの書き出しもしない。
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE="${BUNRI_ENV_FILE:-/data/ashwell/hub-apps/bunri.env}"
KS_DIR="${BUNRI_KEYSTORE_DIR:-$HOME/.config/bunri-staff-android}"
OUT_DIR="${BUNRI_OUT_DIR:-/data/ashwell/_bunri_staff_android}"
export ANDROID_HOME="${ANDROID_HOME:-/data/ashwell/android-sdk}"

# ADMIN_PASSWORD を 16 進にする(値は変数に入れるだけで出力しない)
BUNRI_ADMIN_PASSWORD_HEX="$(python3 - "$ENV_FILE" <<'PY'
import sys
value = None
for line in open(sys.argv[1], encoding="utf-8"):
    line = line.rstrip("\n")
    if line.startswith("ADMIN_PASSWORD="):
        value = line.split("=", 1)[1]
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
if not value:
    sys.exit("ADMIN_PASSWORD が見つかりません")
print(value.encode("utf-8").hex())
PY
)"
export BUNRI_ADMIN_PASSWORD_HEX
export BUNRI_KEYSTORE_FILE="$KS_DIR/release.jks"
BUNRI_KEYSTORE_PASSWORD="$(cat "$KS_DIR/keystore.pass")"
export BUNRI_KEYSTORE_PASSWORD
export BUNRI_KEY_ALIAS="bunri-staff"

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
mkdir -p "$OUT_DIR"
chmod 700 "$OUT_DIR"

./gradlew --console=plain --no-daemon :app:testDebugUnitTest :app:assembleRelease >/tmp/bunri_android_build.log 2>&1 || {
  echo "ビルド失敗。ログ(値は含まない): /tmp/bunri_android_build.log" >&2
  tail -30 /tmp/bunri_android_build.log >&2
  exit 1
}

APK="app/build/outputs/apk/release/app-release.apk"
DEST="$OUT_DIR/bunrisai-staff-v${VERSION}-android.apk"
cp "$APK" "$DEST"
chmod 600 "$DEST"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$DEST" | grep -E "Verifies|Number of signers|v2 scheme|v3 scheme|certificate SHA-256" || true
ls -l "$DEST"
