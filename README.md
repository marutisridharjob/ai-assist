# ai-assist — listen & draft

A Spring Boot (Java 21) assistant that **listens** to what's happening — an active
Webex or MS Teams meeting playing on this computer, your dictation, or typed
notes — and **drafts detailed content** from it: meeting notes, emails,
documents, blog posts, or summaries.

**The app is fully offline.** Audio never leaves the machine, transcription
and drafting run locally, and the app makes no network requests at runtime —
runtime model download is disabled by default and the cloud-backed browser
speech API is deliberately not used. The only internet use is the one-time
build on your dev machine (Maven dependencies + optionally the speech model).

## Double-click and go

1. Build once (this also fetches the offline speech model into `./models`):
   `mvn package -Pfetch-model`
2. Double-click the launcher next to the jar:
   - **macOS**: `launch/AI-Assist.command` (first time: `chmod +x AI-Assist.command`)
   - **Windows**: `launch/AI-Assist.bat`

On launch the app automatically:

1. starts capturing audio (it prefers a loopback device so it hears the meeting),
2. opens `http://localhost:8080` in your browser,
3. transcribes speech locally with [Vosk](https://alphacephei.com/vosk/), and
4. re-drafts detailed notes every 30 seconds while it listens — the current
   draft is always on screen (in memory only).

When the meeting is over, click **End meeting & save notes** (or call
`POST /api/live/end`). That stops capture, locks the session against further
input, drafts the complete end-to-end meeting content, and saves it as one
timestamped Markdown file, e.g. `drafts/2026-07-05_15-02-41_live-meeting-notes.md`.
Nothing is written to disk before that moment — the file always contains the
full final meeting, never a partial snapshot.

Close the terminal window (or `Ctrl+C`) to stop.

To run on a machine with no internet at all: build elsewhere, then copy the
jar, the launcher, and the `models/` folder together. (Alternatively, unzip
any model from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
into `models/` yourself.)

## Hearing Webex / MS Teams audio

Meeting apps don't expose their audio streams directly; the assistant captures
from an audio **device**. To capture what the meeting is playing, give the OS a
loopback device and pick it in the UI (devices marked 🔁 are auto-preferred):

| OS | Loopback option |
|---|---|
| Windows | Enable **Stereo Mix** (Sound settings → Recording), or install [VB-Cable](https://vb-audio.com/Cable/) |
| macOS | Install [BlackHole](https://github.com/ExistentialAudio/BlackHole) and route/mirror output to it (e.g. a Multi-Output Device) |
| Linux | Select the PulseAudio/PipeWire **Monitor** source |

Without a loopback device the app falls back to the default microphone — which
also works for meetings if your speakers are audible to the mic, and for
dictating your own notes.

> Heads-up: recording a meeting may require the consent of participants
> depending on your jurisdiction and company policy.

## How it works

```
Webex/Teams audio ─┐
Microphone ────────┼─► Java Sound capture ─► Vosk (offline STT) ─┬─► Listening session ─► Drafting engine ─► Detailed draft
Typed notes ─────────────────────────────────────────────────────┘        (transcript)      (template or local Ollama)
```

- **Listening sessions** accumulate utterances (from live capture or typing)
  with ordering and timestamps.
- The **template drafting engine** (default, zero dependencies) segments the
  transcript, promotes the most informative sentences to key points, extracts
  commitments ("we need to…", "John will…") as action items, and assembles a
  structured document with a summary and sections shaped by content type
  (document, email, meeting notes, blog post, summary) and tone.
- Optionally, set `ai-assist.ollama.enabled=true` to draft with a local
  open-source LLM served by [Ollama](https://ollama.com) on `localhost`
  (e.g. `llama3.2`) — still no internet involved; if Ollama is unreachable
  the template engine takes over, so a draft is always produced.

## REST API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/sessions` | Start a listening session (`{"topic": "..."}`) |
| `POST` | `/api/sessions/{id}/utterances` | Add heard/typed text |
| `GET` | `/api/sessions/{id}` | Session with all utterances |
| `GET` | `/api/sessions/{id}/transcript` | Joined transcript |
| `POST` | `/api/sessions/{id}/draft` | Preview a draft from the session (`contentType`, `tone`) — not saved |
| `POST` | `/api/sessions/{id}/end` | End the meeting: stop capture, lock input, save the final timestamped notes file |
| `POST` | `/api/draft` | Ad-hoc draft from supplied notes — not saved |
| `GET` | `/api/audio/devices` | List capture devices (loopback flagged) |
| `POST` | `/api/live/start` | Start live meeting capture (`device`, `sessionId` optional) |
| `POST` | `/api/live/stop` | Pause live capture (session stays open) |
| `POST` | `/api/live/end` | End the current live meeting and save the notes file |
| `GET` | `/api/live/status` | Capture state (`IDLE/PREPARING/LISTENING/ERROR`) |
| `GET` | `/api/live/draft` | Latest interim auto-draft (in memory) |

Example:

```bash
curl -X POST localhost:8080/api/sessions -H 'Content-Type: application/json' \
     -d '{"topic":"Weekly status"}'
curl -X POST localhost:8080/api/sessions/{id}/utterances -H 'Content-Type: application/json' \
     -d '{"text":"We need to schedule the security audit."}'
curl -X POST localhost:8080/api/sessions/{id}/draft -H 'Content-Type: application/json' \
     -d '{"contentType":"MEETING_NOTES","tone":"PROFESSIONAL"}'
```

## Platforms

| Platform | Support |
|---|---|
| Windows / macOS / Linux | Full: runs the app, captures audio, drafts, saves files |
| iPhone / Android | As a remote screen: open `http://<computer-ip>:8080` in the phone browser to watch live notes and copy drafts (the app itself runs on a computer — there is no JVM on iOS) |

## Configuration (`application.yml`)

```yaml
ai-assist:
  output:
    save-drafts: true          # save final notes at meeting end (timestamped .md)
    dir: drafts                # where the files go
  auto:
    start-capture: true        # listen immediately on launch
    open-browser: true         # open the UI on launch
    draft-interval-seconds: 30 # rolling auto-draft cadence
    content-type: MEETING_NOTES
    tone: PROFESSIONAL
  transcription:
    model-name: vosk-model-small-en-us-0.15   # any model from alphacephei.com/vosk/models
    preferred-device: ""       # e.g. "Stereo Mix" / "BlackHole"
    allow-download: false      # keep false: no runtime network access
  ollama:
    enabled: false             # true = draft with a local LLM via Ollama
    model: llama3.2
```

## Build & test

```bash
mvn test                    # tests, no audio hardware or network needed
mvn package -Pfetch-model   # executable jar in target/ + speech model in models/
java -jar target/ai-assist-0.1.0-SNAPSHOT.jar
```

## Stack

Java 21 · Spring Boot 3.5 · [Vosk](https://alphacephei.com/vosk/) (Apache-2.0
offline speech recognition) · Java Sound API · Apache Commons Lang ·
optional local [Ollama](https://ollama.com) for LLM drafting · vanilla
HTML/JS UI served by the app itself.
