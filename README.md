# ai-assist — listen & draft meeting notes

A self-contained desktop assistant (Java 21 / Spring Boot) that **listens** to
a meeting happening on this computer — MS Teams, Webex, or any platform, plus
the room via the microphone — transcribes the **English** speech locally, and
when you press **Stop** drafts the complete meeting notes and saves them as a
timestamped file.

**No internet. No browser. No other apps. No third-party drivers.** The app
uses only the operating system's own resources (audio devices, a window,
files). Speech recognition runs inside the app with the proven, lightweight
[Vosk](https://alphacephei.com/vosk/) small English model (~40 MB, Apache-2.0),
embedded into the jar at build time.

## The window

Launching the app opens its own window (built on Swing, which ships with the
JDK — not a browser):

- a **scrolling text box** where the running transcript appears live, each
  line tagged `[mic]` or `[meeting]`,
- **Start meeting** — begins a fresh meeting; listening also auto-starts on
  launch, so this is mainly for starting the next meeting after a Stop,
- **Pause / Resume** — temporarily stop listening without ending the meeting,
- **Stop — meeting complete** — ends the meeting: capture stops, the full
  end-to-end notes are drafted and saved as a timestamped Markdown file on
  your **Desktop** (e.g. `2026-07-05_15-02-41_live-meeting-notes.md`), and
  the final notes are shown in the window,
- the **close button in the top corner** — if a meeting is still running you
  are asked whether to save before closing.

Nothing is written to disk until Stop (or a confirmed save-on-close).

## What it hears

The app captures **several sources at once**, each transcribed independently:

1. **The microphone** (always) — you and everyone audible in the room.
2. **The meeting audio** — any OS capture device that carries what the
   computer is playing:
   - **Windows 11**: many built-in sound drivers expose **Stereo Mix** — it's
     already installed, just enable it: *Settings → System → Sound → More
     sound settings → Recording → right-click → Show Disabled Devices →
     enable "Stereo Mix"*. The app auto-detects and uses it.
   - **macOS**: the OS provides no built-in loopback capture device, so use
     the zero-setup route below.

### Zero-setup route — meeting audio through the speakers

Works on every OS with nothing to install, enable, or configure: let the
meeting play out loud and the microphone hears both you **and** the remote
participants. Set it up like this:

1. **Unplug/disconnect headphones** — if the meeting plays into your ears,
   the microphone can't hear it. AirPods or a headset must be disconnected
   (or at least not selected as the meeting's speaker).
2. **Point the meeting app at the speakers**:
   - *MS Teams*: Settings → Devices → **Speaker** → choose the built-in
     speakers (e.g. "MacBook Pro Speakers").
   - *Webex*: Settings → Audio → **Speaker** → choose the built-in speakers.
   - Or simply set the system default output to the speakers (macOS:
     System Settings → Sound → Output; Windows: Settings → System → Sound).
3. **Raise the volume** to a normal conversational level (roughly 50 % or
   more) — if you can hear the meeting comfortably, so can the microphone.
4. **Leave the microphone unobstructed** (don't cover the laptop keyboard
   area with papers or a phone).
5. Check the ai-assist window: remote speakers' words should appear in the
   scrolling transcript within a couple of seconds of being spoken. In this
   route everything arrives through the microphone, so lines are tagged
   `[mic]` — that's expected.

Notes: meeting apps cancel their own echo, so the remote side will not hear
themselves back; transcription quality is best in a quiet room. If you must
use headphones, this route can't work — use a loopback device (Windows
Stereo Mix above) instead.

## Getting the app

**Ready-built**: `dist/ai-assist-<version>.jar` in this repository is the
complete self-contained app, speech model included — copy that one file and
run it.

To rebuild it yourself (the model ships in `src/main/resources/vosk-model.zip`,
so a plain build embeds it — internet is only needed for Maven dependencies):

```bash
mvn package
```

To refresh the model archive from upstream, build with `-Pfetch-model`.

The result, `target/ai-assist-<version>.jar`, is **one file containing
everything** — code and speech model. That single jar is all you ever copy,
ship, or click: **double-click it** to start (needs Java 21+, e.g. from
[adoptium.net](https://adoptium.net)), or run `java -jar ai-assist-<version>.jar`.
The embedded model is unpacked invisibly into OS temp space at startup; the
only visible output the app ever creates is the notes file that appears on
your Desktop when you press Stop — never anything in the folder it was
launched from. The app makes **zero network requests at runtime** — verified
by socket inspection in testing.

> Recording a meeting may require participants' consent depending on your
> jurisdiction and company policy.

## How it works

```
Teams/Webex audio (OS loopback device) ─► Vosk recognizer ─┐
Microphone ────────────────────────────► Vosk recognizer ─┼─► listening session ─► drafting engine ─► window + timestamped .md on Stop
                                                           │      (transcript)       (summary, sections,
                                          [mic]/[meeting] ─┘                          key points, action items)
```

- Every recognized phrase lands in one **listening session**, labelled with
  its source and sequence.
- Every 30 s an **interim draft** is refreshed in memory (never on disk).
- **Stop** locks the session, drafts the complete transcript — title,
  summary, discussion, key points, action items — and saves the one final
  timestamped file.

## Optional REST API (localhost)

The window drives everything, but the same controls exist as a local API:
sessions (`/api/sessions…`), capture (`/api/live/start|pause|resume|stop|status`),
meeting end (`/api/live/end`, `/api/sessions/{id}/end` — the only calls that
write the notes file), previews (`/api/draft`, `/api/sessions/{id}/draft`),
and device listing (`/api/audio/devices`).

## Configuration (`application.yml`)

```yaml
ai-assist:
  output:
    save-drafts: true          # save final notes at meeting Stop
    dir: ${user.home}/Desktop   # where the notes file appears on Stop
  auto:
    start-capture: true        # listen immediately on launch
    draft-interval-seconds: 30 # interim in-memory draft cadence
    content-type: MEETING_NOTES
    tone: PROFESSIONAL
  transcription:
    model-name: vosk-model-small-en-us-0.15  # English; embedded in the jar
    allow-download: false      # keep false: no runtime network access
    preferred-device: ""       # optional explicit meeting-audio device
```

## Platforms

| Platform | Support |
|---|---|
| Windows 11 | Full — mic always; meeting audio via built-in Stereo Mix (when the driver provides it) or the speakers route |
| macOS | Full — mic always; meeting audio via the speakers route (macOS has no built-in loopback device) |
| Linux | Full — mic always; meeting audio via the PulseAudio/PipeWire Monitor source (built into the OS) |

## Build & test

```bash
mvn test                    # 43 tests, no audio hardware or network needed
mvn package                 # self-contained jar (model ships in resources)
```

## Stack

Java 21 · Spring Boot 3.5 · Swing (JDK-built-in window) · Java Sound API ·
[Vosk](https://alphacephei.com/vosk/) small English model (Apache-2.0,
embedded) · Apache Commons Lang · optional local
[Ollama](https://ollama.com) drafting (off by default).
