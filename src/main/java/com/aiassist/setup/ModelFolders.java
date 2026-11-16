package com.aiassist.setup;

import java.nio.file.Path;
import java.util.List;

/**
 * Where the app looks for user-supplied model files (Whisper {@code ggml-*.bin}
 * and local LLM {@code *.gguf}): the installed app's models folder in Documents,
 * then the folders next to the jar and the working directory.
 */
public final class ModelFolders {

    private ModelFolders() {
    }

    /** The folder the app runs from — the jar's directory, or the working dir. */
    public static Path appHome() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.contains(java.io.File.pathSeparator) && classPath.endsWith(".jar")) {
            Path parent = Path.of(classPath).toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of("").toAbsolutePath();
    }

    /** Everywhere a user might reasonably put a model folder. */
    public static List<Path> modelSearchRoots() {
        return List.copyOf(new java.util.LinkedHashSet<>(List.of(
                // The installed app's user-writable models folder (Documents),
                // then the folders next to the jar / working directory.
                UserPaths.documents().resolve("ai-assist").resolve("models"),
                appHome().resolve("models"), appHome(),
                Path.of("models").toAbsolutePath(), Path.of("").toAbsolutePath())));
    }
}
