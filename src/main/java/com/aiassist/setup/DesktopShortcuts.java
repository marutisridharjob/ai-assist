package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort creation of Desktop shortcuts — one for the app and one for the
 * minutes-of-meeting folder. Shortcuts are inherently OS-specific, so each
 * platform is handled with only the JDK and the OS's own tools (no
 * third-party libraries); anything that cannot be created is skipped silently
 * so it never affects startup. Written to degrade gracefully on future OS
 * versions.
 */
public final class DesktopShortcuts {

    private static final Logger log = LoggerFactory.getLogger(DesktopShortcuts.class);

    private DesktopShortcuts() {
    }

    /**
     * Creates the Desktop shortcuts if they are not already there. Each
     * individual shortcut creator below already checks for its own target
     * file before writing, so calling this on every launch is always a cheap
     * no-op once the shortcuts exist — deliberately not gated behind a
     * separate "already ran once" marker file, since that marker could get
     * written even when a transient failure (Desktop not yet mounted, a
     * permissions hiccup) meant nothing was actually created, which would
     * have permanently hidden the shortcuts from that user. Calling this
     * every launch instead makes it self-healing.
     */
    public static void ensureShortcuts() {
        try {
            Path desktop = desktopDir();
            if (desktop != null) {
                shortcutToFolder(desktop, "ai-assist minutes-of-meeting", UserPaths.meetingNotesDir());
                shortcutToApp(desktop);
            }
        } catch (Throwable t) {
            log.info("Could not create desktop shortcuts ({}); skipping", t.getMessage());
        }
    }

    /**
     * The user's Desktop folder, or null when there isn't one. Resolved via
     * {@link UserPaths#desktop()}, which finds the real current location even
     * when OneDrive (or a manual relocation) has moved it off the classic
     * {@code ~/Desktop} path — otherwise the shortcut would silently never
     * get created.
     */
    private static Path desktopDir() {
        return UserPaths.desktop();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /** A Desktop shortcut that opens a folder. */
    private static void shortcutToFolder(Path desktop, String name, Path target) {
        try {
            if (isWindows()) {
                createWindowsLnk(desktop.resolve(name + ".lnk"), target, null, null);
            } else {
                // A symlink shows up as a normal folder shortcut in Finder / file managers.
                Path link = desktop.resolve(name);
                if (!Files.exists(link)) {
                    Files.createSymbolicLink(link, target);
                }
            }
        } catch (Exception e) {
            log.info("Skipped folder shortcut '{}' ({})", name, e.getMessage());
        }
    }

    /**
     * A Desktop shortcut that launches ai-assist. Prefers a natively installed
     * app/exe/.desktop entry when one is found (from a jpackage install); most
     * users instead run the single downloaded jar directly (see the README),
     * in which case that native launcher never exists, so a shortcut that
     * runs {@code java -jar <this jar>} is created instead — otherwise those
     * users never get a Desktop shortcut at all.
     */
    private static void shortcutToApp(Path desktop) {
        try {
            if (isMac()) {
                Path app = Path.of("/Applications/ai-assist.app");
                Path link = desktop.resolve("ai-assist");
                if (Files.isDirectory(app) && !Files.exists(link)) {
                    Files.createSymbolicLink(link, app);
                    return;
                }
                currentJarPath().ifPresent(jar -> createMacJarLauncher(desktop, jar));
            } else if (isWindows()) {
                Path exe = windowsAppExe();
                if (exe != null) {
                    createWindowsLnk(desktop.resolve("ai-assist.lnk"), exe, null, exe.getParent());
                    return;
                }
                // Otherwise the installer's own --win-shortcut already placed one
                // when installed natively; running the plain jar needs its own.
                currentJarPath().ifPresent(jar -> createWindowsJarLauncher(desktop, jar));
            } else {
                // Linux: copy the .desktop launcher the installer registered, if present.
                Path menuEntry = linuxDesktopEntry();
                if (menuEntry != null) {
                    Path link = desktop.resolve("ai-assist.desktop");
                    if (!Files.exists(link)) {
                        Files.copy(menuEntry, link);
                        link.toFile().setExecutable(true);
                    }
                    return;
                }
                currentJarPath().ifPresent(jar -> createLinuxJarLauncher(desktop, jar));
            }
        } catch (Exception e) {
            log.info("Skipped app shortcut ({})", e.getMessage());
        }
    }

    /**
     * The jar currently running, when launched the ordinary way ({@code java
     * -jar ai-assist.jar} or a double-click, both a single-entry classpath
     * ending in {@code .jar}). Empty for an IDE/exploded classpath or a
     * jpackage native launcher, neither of which needs this fallback.
     */
    private static java.util.Optional<Path> currentJarPath() {
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank() || classPath.contains(java.io.File.pathSeparator)
                || !classPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return java.util.Optional.empty();
        }
        Path jar = Path.of(classPath).toAbsolutePath();
        return Files.isRegularFile(jar) ? java.util.Optional.of(jar) : java.util.Optional.empty();
    }

