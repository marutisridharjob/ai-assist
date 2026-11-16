# Meeting recording — full technical reference

This document describes exactly how ai-assist records a meeting, where the audio
is stored, how it is turned into the saved notes, how to control it from the
desktop window or the local REST API, and its limitations.

Everything in this document runs on the local machine. No audio leaves it.

---

## 1. Summary

| Item | Value |
|---|---|
| What is captured | Two sources: the **microphone** (label `you`) and **what the computer plays** (label `other`) |
| Format on disk | Raw PCM, **16 000 Hz, mono, signed 16-bit little-endian**, no header |
| Data rate per source | 32 000 bytes/s ≈ **115 MB per hour** per source |
| Storage location | `<java.io.tmpdir>/ai-assist/recordings/<sessionId>/` |
| When transcription happens | On **Stop** (after you choose Save), never during the meeting |
| Transcription engine | Whisper (whisper.cpp via whisper-jni), fully offline |
| Files kept after Stop | No — deleted after transcription (or on *No*) |
| Main classes | `MeetingCaptureService`, `MeetingRecorder`, `AudioDeviceService`, `NativeSystemAudioTap`, `AudioResampler`, `MeetingEndService`, `WhisperTranscriber`, `MeetingController` |

---

## 2. What is captured

### 2.1 Sources and labels

Each source is written to its own file. The label is also the speaker tag in the
saved notes.

| Label | Meaning | Written file | Shown in notes as |
|---|---|---|---|
| `you` | Default microphone (always included) | `you.pcm` | `You` |
| `other` | Audio the computer plays — meeting participants (Teams, Webex, Zoom, browser, …) | `other.pcm` | `Other` |

### 2.2 How the `other` source is chosen

The "other" source is selected in this order (see `MeetingCaptureService.resolveSelections`):

1. **Preferred device** — if `ai-assist.transcription.preferred-device` is set
   (non-empty), that named capture device is used as the `other` source and
   auto-detection is skipped.
2. **Native system-audio tap** — if the native helper is supported on this OS,
   it captures the computer's output directly and replaces any loopback devices.
   - **macOS 14.2+**: Core Audio process/system tap (helper built from
     `src/main/resources/mac/SystemAudioTap.swift`).
   - **Windows**: WASAPI loopback (helper `src/main/resources/windows/system-audio-tap.exe`,
     source in `native/windows-tap/src/main.rs`).
3. **Loopback devices by name** — otherwise every capture device whose name or
   description contains one of these hints is used:
   `stereo mix`, `monitor`, `loopback`, `wave out`, `what u hear`, `blackhole`,
   `vb-audio`, `cable output`.

If none of these is available, only the microphone is recorded. Meeting audio
then has to reach the microphone (speakerphone), or the user must install a
loopback driver (for example BlackHole on macOS or VB-Cable on Windows).

### 2.3 Microphone and loopback devices (Java Sound path)

- Opened with Java Sound (`AudioDeviceService.openBestCaptureLine`).
- The device's first accepted format is used, tried in this order:
  16 kHz mono → 48 kHz mono → 44.1 kHz mono → 48 kHz stereo → 44.1 kHz stereo,
  all 16-bit signed little-endian.
- Stereo is averaged to mono (`downmixToMono`) before resampling.
- Read buffer: 4096 bytes × frame size.

### 2.4 Native system-audio tap path

- The helper is started as a child process (`NativeSystemAudioTap.startHelper`).
- It prints a one-line ASCII header with the sample rate, then streams 16-bit
  little-endian mono PCM on stdout.
- Its stderr is drained and logged with the `[system-tap]` prefix.
- The helper is compiled on first use (macOS: Swift; Windows: prebuilt exe).
  Compile timeout: 180 s. Work folder: `<tmpdir>/ai-assist/tap`.
- If the helper fails at runtime, `NativeSystemAudioTap.disableAfterRuntimeFailure`
  disables it, and the message tells the user to press **Pause then Resume** to
  fall back to loopback devices or the microphone.

---

## 3. Processing pipeline (capture → disk)

```
mic / loopback line ──► read 4096-byte chunks ──► downmix to mono (if stereo)
                                              ──► peak level (for the UI meter)
                                              ──► AudioResampler.to16k (linear)
                                              ──► MeetingRecorder.record(label, pcm16k)
                                                    └─ BufferedOutputStream → <label>.pcm

native tap stdout ───► read chunks ──► peak level ──► to16k (source rate from header)
                                              ──► MeetingRecorder.record(...)
```

