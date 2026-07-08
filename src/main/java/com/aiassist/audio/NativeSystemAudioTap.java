package com.aiassist.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct capture of "everything the computer is playing" — the way modern
 * commercial meeting-notes apps do it — via a tiny native helper compiled
 * once on the user's machine with tools the OS already provides:
 *
 * <ul>
 *   <li><b>macOS 14.2+</b>: Core Audio process tap (Swift, compiled with the
 *       Xcode Command Line Tools' {@code swiftc}); asks once for the
 *       "System Audio Recording" permission.</li>
 *   <li><b>Windows Vista..11</b>: WASAPI loopback on the default output
 *       device — a prebuilt executable embedded in the jar, produced from
 *       the proven open-source {@code wasapi} Rust crate (MIT); nothing to
 *       compile or install, no Stereo Mix or virtual cable, and it works
 *       with any headphones. Source ships in {@code native/windows-tap}.</li>
 * </ul>
 *
 * The helper streams one ASCII header line {@code AI_ASSIST_TAP <rate>}
 * followed by 16-bit little-endian mono PCM on stdout, and exits when its
 * stdin closes. When the helper can't be built (no developer tools, other
 * OS), {@link #isSupported()} is false and capture falls back to loopback
 * devices (Stereo Mix/BlackHole) or the microphone.
 */
public final class NativeSystemAudioTap {

    public static final String SOURCE_NAME = "system audio (native tap)";

    private static final Logger log = LoggerFactory.getLogger(NativeSystemAudioTap.class);
    private static final long COMPILE_TIMEOUT_SECONDS = 180;

    private static Boolean supported;
    private static Path helperBinary;
    private static String reason;

    private NativeSystemAudioTap() {
    }

    /** True when a helper binary is ready to run; builds it on first call. */
    public static synchronized boolean isSupported() {
        if (supported == null) {
            try {
                supported = prepareHelper();
            } catch (Exception e) {
                reason = e.getMessage();
                supported = false;
            }
            if (!supported) {
                log.info("Native system-audio tap unavailable: {}", reason);
            }
        }
        return supported;
    }

    /** Why {@link #isSupported()} returned false, for logs and status lines. */
    public static synchronized String unavailableReason() {
        return reason;
    }

    /**
     * Called when the helper failed at runtime (e.g. permission denied,
     * device error): the tap is disabled for the rest of this app run so the
     * next start/resume automatically falls back to loopback devices or the
     * microphone instead of failing the same way again.
     */
    public static synchronized void disableAfterRuntimeFailure(String message) {
        supported = false;
        reason = "disabled after a runtime failure: " + message;
        log.warn("Native system-audio tap {}", reason);
    }

    public static Process startHelper() throws IOException {
        if (!isSupported()) {
            throw new IllegalStateException("system audio tap not available: " + reason);
        }
        return new ProcessBuilder(helperBinary.toString()).start();
    }

    /**
     * Reads the helper's one-line ASCII header from its stdout and returns
     * the sample rate; everything after it is raw PCM.
     */
    public static float readHeader(InputStream stdout) throws IOException {
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = stdout.read()) != -1 && b != '\n' && line.length() < 200) {
            line.append((char) b);
        }
        String header = line.toString().strip();
        if (!header.startsWith("AI_ASSIST_TAP ")) {
            throw new IOException("unexpected system-tap header: \"" + header + "\"");
        }
        return Float.parseFloat(header.substring("AI_ASSIST_TAP ".length()).strip());
    }

    private static boolean prepareHelper() throws IOException, InterruptedException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "ai-assist", "tap");
        if (os.contains("mac")) {
            if (!macVersionAtLeast(14, 2)) {
                reason = "requires macOS 14.2+ (this is " + System.getProperty("os.version") + ")";
                return false;
            }
            Path compiler = findOnPath(List.of("xcrun"));
            if (compiler == null) {
                reason = "Xcode Command Line Tools not found - install once with: xcode-select --install";
                return false;
            }
            return compile(workDir, "/mac/SystemAudioTap.swift", "SystemAudioTap.swift",
                    "system-audio-tap",
                    source -> List.of("xcrun", "swiftc", "-O",
                            "-o", workDir.resolve("system-audio-tap").toString(), source.toString()));
        }
        if (os.contains("win")) {
            // Prebuilt at project build time; just unpack it from the jar.
            Files.createDirectories(workDir);
            Path exe = workDir.resolve("system-audio-tap.exe");
            try (InputStream in = NativeSystemAudioTap.class
                    .getResourceAsStream("/windows/system-audio-tap.exe")) {
                if (in == null) {
                    reason = "embedded Windows helper missing from the jar";
                    return false;
                }
                try {
                    Files.copy(in, exe, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException overwriteFailure) {
                    if (!Files.exists(exe)) {
                        throw overwriteFailure;
                    }
                    // a running copy has the file locked; reuse it
                }
            }
            helperBinary = exe;
            return true;
        }
        reason = "no native tap for " + os + " (Linux uses the PulseAudio/PipeWire Monitor device instead)";
        return false;
    }

    private interface CommandBuilder {
        List<String> command(Path source);
    }

    private static boolean compile(Path workDir, String resource, String sourceName,
                                   String binaryName, CommandBuilder builder)
            throws IOException, InterruptedException {
        Files.createDirectories(workDir);
        Path source = workDir.resolve(sourceName);
        byte[] embedded;
        try (InputStream in = NativeSystemAudioTap.class.getResourceAsStream(resource)) {
            if (in == null) {
                reason = "helper source " + resource + " missing from the jar";
                return false;
            }
            embedded = in.readAllBytes();
        }
        Path binary = workDir.resolve(binaryName);
        // Recompile only when the embedded source changed or the binary is gone.
        if (!Files.exists(binary) || !Files.exists(source)
                || !Files.readString(source, StandardCharsets.UTF_8)
                        .equals(new String(embedded, StandardCharsets.UTF_8))) {
            Files.write(source, embedded);
            Files.deleteIfExists(binary);
            log.info("Building the native system-audio helper (one time)...");
            Process compile = new ProcessBuilder(builder.command(source))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!compile.waitFor(COMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                compile.destroyForcibly();
                reason = "helper compilation timed out";
                return false;
            }
            if (compile.exitValue() != 0 || !Files.exists(binary)) {
                reason = "helper compilation failed: "
                        + output.lines().limit(5).reduce("", (a, b) -> a + " | " + b);
                return false;
            }
            log.info("Native system-audio helper ready at {}", binary);
        }
        helperBinary = binary;
        return true;
    }

    private static boolean macVersionAtLeast(int wantMajor, int wantMinor) {
        try {
            String[] parts = System.getProperty("os.version", "0.0").split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major > wantMajor || (major == wantMajor && minor >= wantMinor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static Path findOnPath(List<String> names) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            for (String name : names) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

}
