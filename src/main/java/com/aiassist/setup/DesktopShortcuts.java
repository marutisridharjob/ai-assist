package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Best-effort creation of Desktop shortcuts on first run — one for the app and
 * one for the meeting-notes folder — done once and remembered with a marker
 * file. Shortcuts are inherently OS-specific, so each platform is handled with
 * only the JDK and the OS's own tools (no third-party libraries); anything that
 * cannot be created is skipped silently so it never affects startup. Written to
 * degrade gracefully on future OS versions.
 */
public final class DesktopShortcuts {

    private static final Logger log = LoggerFactory.getLogger(DesktopShortcuts.class);

    private DesktopShortcuts() {
    }

    /** Creates the shortcuts once; subsequent runs do nothing. */
    public static void createOnceOnFirstRun() {
        Path marker = UserPaths.home().resolve(".ai-assist").resolve(".shortcuts-created");
        try {
            if (Files.exists(marker)) {
                return;
            }
        } catch (Exception ignored) {
            // fall through and attempt creation
        }
        try {
            Path desktop = desktopDir();
            if (desktop != null) {
                shortcutToFolder(desktop, "ai-assist meeting-notes", UserPaths.meetingNotesDir());
                shortcutToApp(desktop);
            }
        } catch (Throwable t) {
            log.info("Could not create desktop shortcuts ({}); skipping", t.getMessage());
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "created\n");
        } catch (IOException ignored) {
            // if we cannot persist the marker we simply try again next launch
        }
    }

    /** The user's Desktop folder, or null when there isn't one. */
    private static Path desktopDir() {
        Path desktop = UserPaths.home().resolve("Desktop");
        return Files.isDirectory(desktop) ? desktop : null;
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
                createWindowsLnk(desktop.resolve(name + ".lnk"), target, true);
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

    /** A Desktop shortcut that launches the installed app, where one can be located. */
    private static void shortcutToApp(Path desktop) {
        try {
            if (isMac()) {
                Path app = Path.of("/Applications/ai-assist.app");
                Path link = desktop.resolve("ai-assist");
                if (Files.isDirectory(app) && !Files.exists(link)) {
                    Files.createSymbolicLink(link, app);
                }
            } else if (isWindows()) {
                Path exe = windowsAppExe();
                if (exe != null) {
                    createWindowsLnk(desktop.resolve("ai-assist.lnk"), exe, false);
                }
                // Otherwise the installer's own --win-shortcut already placed one.
            } else {
                // Linux: copy the .desktop launcher the installer registered, if present.
                Path menuEntry = linuxDesktopEntry();
                if (menuEntry != null) {
                    Path link = desktop.resolve("ai-assist.desktop");
                    if (!Files.exists(link)) {
                        Files.copy(menuEntry, link);
                        link.toFile().setExecutable(true);
                    }
                }
            }
        } catch (Exception e) {
            log.info("Skipped app shortcut ({})", e.getMessage());
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
     */
    private static void createWindowsLnk(Path lnk, Path target, boolean isFolder) throws IOException, InterruptedException {
        if (Files.exists(lnk)) {
            return;
        }
        String vbs = "Set s = CreateObject(\"WScript.Shell\")\r\n"
                + "Set lnk = s.CreateShortcut(\"" + lnk.toAbsolutePath() + "\")\r\n"
                + "lnk.TargetPath = \"" + target.toAbsolutePath() + "\"\r\n"
                + (isFolder ? "" : "lnk.WorkingDirectory = \"" + target.toAbsolutePath().getParent() + "\"\r\n")
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
}