    /** Windows: a .lnk that runs {@code javaw -jar <jar>} (no console window). */
    private static void createWindowsJarLauncher(Path desktop, Path jar) {
        try {
            Path javaw = Path.of(System.getProperty("java.home", ""), "bin", "javaw.exe");
            Path javaExe = Files.isRegularFile(javaw) ? javaw : Path.of("javaw.exe");
            createWindowsLnk(desktop.resolve("ai-assist.lnk"), javaExe,
                    "-jar \"" + jar + "\"", jar.getParent());
        } catch (Exception e) {
            log.info("Skipped jar-launcher shortcut ({})", e.getMessage());
        }
    }

    /** macOS: a double-clickable .command script that runs {@code java -jar <jar>}. */
    private static void createMacJarLauncher(Path desktop, Path jar) {
        Path link = desktop.resolve("ai-assist.command");
        try {
            if (Files.exists(link)) {
                return;
            }
            Files.writeString(link, "#!/bin/bash\ncd \"" + jar.getParent() + "\"\n"
                    + "exec java -jar \"" + jar.getFileName() + "\"\n");
            link.toFile().setExecutable(true);
        } catch (Exception e) {
            log.info("Skipped jar-launcher shortcut ({})", e.getMessage());
        }
    }

    /** Linux: a standard .desktop entry that runs {@code java -jar <jar>}. */
    private static void createLinuxJarLauncher(Path desktop, Path jar) {
        Path link = desktop.resolve("ai-assist.desktop");
        try {
            if (Files.exists(link)) {
                return;
            }
            Files.writeString(link, "[Desktop Entry]\n"
                    + "Type=Application\n"
                    + "Name=ai-assist\n"
                    + "Comment=Offline meeting notes assistant\n"
                    + "Exec=java -jar \"" + jar + "\"\n"
                    + "Path=" + jar.getParent() + "\n"
                    + "Terminal=false\n"
                    + "Categories=Office;\n");
            link.toFile().setExecutable(true);
        } catch (Exception e) {
            log.info("Skipped jar-launcher shortcut ({})", e.getMessage());
        }
    }

    /** Locates the jpackage-installed launcher on Windows, if present. */
    private static Path windowsAppExe() {
        for (String base : new String[] {
                System.getenv("LOCALAPPDATA"), System.getenv("ProgramFiles"),
                System.getenv("ProgramFiles(x86)")}) {
            if (base == null || base.isBlank()) {
                continue;
            }
            Path exe = Path.of(base, "ai-assist", "ai-assist.exe");
            if (Files.isRegularFile(exe)) {
                return exe;
            }
        }
        return null;
    }

    /** Locates the installed .desktop menu entry on Linux, if present. */
    private static Path linuxDesktopEntry() {
        for (Path dir : new Path[] {
                Path.of("/opt/ai-assist/lib/ai-assist-ai-assist.desktop"),
                UserPaths.home().resolve(".local/share/applications/ai-assist-ai-assist.desktop"),
                Path.of("/usr/share/applications/ai-assist-ai-assist.desktop")}) {
            if (Files.isRegularFile(dir)) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Creates a Windows .lnk shortcut via the built-in WScript.Shell (no extra
     * tools). Uses a temporary VBScript so it works on any Windows version.
     * {@code arguments} and {@code workingDir} may be null when not needed.
     */
    private static void createWindowsLnk(Path lnk, Path target, String arguments, Path workingDir)
            throws IOException, InterruptedException {
        if (Files.exists(lnk)) {
            return;
        }
        String vbs = "Set s = CreateObject(\"WScript.Shell\")\r\n"
                + "Set lnk = s.CreateShortcut(\"" + vbsEscape(lnk.toAbsolutePath().toString()) + "\")\r\n"
                + "lnk.TargetPath = \"" + vbsEscape(target.toAbsolutePath().toString()) + "\"\r\n"
                + (arguments != null ? "lnk.Arguments = \"" + vbsEscape(arguments) + "\"\r\n" : "")
                + (workingDir != null
                        ? "lnk.WorkingDirectory = \"" + vbsEscape(workingDir.toAbsolutePath().toString()) + "\"\r\n"
                        : "")
                + "lnk.Save\r\n";
        Path script = Files.createTempFile("ai-assist-lnk", ".vbs");
        try {
            Files.writeString(script, vbs);
            Process p = new ProcessBuilder("wscript", "//nologo", script.toString())
                    .redirectErrorStream(true).start();
            p.waitFor();
        } finally {
            Files.deleteIfExists(script);
        }
    }

    /** Doubles double-quotes for embedding a value inside a VBScript string literal. */
    private static String vbsEscape(String value) {
        return value.replace("\"", "\"\"");
    }
}
