package com.aiassist.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fixed, user-writable locations the installed app uses, identical on
 * macOS, Windows, and Linux (all three expose a home directory, and a
 * {@code Documents} folder on the desktop platforms). Keeping these under the
 * user's home means they are always writable — unlike the install directory,
 * which needs admin rights on macOS/Windows — and survive reinstalls.
 *
 * <ul>
 *   <li>{@code ~/Documents/meeting-notes} — where the notes files are saved;</li>
 *   <li>{@code ~/Documents/meeting-notes/model-backups} — where a model .zip is
 *       moved after it is unpacked, so the original download is kept;</li>
 *   <li>{@code ~/Documents/ai-assist/models} — where the speech / transcription
 *       / LLM model files are placed.</li>
 * </ul>
 */
public final class UserPaths {

    private static final Logger log = LoggerFactory.getLogger(UserPaths.class);

    private UserPaths() {
    }

    /** The user's home directory. */
    public static Path home() {
        return Path.of(System.getProperty("user.home", "."));
    }

    /**
     * The user's Documents folder — wherever it actually is right now.
     * On Windows this is very often <b>not</b> {@code ~/Documents}: OneDrive's
     * "Known Folder Move" (on by default on many managed/new PCs) relocates it
     * to somewhere under the OneDrive folder instead, in which case the
     * classic path doesn't exist and files written there would be invisible to
     * the user. {@link #windowsKnownFolder} asks Windows itself where the
     * folder currently lives; when that isn't available (not Windows, or the
     * lookup fails), falls back to the classic {@code ~/Documents}, then the
     * home directory.
     */
    public static Path documents() {
        Path known = windowsKnownFolder("Personal");
        if (known != null) {
            return known;
        }
        Path docs = home().resolve("Documents");
        return Files.isDirectory(docs) ? docs : home();
    }

    /**
     * The user's Desktop folder, or null if none can be found. Same
     * OneDrive-redirection caveat as {@link #documents()}.
     */
    public static Path desktop() {
        Path known = windowsKnownFolder("Desktop");
        if (known != null) {
            return known;
        }
        Path classic = home().resolve("Desktop");
        return Files.isDirectory(classic) ? classic : null;
    }

    /**
     * Resolves a Windows "known folder" (Desktop = {@code "Desktop"},
     * Documents = {@code "Personal"}) to wherever it actually lives right now
     * — classic path, OneDrive-redirected, or manually relocated by the user
     * — by reading the registry value Windows itself keeps current. This is
     * the same mechanism Windows Explorer uses, so it reflects reality even
     * when OneDrive has moved the folder. No-op on macOS/Linux, and
     * best-effort: any failure (missing {@code reg} tool, no such value,
     * garbled output) just returns null so the caller falls back to the
     * classic path.
     */
    private static Path windowsKnownFolder(String registryValueName) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                    "/v", registryValueName)
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor();
            for (String line : output.split("\r?\n")) {
                int typeIdx = line.indexOf("REG_");
                if (typeIdx < 0) {
                    continue;
                }
                int valueStart = line.indexOf(' ', typeIdx);
                if (valueStart < 0) {
                    continue;
                }
                Path resolved = Path.of(expandWindowsEnv(line.substring(valueStart).trim()));
                if (Files.isDirectory(resolved)) {
                    return resolved;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read the Windows {} folder location ({}); using the classic path",
                    registryValueName, e.getMessage());
        }
        return null;
    }

    /** Expands {@code %NAME%}-style environment references (e.g. {@code %USERPROFILE%}). */
    private static String expandWindowsEnv(String value) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%') {
                int end = value.indexOf('%', i + 1);
                if (end > i) {
                    String name = value.substring(i + 1, end);
                    String resolved = System.getenv(name);
                    out.append(resolved != null ? resolved : "%" + name + "%");
                    i = end + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** {@code ~/Documents/meeting-notes} — created if missing. */
    public static Path meetingNotesDir() {
        return ensure(documents().resolve("meeting-notes"));
    }

    /** {@code ~/Documents/meeting-notes/model-backups} — created if missing. */
    public static Path modelBackupDir() {
        return ensure(documents().resolve("meeting-notes").resolve("model-backups"));
    }

    /** {@code ~/Documents/ai-assist/models} — created if missing. */
    public static Path modelsDir() {
        return ensure(documents().resolve("ai-assist").resolve("models"));
    }

    /**
     * The app's own config folder ({@code .ai-assist} — API token, first-run
     * marker), created if missing. Kept next to the running jar (or the
     * working directory when not launched via {@code java -jar}) rather than
     * the user's home, so it travels with the app and Uninstall/first-run can
     * find it the same way regardless of OS profile quirks (e.g. a
     * OneDrive-redirected home directory).
     */
    public static Path configDir() {
        return ensure(com.aiassist.audio.VoskModelManager.appHome().resolve(".ai-assist"));
    }

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Could not create {} ({})", dir, e.getMessage());
        }
        return dir;
    }
}
