# ai-assist — Technical Documentation

**Version 0.1 · Architecture & Design by Maruti**

A self-contained, fully-offline desktop assistant that listens to a meeting on
your computer, transcribes it locally, and drafts complete meeting notes — and
that also rewrites and summarizes any text on demand. It runs identically on
macOS, Windows, and Linux, uses only open-source components, and never sends
your audio or text anywhere.

- **GitHub repository:** <https://github.com/marutisridharjob/ai-assist>
- **License:** open source (see [§10](#10-open-source-licenses))
- **Companion document:** [ARCHITECTURE.md](ARCHITECTURE.md) (diagrams)

---

## Table of contents

1. [Overview](#1-overview)
2. [Design principles](#2-design-principles)
3. [Feature reference (step by step)](#3-feature-reference-step-by-step)
4. [Technical architecture](#4-technical-architecture)
5. [Module & class reference](#5-module--class-reference)
6. [The audio pipeline](#6-the-audio-pipeline)
7. [Models: details, licenses, downloads](#7-models-details-licenses-downloads)
8. [Configuration reference](#8-configuration-reference)
9. [Security](#9-security)
10. [Open-source licenses](#10-open-source-licenses)
11. [Build, packaging & distribution](#11-build-packaging--distribution)
12. [Repository layout](#12-repository-layout)
13. [Troubleshooting](#13-troubleshooting)

---

## 1. Overview

ai-assist turns spoken meetings into written notes without any cloud service.
It captures two audio streams at once — your **microphone** (`you`) and the
computer's **system audio** (`others`, i.e. the remote participants) — shows
live captions, and on **Stop** produces an accurate transcript and an AI-drafted
summary with action items, saved as a timestamped rich-text file.

Three engines, all offline and bundled as native libraries, do the heavy work:

- **Vosk** — real-time speech recognition for the live captions.
- **Whisper** (whisper.cpp) — accurate, complete transcription of the recording
  when you press Stop.
- **llama.cpp** — an in-process Large Language Model that writes the summary and
  performs the Assist-tab rewrites (falling back to deterministic rules when no
  model is installed).

The application is a single Java process. A Java **Swing** window is the primary
interface; a **Spring Boot** context wires everything together and also exposes
an optional, locally-bound REST API.

---

## 2. Design principles

| Principle | How it is realized |
|---|---|
| **100% offline** | No network calls at runtime except the optional feedback email. Models are local files. |
| **100% open source** | Every dependency is OSS; no proprietary libraries or cloud APIs. |
| **Cross-platform, future-proof** | Pure JDK + Swing; OS-specific behavior is isolated and degrades gracefully so future OS versions keep working. |
| **Private by default** | Audio and text never leave the machine; the local API is loopback-only and token-authenticated. |
| **Maintainable** | Small, single-responsibility services; the UI talks to services directly and via the same REST contract. |
| **Responsive** | Slow work (transcription, drafting, saving, file IO) runs on background threads; the UI never blocks. |

---

## 3. Feature reference (step by step)

### 3.1 First run

On first launch the app:

1. creates `~/Documents/minutes-of-meeting` (where notes are saved) and
   `~/Documents/minutes-of-meeting/model-backups` (where unpacked model `.zip`s are
   archived);
2. creates `~/Documents/ai-assist/models` (where you place model files);
3. places two **Desktop shortcuts** — one to the app and one to the
   minutes-of-meeting folder (best-effort, per OS);
4. shows the **model-setup notice** listing which models are still missing, each
   with its exact filename, a download-page link, and where to place it.

### 3.2 Meeting tab

1. Open the **Meeting** tab. The **Title** defaults to *Minutes of meeting*; a
   timestamp is appended to the file name on save.
2. Pick a speech model from the **Model:** dropdown (remembered across runs).
3. Press **Start**, or leave **Auto-start** on and the app starts by itself when
   a meeting app is detected **and** the meeting is actually playing audio.
4. Live captions appear: the other participants' lines show the timestamp on its
   own line above the text; **your own speech is shown in blue**.
5. **Pause** suspends listening; **Start** resumes.
6. **Apply** produces a live summary of the meeting so far (no save).
7. **Stop** → **Save** writes the notes; **No** ends without saving; **Cancel**
   keeps the meeting running. A blinking *Saving…* indicator shows while the
   file is written, then an **Open saved notes** link appears.
8. Notes land in `~/Documents/minutes-of-meeting` as `Minutes-<12-hour timestamp>.rtf`.

### 3.3 Auto-start

Opt-in and **on by default**. While idle, the app watches for a meeting
application (Microsoft Teams, Webex, Zoom, Slack). When one is detected, a
lightweight **system-audio monitor** listens for real meeting audio (the other
participants), never the microphone. Once audio is heard, a **cancelable
countdown** ("Zoom detected — starting in 10s") appears and then starts capture.
Works on every OS via the built-in tap (macOS/Windows) or a loopback / PulseAudio
monitor device (Linux); if no system-audio source exists it falls back to app
detection alone.

### 3.4 Assist tab

1. Type or paste text, or **Load** a `.txt`, `.doc`, or `.docx` file (other file
   types are rejected with a message box; `.docx`/`.doc` text is extracted with
   the JDK only — no document library).
2. Tick options — **Grammar**, **Compact**, **Detailed**, **Professional**,
   **Bullet points**, **Summary**, **Email**, and thirteen communication styles.
3. Optionally type **Additional instructions**.
4. Press **Apply**; the result appears in the **After modification** box. Press
   **Apply** again any time to regenerate.
5. **Download** saves the result; **Clear** resets everything.

### 3.5 Help tab

- **About** — author and version.
- **Appearance** — the light/dark **Dark mode** toggle (remembered).
- **Help** — a searchable, read-only instructions window.
- **Feedback** — a message (capped at 1000 characters with a live countdown) and
  an *Overall rating to ai-assist* (0–5). **Submit** sends it, then shows a
  read-only copy of what was submitted until the next submission.

---

## 4. Technical architecture

ai-assist is a single Spring Boot application. The **presentation layer** is a
Swing window (`MeetingConsole`). The **service layer** holds the offline
engines' orchestration. A thin **web layer** exposes the same operations over a
loopback-only REST API. See [ARCHITECTURE.md](ARCHITECTURE.md) for diagrams.

```
com.aiassist
├── AiAssistApplication          # Spring Boot entry point (Swing-aware)
├── ui/                          # Swing desktop UI
│   ├── MeetingConsole           # the window: Meeting / Assist / Help tabs
│   └── MeetingAppDetector       # detects Teams/Webex/Zoom/Slack processes
├── audio/                       # capture + speech recognition
│   ├── LiveTranscriptionService # capture threads, recognition queue, monitor
│   ├── AudioDeviceService       # picks mic + loopback devices
│   ├── NativeSystemAudioTap     # OS system-audio helper
│   ├── AudioResampler           # → 16 kHz mono PCM
│   ├── SpeechRecognizer / VoskNative / VoskModelManager
│   ├── WhisperTranscriber       # final transcription on Stop
│   └── MeetingRecorder          # per-source WAV recording
├── draft/                       # notes drafting & rewriting
│   ├── MeetingEndService        # Stop → transcribe → draft → save
│   ├── StyleRewriteService      # summaries & style rewrites (LLM-first)
│   ├── LocalLlmService          # in-process llama.cpp
│   ├── TemplateContentDrafter   # deterministic offline drafter
│   └── DraftFileWriter          # timestamped .rtf output
├── feedback/FeedbackMailSender  # direct SMTP (no mail app)
├── setup/                       # UserPaths, ModelCatalog, DesktopShortcuts
├── security/                    # ApiTokenService, ApiTokenAuthFilter
└── config/                      # typed @ConfigurationProperties + filters
```

**Threading model.** The Swing Event Dispatch Thread owns the UI. Audio capture
runs one thread per source; **recognition runs on a separate thread** fed by a
bounded queue, so a slow model can never stall capture. Note drafting, file IO,
model unpacking, feedback sending, and the activity monitor each run on their own
background threads and marshal results back to the EDT.

---

## 5. Module & class reference

| Class | Responsibility |
|---|---|
| `MeetingConsole` | Builds and drives the window; owns the tabs, buttons, transcript rendering, auto-start countdown, and the model-setup notice. |
| `LiveTranscriptionService` | Opens capture sources, resamples to 16 kHz, records WAVs, feeds a per-source recognition thread, and exposes a level-only **system-audio activity monitor** used by auto-start. |
| `MeetingEndService` | The Stop flow: stop capture, transcribe the recording with Whisper (falling back to live captions), draft the summary, append the verbatim transcript, and save. |
| `StyleRewriteService` | The single entry point for summaries and rewrites; prefers the local LLM and falls back to deterministic rules. Guards against summarizing near-empty input. |
| `LocalLlmService` | Loads a GGUF model with llama.cpp and generates text using the model's own chat template. |
| `WhisperTranscriber` | Runs whisper.cpp on the recorded WAVs for the accurate transcript. |
| `VoskModelManager` | Locates and unpacks Vosk models (Zip-Slip-guarded), backs up the `.zip`, and lists available models. |
| `DraftFileWriter` | Renders the draft as RTF and writes `Minutes-<12-hour timestamp>.rtf`. |
| `FeedbackMailSender` | Sends feedback straight over SMTP (JDK sockets + JNDI MX lookup + STARTTLS), no desktop mail app. |
| `UserPaths` | The user-writable folders (notes, backups, models), identical on every OS. |
| `ModelCatalog` | The required/recommended models, each with filename, download URL, and an on-disk detector. |
| `DesktopShortcuts` | First-run Desktop shortcuts (symlink on macOS/Linux, `.lnk` via WScript on Windows). |
| `ApiTokenService` / `ApiTokenAuthFilter` | Generates and enforces the local API bearer token. |
| `LocalApiSecurityFilter` | Host/Origin checks and security headers for the local API. |

---

## 6. The audio pipeline

1. **Capture.** `AudioDeviceService` opens the microphone and the best system-audio
   source. On macOS/Windows the system audio comes from `NativeSystemAudioTap`
   (Core Audio / WASAPI); on Linux from a PulseAudio/PipeWire monitor device.
2. **Normalize.** Every source is downmixed to mono and resampled to **16 kHz**
   (`AudioResampler`) — the rate the models expect.
3. **Record + recognize.** Each 16 kHz chunk is written to a per-source WAV
   (`MeetingRecorder`) and enqueued for recognition. A dedicated recognition
   thread feeds Vosk; if it falls behind, the **oldest queued audio is dropped**
   so captions stay near-live while the recording stays complete.
4. **Mic gating.** The microphone requires a peak level of **≥ 30%** before its
   phrases count, so room noise while you are muted in the meeting app is not
   drafted; the system audio keeps a low threshold so quiet remote voices are
   kept.
5. **Finalize.** On Stop the recording is transcribed by **Whisper** for the
   accurate transcript; the live Vosk captions are the fallback when no Whisper
   model is present.

---

## 7. Models: details, licenses, downloads

ai-assist ships with **no models** — you download them once and drop them into
`~/Documents/ai-assist/models`. A Vosk `.zip` is unpacked automatically and the
original archived to `model-backups`.

| Model | File | Purpose | Download page | License |
|---|---|---|---|---|
| **Vosk small English** (required) | `vosk-model-small-en-us-0.15.zip` | Live captions | <https://alphacephei.com/vosk/models> | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Whisper base** (recommended) | `ggml-base.bin` | Accurate transcript on Stop | [GitHub mirror](https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases) | [MIT](https://opensource.org/license/mit) |
| **Instruct LLM** (recommended) | e.g. `qwen2.5-1.5b-instruct-q4_k_m.gguf` | Summaries & rewrites | see [`models/README.md`](../models/README.md) | model-dependent (open weights) |

Larger, more accurate Vosk models (`vosk-model-en-us-0.22-lgraph`, 128 MB;
`vosk-model-en-us-0.22`, 1.8 GB) are available from the same page. For **live
captions** prefer a streaming model (`small` or `0.22-lgraph`); the full 1.8 GB
model is accurate but too heavy for real-time on most CPUs — the saved transcript
uses Whisper regardless, so a lighter live model does not reduce notes quality.

Place each file in the models folder; the app detects it automatically. Press
**Recheck** in the setup notice after adding files.

---

## 8. Configuration reference

All settings live in `src/main/resources/application.yml` under `ai-assist.*`.
Sensitive values (SMTP credentials, API token) should be supplied at runtime, not
committed — see [§9](#9-security).

| Key | Default | Meaning |
|---|---|---|
| `output.save-drafts` | `true` | Save notes on Stop. |
| `output.dir` | `""` | Blank = `~/Documents/minutes-of-meeting`. |
| `auto.start-capture` | `false` | Begin capture immediately at launch. |
| `transcription.model-name` | `vosk-model-small-en-us-0.15` | Default speech model. |
| `transcription.allow-download` | `false` | Keep false: no runtime network access. |
| `feedback.from` / `feedback.to` | `noreply@ai-assist.com` / author | Feedback email addresses. |
| `feedback.relay-host` / `relay-port` / `username` / `password` / `start-tls` | direct-MX | Optional SMTP relay. |
| `security.api-token` | auto-generated | Bearer token for the local API. |
| `security.api-token-required` | `true` | Require the token on every API call. |

---

## 9. Security

The local REST API is defence-in-depth hardened (authentication is deliberately
lightweight for a single-user local app):

- **Loopback-only** bind (`127.0.0.1`) — unreachable from the network.
- **`LocalApiSecurityFilter`** rejects requests whose `Host` is not a loopback
  name (anti DNS-rebinding) and cross-origin browser requests; adds conservative
  security headers; error responses omit stack traces.
- **`ApiTokenAuthFilter`** requires a bearer token generated on first run and
  stored owner-only at `~/.ai-assist/api-token`.
- **SMTP** feedback verifies the TLS certificate on STARTTLS and refuses to send
  credentials over an unencrypted connection.
- **No secrets in the repo** — supply SMTP credentials via JVM system properties
  or an external, git-ignored `application.yml`.
- Release builds use the `harden` Maven profile to strip debug information from
  the bytecode. (JVM bytecode can never be fully decompile-proof; this raises the
  bar.)

---

## 10. Open-source licenses

ai-assist and everything it bundles is open source. Click a license for its full
text.

| Component | License |
|---|---|
| ai-assist application code | see the [GitHub repository](https://github.com/marutisridharjob/ai-assist) |
| Vosk speech engine & models | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Whisper / whisper.cpp & whisper-jni | [MIT](https://opensource.org/license/mit) |
| llama.cpp & de.kherud:llama | [MIT](https://opensource.org/license/mit) |
| Spring Boot & Jackson | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| JNA | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) / [LGPL-2.1](https://opensource.org/license/lgpl-2-1) |
| Java runtime (OpenJDK) | [GPLv2 with Classpath Exception](https://openjdk.org/legal/gplv2+ce.html) |
| BlackHole (optional macOS loopback) | [GPL-3.0](https://opensource.org/license/gpl-3-0) |

---

## 11. Build, packaging & distribution

- **Build the jar:** `mvn package` → `target/ai-assist-<version>.jar` (needs
  Java 21+). Run with `java -jar` or double-click.
- **Native installers:** `packaging/build-{linux.sh,macos.sh,windows.ps1}` use
  **jpackage** to produce `.deb`/`.rpm`, `.dmg`/`.pkg`, and `.msi`/`.exe`, each
  bundling its own Java runtime. `slim-jar.sh` strips the other platforms'
  native libraries first, cutting the jar ~40%.
- **CI:** `.github/workflows/package.yml` builds all three on native runners and
  publishes a GitHub Release on a `v*` tag.
- See [`packaging/README.md`](../packaging/README.md) for details, signing notes,
  and the per-OS slimming.

---

## 12. Repository layout

```
ai-assist/
├── src/main/java/com/aiassist/   # application source (see §5)
├── src/main/resources/           # application.yml, embedded resources
├── src/test/java/                # unit + integration tests
├── models/                       # model download scripts & guide
├── packaging/                    # jpackage build scripts, icon, slim-jar.sh
├── docs/                         # this document + ARCHITECTURE.md
├── .github/workflows/            # CI (tests, packaging)
└── pom.xml
```

Repository: <https://github.com/marutisridharjob/ai-assist>

---

## 13. Troubleshooting

| Symptom | Cause & fix |
|---|---|
| Live captions drop most words | Using the 1.8 GB Vosk model in real time — switch to `small` or `0.22-lgraph`. The saved transcript (Whisper) is unaffected. |
| "Not enough was captured to summarize" | Too little speech was recognized; check microphone / meeting-audio routing. |
| Remote participants not transcribed | System audio isn't reaching the app — use the speakers route or a loopback device; on macOS ensure Mic Mode is *Standard*. |
| Auto-start doesn't fire | It waits for real meeting audio; confirm a meeting app is running and audio is playing. |
| Notes "not generated" | They save to `~/Documents/minutes-of-meeting`; an *Open saved notes* link appears when done. |

---

*This document is maintained alongside the source. For diagrams see
[ARCHITECTURE.md](ARCHITECTURE.md); for user-facing setup see the top-level
[README](../README.md).*