Details:

- **Resampling** (`AudioResampler.to16k`): linear interpolation to exactly
  16 000 Hz. If the device is already at 16 kHz, the bytes are copied unchanged.
  Empty input returns an empty array.
- **Level meter**: the peak sample of each chunk is converted to 0–100 and shown
  in the UI (`levels()`). The meter does not affect the recording.
- **Threads**: one capture thread per source (`capture-<label>`), started by a
  short-lived `capture-init` thread. All threads are daemon threads.
- **Write path**: `MeetingRecorder.record` appends to a `BufferedOutputStream`
  per source, synchronized on the stream. Write errors are logged
  (`Could not record <label> audio`) and that chunk is dropped; capture continues.

---

## 4. Files on disk

### 4.1 Location

```
<java.io.tmpdir>/ai-assist/recordings/<sessionId>/you.pcm
<java.io.tmpdir>/ai-assist/recordings/<sessionId>/other.pcm
```

Typical `java.io.tmpdir` values:

| OS | Path prefix |
|---|---|
| macOS | `/var/folders/.../T/` (per-user temp) |
| Windows | `C:\Users\<name>\AppData\Local\Temp\` |
| Linux | `/tmp/` |

The `<sessionId>` is the session created by the meeting (`sessions.create`).
Each meeting has its own folder.

### 4.2 Format

| Property | Value |
|---|---|
| Encoding | Signed 16-bit PCM, little-endian |
| Channels | 1 (mono) |
| Sample rate | 16 000 Hz |
| Header | None (raw samples) |
| Bytes per second | 32 000 |
| Size per hour, per source | ≈ 115 MB |
| Duration of a file | `size_in_bytes / 32 000` seconds |

You can play a file with tools that accept raw PCM, for example:

```bash
ffplay -f s16le -ar 16000 -ac 1 you.pcm
```

### 4.3 Lifetime

| Event | What happens to the files |
|---|---|
| Recording starts | Folder and files are created when each source first delivers audio |
| Pause | Files stay open and are not closed; no new audio is written |
| Resume | Writing continues into the same files (appended) |
| Stop → **Save** | Files are closed, transcribed, then deleted |
| Stop → **No** | Files are closed and deleted without transcription |
| Stop → **Cancel** | Recording keeps running |
| App crash or force-quit | Files are **not** deleted by the app; they remain in the temp folder until the OS cleans temp space |

---

## 5. Meeting lifecycle and states

### 5.1 Capture states (`MeetingCaptureService.State`)

| State | Meaning | How it is entered |
|---|---|---|
| `IDLE` | Nothing is recording | Initial state; after Stop |
| `PREPARING` | Opening audio sources (the OS may ask for permission) | `start()` |
| `LISTENING` | At least one source is recording | First source reports `markListening()` |
| `PAUSED` | Recording paused, session still open | `pause()` |
| `ERROR` | A source failed, or the recording folder could not be created | `markFailed()`, or `start()` when `MeetingRecorder` cannot be created |

`Status` also carries `sessionId`, `devices` (the sources in use, shown as
`display name [label]`) and `detail` (a human-readable line for the status bar).

### 5.2 Transitions

| From | Trigger | To | Notes |
|---|---|---|---|
| `IDLE` | `start()` | `PREPARING` | Recorder folder is created first; on failure → `ERROR` |
| `PREPARING` | first source delivers audio | `LISTENING` | |
| `PREPARING` / `LISTENING` | `pause()` | `PAUSED` | Capture threads stop; files stay open |
| `PAUSED` | `resume()` (= `start(sessionId)`) | `PREPARING` → `LISTENING` | Same session and files |
| `PREPARING` / `LISTENING` / `PAUSED` | `stop()` | `IDLE` | Session kept, recording still attached until `finishRecording` |
| any source | fails | `ERROR` | Only if no other source is still delivering |
| `ERROR` / `IDLE` | `start()` | `PREPARING` | |

Notes:

- `start()` on a running meeting returns HTTP 409 (`Recording is already running`).
- `resume()` on a meeting that is not paused returns HTTP 409
  (`No paused meeting to resume`).
- A failure on one source does not stop the others. The state becomes `ERROR`
  only when no other source is still delivering.

### 5.3 Start

1. `MeetingCaptureService.start(devices, sessionId)`:
   - If `sessionId` is blank, a new session titled `Minutes of meeting` is created.
     The title is changed later from the window.
   - A `MeetingRecorder` is created for the session's folder. If that fails,
     the state becomes `ERROR` with `Cannot record meeting audio: …` and the
     call returns.
2. A `capture-init` thread resolves the sources and starts one capture thread
   per source.

### 5.4 Pause and resume

- **Pause** closes the capture lines and joins the capture threads. The recorder
  keeps its streams open, so the files stay as they are.
- **Resume** calls `start()` for the same session. The recorder is reused
  (`recorderSessionId` matches), so audio is appended to the same files.
- Because silence is not written during a pause, **the recording timeline does
  not include the paused time** (see §9).

### 5.5 Stop and the Save / No / Cancel choice

Stop in the window opens a choice:

| Choice | Effect |
|---|---|
| **Save** | `MeetingEndService.stopCapture` stops capture, marks the session ended, and detaches the recording (`finishRecording`). Notes are then produced in the background by `finishNotes`. |
| **No** | `MeetingEndService.discardNotes` deletes the recording files. No notes are written. |
| **Cancel** | Nothing changes. |

`stopCapture` is fast and synchronous, so the window can start a new meeting
while the previous one is transcribed. A new meeting's recorder cannot overwrite
the previous files because `finishRecording` detaches them first.

---

## 6. From recording to notes (Whisper)

Implemented in `MeetingEndService.finishNotes` and `transcribeRecording`.

### 6.1 Steps

1. `finishRecording` returns a map `label → file` for each source that wrote audio.
2. `transcribeRecording` runs on a background worker with a **30-minute** limit
   (`TRANSCRIPTION_TIMEOUT`):
   - If there are no files, or Whisper is unavailable, the files are deleted and
     the session's utterances are used instead (see §6.4).
   - Otherwise each file is transcribed separately with `WhisperTranscriber.transcribe(Path)`.
3. Each Whisper segment becomes `Timed(start, label, text)`.
4. All segments from all sources are **sorted by start time**.
5. Each segment becomes an `Utterance` with
   `timestamp = session.startedAt + start`, and the source label as speaker.
6. Every file is deleted in a `finally` block, whatever the outcome.
7. The summary (LLM if installed, otherwise the offline drafter) is produced
   with a 5-minute limit. The transcript is appended to the notes with speaker
   labels (`You:` / `Other:`).
8. The notes are saved as `Minutes-<date>-<time>.rtf` in
   `~/Documents/minutes-of-meeting` (see `UserPaths`).

If the transcript is blank after all of this, the request fails with HTTP 422
(`ended but no speech was transcribed`). The message points to the Whisper
model and to audio capture.

### 6.2 Whisper model

Model discovery (`WhisperTranscriber.findModel`):

- Searches the folders from `ModelFolders.modelSearchRoots()`, in this order:
  1. `~/Documents/ai-assist/models`
  2. `models/` next to the jar
  3. the jar's folder
  4. `./models` in the working directory
  5. the working directory
- Accepts files named `ggml-*.bin`.
- If several are present, the most accurate one wins. Ranking is by the size in
  the name: `large` > `medium` > `small` > `base` > `tiny`. Ties are broken by
  file name.

Decoding settings:

| Setting | Value | Reason |
|---|---|---|
| Strategy | Greedy | Speed |
| Threads | `available processors − 1` (min 1) | Leaves one core for the UI |
| Non-speech tokens | Suppressed | Stops music or applause being written as words |
| Temperature fallback | whisper.cpp default | Accuracy over speed |
| Hallucination filter | Drops short "words" that span long quiet stretches; collapses stuck decoder loops (3+ identical segments) | Removes common Whisper artefacts |

Whisper's native library is loaded once and kept. `releaseModel()` frees it
before uninstall, so Windows can delete the file.

### 6.3 Fallback order

| Situation | Transcript used |
|---|---|
| Recordings exist and Whisper works | Whisper segments |
| Whisper gives no segments | Session utterances (usually none) → HTTP 422 if still empty |
| Whisper model missing | Session utterances → HTTP 422 if empty |
| Transcription exceeds 30 min | Session utterances after the timeout (Whisper keeps running in the background and its result is discarded) |
| Another meeting's notes are still processing | Transcription still runs; the flag only changes logging |

### 6.4 Session utterances (API-supplied text)

Text can be added to a session through the REST API
(`POST /api/sessions/{id}/utterances`). Recording does not create utterances.
These entries are only used as a fallback when there is no usable recording.
They are included in the saved notes like any other transcript.

---

## 7. Controlling recording

### 7.1 Desktop window

| Control | Action |
|---|---|
| **Start** | `start(null, null)` — new meeting; or `resume()` when paused |
| **Pause** | `pause()` |
| **Stop** | Save / No / Cancel dialog (see §5.5) |
| Title field | Sets the session title and therefore the notes file name |
| Auto-start | Starts a meeting when a meeting app or sustained audio is detected |

The window polls the status about 4 times per second (`refresh()`).

### 7.2 REST API

All routes are under `/api` and require the API token (see §8.3). They are
served on `localhost` only.

| Method | Path | Body | Returns |
|---|---|---|---|
| `GET` | `/api/audio/devices` | — | Capture devices: `[{name, description, likelyLoopback}]` |
| `POST` | `/api/live/start` | `{ "devices": [...]?, "sessionId": "..."? }` (optional) | `Status` |
| `POST` | `/api/live/pause` | — | `Status` |
| `POST` | `/api/live/resume` | — | `Status` (HTTP 409 if not paused) |
| `POST` | `/api/live/stop` | — | `Status` (stops capture only; no notes) |
| `GET` | `/api/live/status` | — | `Status` |
| `POST` | `/api/live/end` | optional `{ "contentType", "tone", … }` | `Draft` — ends the current meeting and saves the notes (HTTP 404 if none is running) |
| `POST` | `/api/sessions/{id}/end` | optional draft options | `Draft` — ends a specific session and saves the notes (HTTP 409 if already ended, 422 if empty) |

`Status` JSON:

```json
{
  "state": "LISTENING",
  "sessionId": "2f1c…",
  "devices": ["default microphone [you]", "system audio (native tap) [other]"],
  "detail": "Recording meeting audio"
}
```

Example:

```bash
TOKEN=$(cat <folder-with-the-jar>/.ai-assist/api-token)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:1234/api/audio/devices
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"devices":["default microphone"]}' http://localhost:1234/api/live/start
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"contentType":"MEETING_NOTES","tone":"PROFESSIONAL"}' http://localhost:1234/api/live/end
```

The port is 1234 by default. If it is taken, the launcher uses a free port
(see `AiAssistApplication`). Override with `-Dai-assist.port=N`.

---

## 8. Configuration

### 8.1 Settings in `application.yml`

```yaml
ai-assist:
  transcription:
    preferred-device: ""     # blank = auto-detect; set a device name to force the "other" source
