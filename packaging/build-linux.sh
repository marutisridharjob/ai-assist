#!/usr/bin/env bash
# Builds a Linux installer (.deb by default; pass "rpm" as $1 for .rpm) with
# jpackage. Bundles its own Java runtime, installs into /opt, and adds a
# desktop/menu shortcut. Run on Linux with a JDK 21+ on PATH.
set -euo pipefail
cd "$(dirname "$0")/.."

TYPE="${1:-deb}"
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
bash packaging/slim-jar.sh "$INPUT/$(basename "$JAR")" linux || echo "(jar slimming skipped)"
OUT="dist"; mkdir -p "$OUT"

ICON_ARG=()
[ -f packaging/icons/ai-assist.png ] && ICON_ARG=(--icon packaging/icons/ai-assist.png)

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
  --linux-shortcut \
  --linux-menu-group "Office" \
  --dest "$OUT"

rm -rf "$INPUT"
echo "==> Done. Installer(s) in ./$OUT:"
ls -1 "$OUT"
