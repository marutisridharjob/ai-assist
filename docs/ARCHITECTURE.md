# ai-assist — Architecture

This document describes the architecture of **ai-assist**: features, the
technologies behind each part, the runtime data flow, and the meeting
sequence. The diagrams below are [Mermaid](https://mermaid.js.org/) and render
on GitHub. An **editable draw.io** version (component architecture + data flow)
is at [`ai-assist-architecture.drawio`](ai-assist-architecture.drawio) — open it
at [diagrams.net](https://app.diagrams.net/).

- Repository: <https://github.com/marutisridharjob/ai-assist>
- Everything runs **100% locally and offline** — no cloud, no account, nothing
  leaves the machine (the one exception is the optional feedback email).

---

## 1. Feature map

```mermaid
mindmap
  root((ai-assist))
    Meeting
      Live captions (you / others)
      Auto-start on meeting audio
      Apply — live summary
      Stop — Save / No / Cancel
      Timestamped notes file
      Open-saved-notes link
    Assist
      Load .txt / .doc / .docx
      Grammar / Compact / Detailed
      Professional / Bullet points
      Summary / Email
      Communication styles
      Additional instructions
      Download result
    Help
      Appearance (light / dark)
      Instructions (searchable)
      Feedback (rating + message)
      Open-source licenses
    Cross-cutting
      100% offline
      Local REST API (loopback + token)
      First-run setup (folders, shortcuts)
      Native installers (mac / win / linux)
```

---

## 2. Component & technology architecture

```mermaid
flowchart TB
    subgraph UI["Desktop UI — Java Swing (Metal L&F)"]
        MC["MeetingConsole<br/>Meeting · Assist · Help tabs"]
    end

    subgraph API["Local REST API — Spring Boot / Tomcat (loopback + bearer token)"]
        LC["ListenController"]
        LV["LiveController"]
        DC["DraftController"]
    end

    subgraph SVC["Services"]
        LTS["LiveTranscriptionService<br/>capture + recognition threads"]
        MES["MeetingEndService"]
        SRS["StyleRewriteService"]
        LLM["LocalLlmService"]
        WT["WhisperTranscriber"]
        VMM["VoskModelManager"]
        FMS["FeedbackMailSender"]
    end

    subgraph NATIVE["Offline engines (native, bundled)"]
        VOSK["Vosk — live captions<br/>(Apache-2.0)"]
        WHISPER["whisper.cpp / whisper-jni<br/>final transcript (MIT)"]
        LLAMA["llama.cpp / de.kherud:llama<br/>summaries & rewrites (MIT)"]
    end

    subgraph STORE["Local storage"]
        NOTES["~/Documents/meeting-notes<br/>(.rtf notes + model backups)"]
        MODELS["~/Documents/ai-assist/models<br/>(gguf / ggml / vosk)"]
    end

    MC --> LTS
    MC --> MES
    MC --> SRS
    MC --> FMS
    LC --> LTS
    LV --> LTS
    DC --> MES
    DC --> SRS

    LTS --> VOSK
    LTS --> VMM
    MES --> WT --> WHISPER
    MES --> SRS
    SRS --> LLM --> LLAMA
    VMM --> MODELS
    LLM --> MODELS
    WT --> MODELS
    MES --> NOTES
    FMS -->|"SMTP (opt.)"| EMAIL["Recipient mail server"]

    classDef ext fill:#eee,stroke:#999;
    class EMAIL ext;
```

**Technologies at a glance**

| Layer | Technology | Purpose | License |
|---|---|---|---|
| UI | Java 21 + Swing (Metal L&F) | Native desktop window, same on every OS | GPLv2+CE (OpenJDK) |
| App wiring / API | Spring Boot 3.5 + Tomcat | Dependency injection, optional local REST API | Apache-2.0 |
| Live captions | Vosk 0.3.45 | Real-time speech-to-text | Apache-2.0 |
| Final transcript | whisper.cpp via whisper-jni 1.7.1 | Accurate offline transcription on Stop | MIT |
| Summaries / rewrites | llama.cpp via de.kherud:llama 4.1.0 | In-process LLM (GGUF model) | MIT |
| Native access | JNA 5.7.0 | Java ↔ native bindings | Apache-2.0 / LGPL-2.1 |
| System audio | Core Audio tap (macOS) · WASAPI (Windows) · PulseAudio/PipeWire monitor (Linux) | Capture the other participants | OS APIs |
| Packaging | jpackage (JDK) | Native installers with a bundled runtime | GPLv2+CE |

---

## 3. Runtime data flow

```mermaid
flowchart LR
    MIC["Microphone<br/>[you]"] --> RES1["Resample → 16 kHz mono"]
    SYS["System audio<br/>[others]"] --> RES2["Resample → 16 kHz mono"]
    RES1 --> REC["MeetingRecorder<br/>(per-source WAV)"]
    RES2 --> REC
    RES1 --> Q1["Recognition queue"]
    RES2 --> Q2["Recognition queue"]
    Q1 --> VOSK["Vosk recognizer"]
    Q2 --> VOSK
    VOSK --> BOX["Live transcript box<br/>(you = blue, others = plain)"]

    REC -->|"on Stop"| WHISPER["Whisper transcription"]
    WHISPER --> DRAFT["StyleRewriteService<br/>summary + action items"]
    DRAFT --> LLMD{"AI model present?"}
    LLMD -->|yes| LLAMA["llama.cpp"]
    LLMD -->|no| RULES["Offline rule-based drafter"]
    LLAMA --> FILE["Timestamped .rtf<br/>~/Documents/meeting-notes"]
    RULES --> FILE
```

Key design point: **audio capture and speech recognition run on separate
threads** connected by a bounded queue. If a heavy model can't keep up in real
time, the queue drops the oldest audio (keeping captions near-live) while the
**recording stays complete** — so the saved Whisper transcript is unaffected.

---

## 4. Meeting sequence

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant UI as MeetingConsole
    participant Mon as Activity monitor
    participant LTS as LiveTranscriptionService
    participant Vosk as Vosk
    participant MES as MeetingEndService
    participant Whisper as Whisper
    participant LLM as LLM / rules
    participant Disk as meeting-notes

    Note over UI,Mon: Auto-start armed (default on)
    Mon->>LTS: system audio active + meeting app detected
    UI-->>User: "Zoom detected — starting in 10s" (cancelable)
    User->>UI: (or press Start)
    UI->>LTS: start()
    loop While listening
        LTS->>Vosk: 16 kHz mono frames
        Vosk-->>UI: live captions (you / others)
        LTS->>Disk: record per-source WAV
    end
    User->>UI: Apply (optional)
    UI->>LLM: summarize transcript so far
    LLM-->>UI: live summary
    User->>UI: Stop → Save
    UI->>MES: finishNotes()
    MES->>Whisper: transcribe recording
    Whisper-->>MES: accurate transcript
    MES->>LLM: summary + action items
    LLM-->>MES: notes body
    MES->>Disk: write timestamped .rtf
    MES-->>UI: "Open saved notes" link
```

---

## 5. Security & first-run

```mermaid
flowchart TB
    subgraph Sec["Local REST API hardening"]
        A["Bind to 127.0.0.1 only"]
        B["Host / Origin filter<br/>(anti DNS-rebinding & CSRF)"]
        C["Bearer token (~/.ai-assist/api-token)"]
        D["Security headers · no stack traces"]
    end
    subgraph First["First run"]
        E["Create ~/Documents/meeting-notes"]
        F["Create ~/Documents/ai-assist/models"]
        G["Desktop shortcuts: app + notes folder"]
        H["Model-setup notice with download links"]
    end
    A --> B --> C --> D
    E --> F --> G --> H
```

See [`../packaging/README.md`](../packaging/README.md) for how the native
installers are built (and slimmed per-OS), and
[`TECHNICAL-DOCUMENTATION.md`](TECHNICAL-DOCUMENTATION.md) for the full write-up.
