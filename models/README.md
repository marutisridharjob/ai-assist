# Whisper models

ai-assist uses a **Whisper** (`ggml-*.bin`) model for the accurate
complete-conversation transcript produced when you press **Stop**. The model
is not committed to this repository — the files are 142 MB – 1.5 GB, well over
GitHub's 100 MB per-file limit, so they can't live in git. Download them here
instead; **this `models/` folder is one of the places the app looks**, as long
as it sits next to `ai-assist-<version>.jar`.

> No Hugging Face account needed — the scripts pull from a public GitHub
> release mirror, so a plain GitHub connection is enough.

## Quick start

macOS / Linux:

```bash
cd models
./download-models.sh          # fast + accurate: ggml-base.bin + ggml-small.bin
./download-models.sh all      # also fetch ggml-medium.bin (1.5 GB, most accurate)
./download-models.sh base     # or one model by name: base | small | medium
```

Windows:

```bat
cd models
download-models.bat           :: fast + accurate
download-models.bat all       :: also fetch medium
```

Then keep the `models/` folder next to the jar. On **Stop**, the app finds the
model automatically. (If no model is present, it falls back to the live Vosk
captions.)

## Which model?

All are English-capable GGML models (whisper.cpp, MIT). They are multilingual
builds — English is a subset, so they transcribe English meetings perfectly.
The app uses whichever `ggml-*.bin` it finds first.

| Model | Size | Speed vs. accuracy |
|-------|------|--------------------|
| `ggml-base.bin`   | ~142 MB | **Fast** — recommended default, good accuracy |
| `ggml-small.bin`  | ~466 MB | **Accurate** — noticeably better, still practical on CPU |
| `ggml-medium.bin` | ~1.5 GB | **Most accurate** — slower on CPU, for the best transcript |

Pick one to keep in the folder; if several are present the app uses the first
it finds (alphabetical), so keep only the one you want active.

## Sources & trust

- **Official (needs Hugging Face):**
  [huggingface.co/ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp/tree/main)
  — the canonical whisper.cpp models.
- **GitHub mirror used by the scripts:**
  [NoMercy-Entertainment/nomercy-whisper-models](https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases)
  — a community mirror that publishes the same GGML files as release assets
  (with a signed `manifest.json`). It is third-party; if you need certainty,
  verify a downloaded file's SHA-256 against that release's `manifest.json`,
  or use the official Hugging Face source.

## Hosting the model inside GitHub yourself

If you want the model served entirely from *this* project (no external mirror),
attach it as a **GitHub Release asset** on your own repo — release assets allow
up to 2 GB per file, so no Git LFS and no 100 MB limit. Committing the binary
into the repo tree will not work; the file is too large and the push is
rejected.
