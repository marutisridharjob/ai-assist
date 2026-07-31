package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small, portable user settings — dark mode, auto-start, and the chosen
 * speech model — persisted as a plain properties file inside the app's own
 * {@code .ai-assist} folder (next to the jar) instead of the JDK's
 * platform-specific Preferences store (Windows registry, macOS plist, or a
 * Linux dotfile outside {@code .ai-assist}). Keeping it here means it
 * travels with the app, is easy to inspect, and Uninstall's single folder
 * delete clears it along with everything else with no separate API needed.
 */
public final class AppSettings {

    private static final String FILE_NAME = "settings.properties";

    private AppSettings() {
    }

    public static boolean darkMode(boolean defaultValue) {
        String value = load().getProperty("darkMode");
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        Boolean legacy = legacyBoolean("com/aiassist/ui", "darkMode");
        if (legacy != null) {
            setDarkMode(legacy);
            return legacy;
        }
        return defaultValue;
    }

    public static void setDarkMode(boolean dark) {
        Properties props = load();
        props.setProperty("darkMode", Boolean.toString(dark));
        save(props);
    }

    public static boolean autoStart(boolean defaultValue) {
        String value = load().getProperty("autoStart");
        if (value != null) {
            return Boolean.parseBoolean(value);
        }
        Boolean legacy = legacyBoolean("com/aiassist/ui", "autoStart");
        if (legacy != null) {
            setAutoStart(legacy);
            return legacy;
        }
        return defaultValue;
    }

    public static void setAutoStart(boolean autoStart) {
        Properties props = load();
        props.setProperty("autoStart", Boolean.toString(autoStart));
        save(props);
    }

    /** The last chosen speech model, or null when none has been chosen yet. */
    public static String modelName() {
        String value = load().getProperty("modelName");
        if (value != null) {
            return value;
        }
        String legacy = legacyString("com/aiassist/audio", "model");
        if (legacy != null) {
            setModelName(legacy);
        }
        return legacy;
    }

    public static void setModelName(String name) {
        Properties props = load();
        props.setProperty("modelName", name);
        save(props);
    }

    /**
     * Which engine produces the saved meeting notes' transcript: {@code
     * "whisper"} (accurate, the default — re-transcribes the recording with
     * Whisper) or {@code "vosk"} (use the live captions as-is, skipping
     * Whisper entirely, for speed).
     */
    public static String transcriptionEngine(String defaultValue) {
        String value = load().getProperty("transcriptionEngine");
        return value != null ? value : defaultValue;
    }

    public static void setTranscriptionEngine(String engine) {
        Properties props = load();
        props.setProperty("transcriptionEngine", engine);
        save(props);
    }

    /**
     * A one-time read of a value from the JDK Preferences store this setting
     * used before it moved into {@code settings.properties} — so upgrading
     * to a settings file never silently resets a choice the user already
     * made (e.g. turning auto-start off) back to its default. Once read, the
     * caller immediately persists it into the file, so this path is only
     * ever taken once per setting.
     */
    private static Boolean legacyBoolean(String legacyPackagePath, String key) {
        String raw = legacyString(legacyPackagePath, key);
        return raw == null ? null : Boolean.valueOf(raw);
    }

    private static String legacyString(String legacyPackagePath, String key) {
        try {
            return java.util.prefs.Preferences.userRoot().node(legacyPackagePath).get(key, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path file() {
        return UserPaths.configDir().resolve(FILE_NAME);
    }

    private static Properties load() {
        Properties props = new Properties();
        Path file = file();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newInputStream(file)) {
                props.load(in);
            } catch (IOException ignored) {
                // start blank if the file is missing or unreadable
            }
        }
        return props;
    }

    private static void save(Properties props) {
        try (var out = Files.newOutputStream(file())) {
            props.store(out, "ai-assist user settings");
        } catch (IOException ignored) {
            // best-effort; the in-memory value still applies for this run
        }
    }
}
