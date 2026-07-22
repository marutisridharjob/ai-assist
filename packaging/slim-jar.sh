#!/usr/bin/env bash
# Removes native libraries the target OS will never use from the fat jar, to
# shrink the installer. The app bundles Vosk / llama.cpp / whisper native
# binaries for every OS and CPU; a macOS installer only needs the macOS ones,
# a Windows installer only the Windows ones, etc. Also drops clearly obsolete
# targets (Android, 32-bit Windows) everywhere.
#
# Usage:  slim-jar.sh <path-to-fat-jar> <mac|windows|linux>
#
# Spring Boot requires nested jars to be STORED (uncompressed) inside the fat
# jar, so the slimmed nested jars are written back with `zip -0`.
set -euo pipefail

JAR_IN="${1:?usage: slim-jar.sh <fat-jar> <mac|windows|linux>}"
OS="${2:?usage: slim-jar.sh <fat-jar> <mac|windows|linux>}"
JAR="$(cd "$(dirname "$JAR_IN")" && pwd)/$(basename "$JAR_IN")"

BEFORE="$(du -m "$JAR" | cut -f1)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# Pull out just the native-carrying nested jars.
unzip -qo "$JAR" 'BOOT-INF/lib/vosk-*.jar' 'BOOT-INF/lib/llama-*.jar' 'BOOT-INF/lib/whisper-jni-*.jar'
VOSK="$(ls BOOT-INF/lib/vosk-*.jar)"
LLAMA="$(ls BOOT-INF/lib/llama-*.jar)"
WHISPER="$(ls BOOT-INF/lib/whisper-jni-*.jar)"

del() { local jar="$1"; shift; zip -q -d "$jar" "$@" >/dev/null 2>&1 || true; }

# Obsolete / non-desktop targets are never needed on any build.
del "$LLAMA" 'de/kherud/llama/Linux-Android/*' 'de/kherud/llama/Windows/x86/*'

case "$OS" in
  mac)
    del "$VOSK"    'linux-x86-64/*' 'win32-x86-64/*'
    del "$LLAMA"   'de/kherud/llama/Linux/*' 'de/kherud/llama/Windows/*'
    del "$WHISPER" 'debian-*' 'win-*'
    ;;
  windows)
    del "$VOSK"    'darwin/*' 'linux-x86-64/*'
    del "$LLAMA"   'de/kherud/llama/Mac/*' 'de/kherud/llama/Linux/*'
    del "$WHISPER" 'debian-*' 'macos-*'
    ;;
  linux)
    del "$VOSK"    'darwin/*' 'win32-x86-64/*'
    del "$LLAMA"   'de/kherud/llama/Mac/*' 'de/kherud/llama/Windows/*'
    del "$WHISPER" 'macos-*' 'win-*'
    ;;
  *)
    echo "Unknown OS '$OS' (expected mac|windows|linux)"; exit 1 ;;
esac

# Write the slimmed nested jars back, STORED (Spring Boot cannot read a
# compressed nested jar).
zip -q -0 "$JAR" "$VOSK" "$LLAMA" "$WHISPER"

AFTER="$(du -m "$JAR" | cut -f1)"
echo "Slimmed for ${OS}: ${BEFORE} MB -> ${AFTER} MB  ($JAR)"
