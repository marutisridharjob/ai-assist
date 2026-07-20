package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * The user's Documents folder when it exists, otherwise the home directory.
     * (Documents is standard on macOS and Windows; many Linux setups have it
     * too, but not all.)
     */
    public static Path documents() {
        Path docs = home().resolve("Documents");
        return Files.isDirectory(docs) ? docs : home();
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

    private static Path ensure(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Could not create {} ({})", dir, e.getMessage());
        }
        return dir;
    }
}
