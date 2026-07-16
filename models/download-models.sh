#!/usr/bin/env bash
#
# Downloads the Whisper speech models ai-assist uses for the accurate
# complete-conversation transcript. Run it once; the models land in this
# "models/" folder, which the app searches automatically (it sits next to
# the jar). No Hugging Face account needed — these come from a GitHub mirror.
#
#   ./download-models.sh            # fast + accurate (base + small)
#   ./download-models.sh all        # also grab medium (1.5 GB, most accurate)
#   ./download-models.sh base       # just one model by name
#
# The models are GGML files (whisper.cpp, MIT). They are multilingual and
# transcribe English perfectly — the app picks up any ggml-*.bin it finds.

set -euo pipefail

BASE_URL="https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases/latest/download"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Model set: fast (base) + accurate (small). Add medium with "all".
models=("ggml-base.bin" "ggml-small.bin")
case "${1:-}" in
  all)             models=("ggml-base.bin" "ggml-small.bin" "ggml-medium.bin") ;;
  base|small|medium) models=("ggml-${1}.bin") ;;
  "")              : ;;                       # default set
  *)               models=("ggml-${1}.bin") ;;
esac

for m in "${models[@]}"; do
  out="$DIR/$m"
  if [ -f "$out" ]; then
    echo "✓ $m already present, skipping"
    continue
  fi
  echo "↓ downloading $m ..."
  curl -L --fail --progress-bar -o "$out.partial" "$BASE_URL/$m"
  mv "$out.partial" "$out"
  echo "✓ saved $out"
done

echo
echo "Done. Models are in: $DIR"
echo "Keep this 'models' folder next to ai-assist-<version>.jar and the app"
echo "will use the model automatically on Stop."
