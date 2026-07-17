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

---

# Optional: a local LLM for better summaries and editing

The **summary**, the **Editor** tab, and the **Compose** tab can be powered by
a small **local LLM** that runs *inside* the app (llama.cpp, MIT — no Ollama,
no server, nothing leaves the machine). It is entirely optional: without a
model the app uses its offline rule-based drafter; drop one in and the summary,
Editor and Compose go through the model, and the free-form **Instructions**
fields start working.

Drop **one** GGUF *instruct* model here (or next to the jar). The app uses the
first `*.gguf` it finds:

| Model (GGUF, Q4) | ~Size | Notes |
|------------------|-------|-------|
| `qwen2.5-0.5b-instruct-q4_k_m.gguf` | ~400 MB | Tiny, fastest, lowest quality |
| `llama-3.2-1b-instruct-q4_k_m.gguf` | ~800 MB | Tiny, better writing — good default |
| `qwen2.5-1.5b-instruct-q4_k_m.gguf` | ~1.1 GB | Small, noticeably better |
| `qwen2.5-3b-instruct-q4_k_m.gguf` | ~2 GB | Small+, best of the practical set on a laptop CPU |

Notes:
- **Any** GGUF instruct model works — pick one your machine can run. Bigger =
  better writing but slower and more RAM (a Q4 model needs roughly its file
  size in free RAM).
- It runs **CPU-only**, so the first reply after a meeting takes a few seconds
  on a tiny model, longer on bigger ones.
- These GGUF files live on model hubs (e.g. Hugging Face's `*-GGUF` repos such
  as `Qwen/Qwen2.5-1.5B-Instruct-GGUF` or `bartowski/*`). If you can only reach
  GitHub, host the file as a Release asset on your own repo (up to 2 GB) and
  download it from there.
- The native LLM libraries are already bundled in the jar; you only supply the
  model file.

## Is the model actually being used?

After you press **Apply** on the Editor or Compose tab, the status line ends
with a **`[LLM: …]`** note that tells you exactly what happened:

- `[LLM: used <model> (1234 ms)]` — the model ran. ✅
- `[LLM: no .gguf model found (looked in: …)]` — the file isn't in a folder the
  app searches. The paths it looked in are listed — put the `.gguf` in one of
  them (the simplest is right next to the jar, or a `models/` folder next to
  the jar). Note: "next to the jar" means the folder containing the
  `ai-assist-<version>.jar` you actually launched, not the source checkout.
- `[LLM: <model> failed to load/run: …]` — the file was found but couldn't be
  loaded (a corrupt/partial download, or a GGUF too new for the bundled
  llama.cpp). Re-download it, or try a different quant.

If it says "no model found", check the listed paths first — the most common
cause is the model sitting next to the source tree while the jar runs from
somewhere else.
