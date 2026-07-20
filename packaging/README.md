# Packaging ai-assist as a native installer

These scripts turn the app into a **native installer** for each OS using
[`jpackage`](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)
(part of the JDK). Each installer **bundles its own Java runtime**, so end users
do not need to install Java.

| OS | Script | Output | Installs to | Shortcut |
|---|---|---|---|---|
| Linux | `build-linux.sh [deb\|rpm]` | `.deb` / `.rpm` | `/opt/ai-assist` | menu entry |
| macOS | `build-macos.sh [dmg\|pkg]` | `.dmg` / `.pkg` | `/Applications` | in Applications |
| Windows | `build-windows.ps1 -Type msi\|exe` | `.msi` / `.exe` | per-user Program Files | Desktop + Start menu |

`jpackage` can only build for the OS it runs on, so all three are produced by
the **GitHub Actions workflow** (`.github/workflows/package.yml`) on macOS,
Windows, and Linux runners. Push a tag like `v0.1.0` to build all three and
publish them as a GitHub Release; or run the workflow manually to get the
installers as build artifacts.

## Build locally

Prerequisites: **JDK 21+** on `PATH` (the same JDK provides `jpackage`), plus:

- **Linux**: `sudo apt-get install -y fakeroot binutils` (for `.deb`).
- **Windows**: the [WiX Toolset v3](https://wixtoolset.org/) — `choco install wixtoolset -y`.
- **macOS**: nothing extra (`sips`/`iconutil` are built in).

```bash
# Linux
bash packaging/build-linux.sh deb
# macOS
bash packaging/build-macos.sh dmg
# Windows (PowerShell)
./packaging/build-windows.ps1 -Type msi
```

The installer lands in `./dist`. The build uses the `harden` Maven profile,
which strips debug information from the compiled classes.

## What the installed app does on first run

The installer only lays down the program; the app does the user-folder setup
itself, identically on every OS, so it always works and stays user-writable:

- creates `~/Documents/meeting-notes` (where notes are saved) and
  `~/Documents/ai-assist/models` (where you place model files);
- shows a **model-setup notice** with clickable download links for the models
  that are still missing, and where to put them;
- when you drop a Vosk model `.zip` into the models folder, it **unpacks it**
  and moves the original `.zip` to `~/Documents/meeting-notes/model-backups`.

## Notes on signing (avoiding OS security prompts)

Unsigned installers trigger a one-time security prompt:

- **macOS** Gatekeeper ("unidentified developer"). Fix: sign & notarize with an
  Apple Developer ID — add `--mac-sign` and the signing identity to
  `build-macos.sh`, or right-click → Open once.
- **Windows** SmartScreen. Fix: sign the `.msi`/`.exe` with an Authenticode
  code-signing certificate (`signtool sign ...`).

Signing needs certificates you own; the scripts are ready for them but ship
unsigned by default.

## About "avoid decompiling"

The `harden` profile strips line numbers, local-variable tables, and
source-file names, so a decompiler produces far less readable output. Note that
**JVM bytecode can never be made fully decompile-proof** — this raises the bar,
it does not lock the door. Stronger options (ProGuard symbol obfuscation, or a
GraalVM native image) are heavier and carry compatibility risk with Spring and
the bundled native libraries; see the project notes before pursuing them.
