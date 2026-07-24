# Security Policy

## Supported versions

ai-assist doesn't yet have tagged releases with independent support windows —
security fixes are made against the `main` branch, which is the only
supported version. Once versioned releases exist, this section will list
which lines still receive fixes.

## Reporting a vulnerability

Please **do not open a public GitHub issue** for a security vulnerability.

Instead, report it privately:

- Preferred: use GitHub's [private vulnerability
  reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)
  for this repository (Security tab → "Report a vulnerability"), if enabled.
- Otherwise, email **marutisridhar.job@gmail.com** with a description of the
  issue, steps to reproduce, and any relevant logs or proof-of-concept.

Please include:

- The version/commit you tested against.
- The operating system (ai-assist runs on Windows, macOS, and Linux, and a
  vulnerability may be platform-specific — e.g. the native audio taps, or
  Windows OneDrive folder redirection).
- Whether the issue requires local access, a crafted model file, or network
  conditions to trigger — ai-assist is designed to be fully offline by
  default, so any report describing unexpected network activity or data
  leaving the machine is treated as high priority.

You should expect an initial response within a few days. This is a
single-maintainer project without a dedicated security team, so response
times may vary, but reports are taken seriously and fixes are prioritized
over other work.

## Scope

In scope:

- ai-assist's own Java/Kotlin source code (this repository).
- The packaging scripts (`packaging/`) and how they build/sign installers.
- The optional local REST API (bound to `127.0.0.1` by default, protected by
  a bearer token) — see the README's "Optional REST API" section.

Out of scope:

- Vulnerabilities in third-party dependencies themselves (Spring Boot, Vosk,
  whisper.cpp, llama.cpp, JNA) — please report those upstream, though we'd
  appreciate a heads-up so we can track and update the pinned version here.
- Vulnerabilities that require the user to have already disabled ai-assist's
  offline-by-default design (e.g. manually setting
  `ai-assist.transcription.allow-download=true`) and knowingly point it at a
  malicious download URL.
- Social engineering, physical access to an already-unlocked machine, or
  other attacks that don't involve a flaw in ai-assist itself.

## Design notes for reviewers

A few things by design, documented here so they aren't mistaken for bugs:

- The REST API's bearer token is generated on first run and stored in
  `.ai-assist/settings.properties` next to the jar; it authenticates local
  processes on the same machine, not remote access (the server binds to
  `127.0.0.1` only).
- Feedback email is sent over SMTP with STARTTLS and full certificate
  hostname verification (see `FeedbackMailSender`); credentials (when a
  relay is configured) are only ever sent after the STARTTLS upgrade
  succeeds, never in the clear.
- The app does not phone home, check for updates, or send telemetry.
