#!/usr/bin/env bash
# Builds a macOS installer (.dmg by default; pass "pkg" as $1 for a .pkg) with
# jpackage. Bundles its own Java runtime; the .dmg drops ai-assist.app into
# /Applications. Run on macOS with a JDK 21+ on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE="${1:-dmg}"
APP_NAME="ai-assist"
VENDOR="ai-assist"
DESC="Offline meeting-notes assistant"

echo "==> Building the hardened application jar"
mvn -B -Pharden -DskipTests clean package

JAR="$(ls target/${APP_NAME}-*.jar | grep -v original | head -1)"
VERSION="$(basename "$JAR" | sed -E "s/^${APP_NAME}-(.*)\.jar/\1/; s/-SNAPSHOT//")"
echo "==> Packaging ${APP_NAME} ${VERSION} as ${TYPE}"

INPUT="$(mktemp -d)"; cp "$JAR" "$INPUT/"
# Drop the other platforms' native libraries to shrink the installer.
bash packaging/slim-jar.sh "$INPUT/$(basename "$JAR")" mac || echo "(jar slimming skipped)"
OUT="dist"; mkdir -p "$OUT"

# Build an .icns from the PNG using the built-in macOS tools; fall back to no icon.
ICON_ARG=()
if [ -f packaging/icons/ai-assist.png ] && command -v iconutil >/dev/null 2>&1; then
  ICONSET="$(mktemp -d)/ai-assist.iconset"; mkdir -p "$ICONSET"
  for sz in 16 32 64 128 256 512; do
    sips -z $sz $sz packaging/icons/ai-assist.png --out "$ICONSET/icon_${sz}x${sz}.png" >/dev/null
    sips -z $((sz*2)) $((sz*2)) packaging/icons/ai-assist.png --out "$ICONSET/icon_${sz}x${sz}@2x.png" >/dev/null
  done
  ICNS="$(mktemp -d)/ai-assist.icns"
  iconutil -c icns "$ICONSET" -o "$ICNS" && ICON_ARG=(--icon "$ICNS")
fi

jpackage \
  --type "$TYPE" \
  --name "$APP_NAME" \
  --app-version "$VERSION" \
  --vendor "$VENDOR" \
  --description "$DESC" \
  --input "$INPUT" \
  --main-jar "$(basename "$JAR")" \
  --main-class org.springframework.boot.loader.launch.JarLauncher \
  --java-options "-Djava.awt.headless=false" \
  "${ICON_ARG[@]}" \
  --mac-package-name "$APP_NAME" \
  --dest "$OUT"

rm -rf "$INPUT"
echo "==> Done. Installer in ./$OUT:"
ls -1 "$OUT"
echo "Note: to avoid the Gatekeeper 'unidentified developer' prompt, sign & notarize"
echo "with an Apple Developer ID (add --mac-sign and the signing options)."
