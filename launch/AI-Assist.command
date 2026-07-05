#!/bin/bash
# macOS double-click launcher for ai-assist.
# Put this file next to the ai-assist jar (or in the project root) and run:
#   chmod +x AI-Assist.command
# Then double-click it in Finder. The app opens http://localhost:8080,
# starts listening to the selected audio device, and drafts notes automatically.

cd "$(dirname "$0")"

JAR=$(ls ai-assist-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
  JAR=$(ls target/ai-assist-*.jar 2>/dev/null | head -1)
fi
if [ -z "$JAR" ]; then
  echo "ai-assist jar not found. Build it first with: mvn package -Pfetch-model"
  read -p "Press Enter to close..."
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java 21+ is required. Install it from https://adoptium.net"
  read -p "Press Enter to close..."
  exit 1
fi

echo "Starting ai-assist... (close this window to stop)"
java -jar "$JAR"