```

`TranscriptionProperties` holds only this value. Recording itself has no
on/off switch. It is always on while a meeting runs.

### 8.2 Settings file

`<folder-with-the-jar>/.ai-assist/settings.properties` keeps
UI preferences: dark mode and auto-start. It does not store recording settings.

### 8.3 API token

- `ai-assist.security.api-token` — fixed token; blank means auto-generate and persist.
- `ai-assist.security.api-token-required` — default `true`.
- `ai-assist.security.token-file` — default `.ai-assist/api-token` next to the jar.

---

## 9. Limitations and known behaviour

These follow from the current implementation. They are facts about the code,
not planned features.

1. **Timeline drift between sources.** Each file is a plain sequence of samples,
   not a timed track. A source only grows while it delivers audio:
   - The native tap writes nothing while the computer plays silence
     (`native/windows-tap/src/main.rs`: "Loopback delivers no events while the PC
     plays silence").
   - Pauses remove time from both files.

   Whisper timestamps are file offsets. So the `Other` lines can drift from the
   `You` lines, especially in long quiet stretches. Use the order within each
   speaker as reliable; treat the cross-speaker order as approximate.
2. **Timestamps are not wall-clock.** Each segment time is
   `session start + offset into the file`. Paused time is not counted, so
   timestamps are shorter than the real meeting length when there was a pause.
3. **No crash recovery.** A crash or force-quit leaves `.pcm` files in the
   temp folder. Nothing re-processes them, and nothing deletes them.
4. **Write errors drop audio silently for the user.** A failed write is logged
   (`Could not record … audio`) but the capture keeps running, so a gap can
   occur without a visible error in the window.
5. **Recordings are unencrypted** raw audio in the OS temp folder, readable by
   any process running as the same user while they exist.
6. **Single Whisper model per run.** The most accurate `ggml-*.bin` is used.
   Changing models requires a restart or a new file (the model is reloaded when
   its path changes).
7. **Transcription runs after Stop only.** The window shows no text while the
   meeting is in progress. Only the level meters and status line are live.
8. **Native tap availability.** It needs macOS 14.2+ or Windows, and the helper
   can fail at runtime. The app then disables it and falls back to loopback
   devices or the microphone on the next Start or Resume.
9. **Disk use.** About 230 MB per hour when both sources are active (≈ 115 MB
   per source per hour). Temp space must be available for the whole meeting.

---

## 10. Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| Status stuck on `Opening audio sources…` | macOS/Windows waiting for microphone or system-audio permission | Approve the OS prompt; check Privacy & Security → Microphone (and System Audio Recording on macOS) |
| `Cannot record meeting audio: …` | `<tmpdir>/ai-assist/recordings` cannot be created | Check temp-folder permissions and free disk space |
| `you` level stays at 0 % | Wrong or denied microphone | Check the OS input device and microphone permission |
| `other` source missing from the status line | No loopback device and the native tap is unavailable | Install BlackHole (macOS) or VB-Cable (Windows), or set `preferred-device`; or use speakerphone so the meeting reaches the microphone |
| `Audio problem: … accepted none of the candidate formats` | The device refused 16/48/44.1 kHz mono/stereo | Choose another input device; check permissions |
| `Audio problem: system-audio helper exited with code …` | Native tap crashed | Press Pause then Resume; check `[system-tap]` lines in the log |
| Notes say *no speech was transcribed* (HTTP 422) | No Whisper model, or recording was empty | Put a `ggml-*.bin` file in `~/Documents/ai-assist/models`; check that the recording files exist during the meeting |
| Notes are slow to appear | Long recording on a slow CPU | Expected; the 30-min limit applies. Use a smaller Whisper model for speed |
| Old `.pcm` files left in temp | Crash or force-quit | Delete `<tmpdir>/ai-assist/recordings/<sessionId>/` |

Log messages to look for (`logs` of the app):

- `Recording '<device>' as [<label>] at <rate> Hz <mono|stereo> → 16 kHz into session <id>`
- `Recording system audio via native tap at <rate> Hz → 16 kHz into session <id>`
- `Whisper transcribed <file> (<n>s of audio) in <ms> ms`
- `Whisper transcribed meeting <id> into <n> segments`
- `Meeting <id> finished with <n> utterances; notes saved to <path>`

---

## 11. Code map

| File | Responsibility |
|---|---|
| `audio/MeetingCaptureService.java` | State machine (`IDLE/PREPARING/LISTENING/PAUSED/ERROR`), source selection, capture threads, pause/resume, level meters, activity monitor |
| `audio/MeetingRecorder.java` | Creates the session folder, writes `<label>.pcm`, closes and returns files, deletes them |
| `audio/AudioDeviceService.java` | Lists capture devices, picks the `you` and `other` sources, opens Java Sound lines with format negotiation |
| `audio/NativeSystemAudioTap.java` | Compiles/starts the native helper, reads its header, handles runtime failure |
| `audio/AudioResampler.java` | Linear resampling to 16 kHz |
| `audio/MeetingController.java` | REST endpoints under `/api/audio` and `/api/live` |
| `audio/WhisperTranscriber.java` | Loads the Whisper model, decodes a PCM file to timed segments, applies the hallucination filters |
| `draft/MeetingEndService.java` | Stop flow: `stopCapture`, `finishNotes`, `transcribeRecording`, `discardNotes`; timeouts; file cleanup |
| `draft/DraftController.java` | `/api/sessions/{id}/end` and `/api/live/end` |
| `setup/ModelFolders.java` | Folder search order for model files |
| `setup/UserPaths.java` | `~/Documents/minutes-of-meeting` and the models folder |
| `config/TranscriptionProperties.java` | `ai-assist.transcription.preferred-device` |
| `ui/MeetingConsole.java` | Window controls (Start/Pause/Stop, Save/No/Cancel), status polling |

---

## 12. Tests

| Test | Covers |
|---|---|
| `audio/AudioResamplerTest` | Pass-through at 16 kHz, 32 kHz and 48 kHz upsampling/downsampling, 8 kHz upsampling, empty input |
| `audio/WhisperTranscriberTest` | PCM → float conversion, hallucination filter, filler-loop collapse, model ranking, model not found |
| `ListenAndDraftIntegrationTest` | End-to-end API flow, session end, 422 on empty meeting, 404 with no live meeting |
| `audio/MeetingRecorderTest` | Recorder file handling (see the test class for details) |

Run them with:

```bash
mvn test
```
