package com.aiassist.audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import com.sun.jna.Platform;

/**
 * Extracts the Vosk native library (and, on Windows, the MinGW runtime DLLs
 * it imports) from the vosk jar into one directory with their real file
 * names, then loads them in dependency order. Windows resolves a DLL's
 * imports by module name against the directory it was loaded from, so the
 * dependencies must sit next to libvosk.dll under their true names —
 * JNA's own single-file extraction can't guarantee that, which made
 * {@code Native.load("vosk")} fail only on Windows.
 */
final class VoskLoader {

    private static String libraryPath;

    private VoskLoader() {
    }

    /** Returns the absolute path of the ready-to-load Vosk library. */
    static synchronized String ensureLoaded() {
        if (libraryPath != null) {
            return libraryPath;
        }
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "ai-assist", "natives");
            Files.createDirectories(dir);
            String resourcePrefix;
            String libraryName;
            List<String> dependencies = List.of();
            if (Platform.isWindows()) {
                resourcePrefix = "/win32-x86-64/";
                libraryName = "libvosk.dll";
                dependencies = List.of("libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll");
            } else if (Platform.isMac()) {
                resourcePrefix = "/darwin/";
                libraryName = "libvosk.dylib";
            } else {
                resourcePrefix = "/linux-x86-64/";
                libraryName = "libvosk.so";
            }
            for (String dependency : dependencies) {
                System.load(extract(resourcePrefix + dependency, dir.resolve(dependency)).toString());
            }
            libraryPath = extract(resourcePrefix + libraryName, dir.resolve(libraryName)).toString();
            return libraryPath;
        } catch (IOException e) {
            throw new IllegalStateException("Could not unpack the speech library: " + e.getMessage(), e);
        }
    }

    private static Path extract(String resource, Path target) throws IOException {
        try (InputStream in = VoskLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("native resource " + resource + " not found on the classpath");
            }
            try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException overwriteFailure) {
                // A previous or concurrent run has the file mapped (Windows
                // locks loaded DLLs). Reuse it — contents are version-stable.
                if (!Files.exists(target)) {
                    throw overwriteFailure;
                }
            }
        }
        return target.toAbsolutePath();
    }
}
