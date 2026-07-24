# Contributing to ai-assist

Thanks for your interest in contributing. This is a small, single-maintainer
open-source project — please read this before opening a PR so review goes
smoothly.

## Ground rules

- By contributing, you agree your contribution is licensed under this
  project's [GPL-3.0 license](LICENSE).
- Be respectful — see the [Code of Conduct](CODE_OF_CONDUCT.md).
- Found a security issue? Don't open a public issue — see
  [SECURITY.md](SECURITY.md) instead.

## Getting set up

Requirements: JDK 21, Maven.

```bash
git clone https://github.com/marutisridharjob/ai-assist.git
cd ai-assist
mvn test        # 76 tests, no audio hardware or network needed
mvn package      # builds target/ai-assist-<version>.jar
```

The packaged jar ships with **no speech/LLM models** by design (keeps the
download small and the app fully offline until you choose otherwise) — see
[`models/README.md`](models/README.md) for how to add one for local testing.
For a quick dev loop, `mvn package -Pfetch-model` downloads the small Vosk
model straight into `./models` so you have something to run against
immediately.

Other Maven profiles:

- `-Pharden` — strips debug metadata for release builds (used by the
  packaging scripts, not needed for day-to-day development).

To build native installers (`.dmg`/`.msi`/`.deb`), see the scripts in
`packaging/` and `.github/workflows/package.yml` — these need to run on
their respective OS (jpackage can't cross-build).

## Code style

- No comments that restate what the code does — a comment should explain a
  *why* that isn't obvious from the code itself (a workaround, a hidden
  constraint, a non-obvious invariant). If it doesn't clear that bar, leave
  it out.
- Don't add abstractions, config flags, or generality for hypothetical
  future needs — match the size of the change to the size of the problem.
- Match the existing formatting/style of the file you're editing rather
  than introducing a new convention.
- New behavior should come with a test where practical; bug fixes should
  include a test that would have caught the bug.

## Making changes

1. Fork the repo and create a branch off `main`.
2. Make your change, with tests passing (`mvn test`).
3. If you're changing user-facing behavior, consider whether the README or
   `models/README.md` needs updating too — stale docs are worse than no
   docs.
4. Open a pull request describing *why* the change is needed, not just what
   changed (the diff already shows that).

## Reporting bugs / requesting features

Open a GitHub issue. For bugs, include:

- OS and version (Windows/macOS/Linux — behavior can be platform-specific,
  e.g. the native audio taps).
- Steps to reproduce.
- Relevant log output (launch the jar from a terminal with `java -jar
  ai-assist-<version>.jar` to see it).

## Dependency licenses

ai-assist depends only on permissively-licensed or copyleft-compatible open
source (Apache-2.0, MIT, GPL-family) — see the README's "100% open source"
table. If you add a new dependency, please confirm its license is
compatible with GPL-3.0 and add it to that table.
