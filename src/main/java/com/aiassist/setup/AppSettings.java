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
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public static void setDarkMode(boolean dark) {
        Properties props = load();
        props.setProperty("darkMode", Boolean.toString(dark));
        save(props);
    }

    public static boolean autoStart(boolean defaultValue) {
        String value = load().getProperty("autoStart");
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    public static void setAutoStart(boolean autoStart) {
        Properties props = load();
        props.setProperty("autoStart", Boolean.toString(autoStart));
        save(props);
    }

    /** The last chosen speech model, or null when none has been chosen yet. */
    public static String modelName() {
        return load().getProperty("modelName");
    }

    public static void setModelName(String name) {
        Properties props = load();
        props.setProperty("modelName", name);
        save(props);
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
