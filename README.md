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
5. **macOS: set Mic Mode to "Standard"** — open Control Center (menu bar)
   while the app is listening, click **Mic Mode**, choose **Standard**.
   *Voice Isolation* actively removes everything that isn't your own voice —
   including the meeting audio — and is the most common reason remote
   participants don't get transcribed.
6. Check the ai-assist window: the status line shows a live **audio level**
   per source. Play any video with speech — the level should jump and the
   words should appear in the transcript within a couple of seconds. In this
   route everything arrives through the microphone, so lines are tagged
   `[mic]` — that's expected. If the level stays near 0 % while audio is
   playing, the window tells you and points at the usual causes.

Notes: meeting apps cancel their own echo, so the remote side will not hear
themselves back; transcription quality is best in a quiet room.

### Best option on macOS: BlackHole (open source, one-time install)

If you're allowed to install open-source software, this is the clean answer
for macOS — it captures the meeting directly, silently, and works with
headphones. [BlackHole](https://github.com/ExistentialAudio/BlackHole) is a
free, GPL-3.0 virtual audio driver:

1. **Install** it: `brew install blackhole-2ch`, or download the installer
   from the [BlackHole GitHub page](https://github.com/ExistentialAudio/BlackHole)
   (choose the 2ch variant).
2. Open **Audio MIDI Setup** (Applications → Utilities) → **+** →
   **Create Multi-Output Device** → tick **your headphones (or speakers)**
   and **BlackHole 2ch**.
3. Select that **Multi-Output Device** as the speaker in Teams/Webex (or as
   the system output). You hear the meeting normally; BlackHole carries a
   copy of it.
4. Restart ai-assist. It **auto-detects BlackHole** as the meeting source —
   the status line lists it and remote speech appears tagged `[meeting]`,
   while your own voice still arrives via the microphone as `[mic]`.

### If you must use headphones (without installing anything)

The zero-setup route needs the meeting to be audible in the room. With
headphones, your options without installing anything are:

- **Windows, headphones in the analog jack**: enable **Stereo Mix** (see
  above). It captures the output mix of the built-in sound card, so it keeps
  working with jack-connected headphones. (Bluetooth/USB headsets bypass the
  sound card, so Stereo Mix won't carry them.)
- **macOS: Multi-Output Device** (built-in, no install): open
  **Audio MIDI Setup** (Applications → Utilities), click **+** →
  **Create Multi-Output Device**, tick both your headphones and
  **Built-in Speakers** — then select that Multi-Output Device as the
  meeting's speaker. You hear the meeting in your headphones while the
  built-in speakers also play it for the microphone; keep the speaker
  volume low — the mic sits right next to them. Caveat: the meeting is
  quietly audible in the room, so this isn't for confidential calls in
  shared spaces.
- **Fully silent capture with headphones on macOS needs BlackHole** (the
  open-source driver above) — the OS itself offers no app-accessible
  loopback today. macOS 14.2+ has a native "audio process tap" API that
  could remove even that install in a future version of ai-assist.

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

## Running on macOS — complete setup

Works on both Intel and Apple Silicon Macs (the speech engine ships as a
universal binary; no Rosetta needed).

1. **Install Java 21+** if not present: download the `.pkg` from
   [adoptium.net](https://adoptium.net) and install. Verify with
   `java -version` in Terminal.
2. **Start the app**: `java -jar /Applications/ai-assist-<version>.jar`
   (from Terminal), or double-click the jar if your Java installation
   registered the `.jar` file association. The jar can live anywhere —
   `/Applications`, `~/Downloads`, a USB stick — it never writes next to
   itself.
3. **Approve microphone access** the first time: macOS shows a permission
   prompt attributed to whatever launched Java — *Terminal* if you started
   from a terminal, *Java* if you double-clicked. Click **Allow**. The app's
   status line tells you while it is waiting on this.
4. The window opens, listening starts automatically, and after the meeting
   you press **Stop — meeting complete**: the notes file appears on your
   Desktop.

### macOS troubleshooting

| Symptom | Cause & fix |
|---|---|
| Status stuck on *"Opening audio devices…"* | macOS is waiting for microphone approval. Look for the permission prompt (it can hide behind windows). If it never appears: System Settings → Privacy & Security → **Microphone** → enable **Terminal** (or **Java**), then restart the app. To force a fresh prompt: `tccutil reset Microphone` in Terminal. |
| `Audio problem: UnsatisfiedLinkError … vosk_recognizer_set_grm` | You are running a build older than 2026-07-06. The vosk-java wrapper eagerly binds symbols its own macOS library doesn't export; current builds bypass it with a lazy binding. `git pull && mvn package` and use the new jar. |
| `Audio problem: … accepted none of the candidate formats` | The capture device refused every format (16/48/44.1 kHz, mono/stereo). Usually means microphone permission is denied (see above) or the selected input device is unavailable — check System Settings → Sound → **Input**. |
| "ai-assist-…jar cannot be opened because it is from an unidentified developer" | Gatekeeper quarantines browser-downloaded files. Right-click the jar → **Open**, or clear the flag: `xattr -d com.apple.quarantine ai-assist-<version>.jar`. Jars pulled via `git` are not quarantined. |
| Double-click does nothing / opens Archive Utility | Your Java install didn't claim the `.jar` association. Right-click → Open With → select the Java launcher, or just run `java -jar ai-assist-<version>.jar` from Terminal. |
| *"Preparing speech model…"* for more than ~30 s | First run unpacks + loads the model (5–20 s is normal). Longer means a hidden failure — current builds surface it in red in the status line; run from Terminal to also see the full log. |
| Remote participants aren't transcribed (only your own speech appears) | macOS has no built-in loopback device, so the meeting must be audible: follow the **Zero-setup route** above (speakers, not headphones). Then check, in this order: ① Control Center → **Mic Mode** must be **Standard** — *Voice Isolation strips the meeting audio out of the mic signal*; ② speaker volume ≥ 50 %; ③ watch the **audio level** in the app's status line while a video with speech plays — it should jump well above 0 %. With headphones, see **If you must use headphones** above. |
| Where are my notes / the model? | Notes: on the **Desktop**, named `<date>_<time>_live-meeting-notes.md`. Model cache: `$TMPDIR/ai-assist/models` (managed by the OS; safe to ignore). |

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
| macOS | Full — mic always; meeting audio via open-source [BlackHole](https://github.com/ExistentialAudio/BlackHole) (recommended, auto-detected) or the speakers route (macOS has no built-in loopback device) |
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
