package com.aiassist;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class AiAssistApplication {

    private static final Logger log = LoggerFactory.getLogger(AiAssistApplication.class);

    /**
     * Dedicated local port for the (loopback-only) REST API. Deliberately not
     * 8080, which many other apps grab. Override with {@code -Dai-assist.port=N}.
     */
    private static final int PREFERRED_PORT = 1234;

    public static void main(String[] args) {
        applyMacMenuBarName();
        if (relocateJarIntoDocumentsOnFirstRun(args)) {
            // A fresh process was launched from the new location; this one's
            // done — see the method doc for why it can't just continue here.
            return;
        }
        int port = choosePort();
        // headless(false) lets the app open the desktop window when launched.
        new SpringApplicationBuilder(AiAssistApplication.class)
                .headless(false)
                .properties("server.port=" + port, "server.address=127.0.0.1")
                .run(args);
    }

    /**
     * First run only: a plain downloaded jar usually starts out wherever the
     * browser saved it (typically Downloads), which isn't a stable home —
     * moves it into {@code ~/Documents/ai-assist} instead, the same folder
     * the app already uses for its speech models. Every later launch is a
     * no-op (the jar is already there by then), so this only ever runs once.
     *
     * <p>Returns {@code true} exactly when a replacement process has been
     * launched from the new location and this one should exit immediately
     * without starting Spring at all — continuing in the <em>same</em>
     * process after the move is not safe: Spring Boot's executable jar reads
     * its bundled dependencies (BOOT-INF/lib/*.jar) out of the jar file
     * lazily, well into context startup, not all upfront, so a file moved
     * out from under an already-running instance can break later in a
     * confusing way (confirmed: it surfaced as a missing Bean Validation
     * provider, nothing to do with validation at all).
     *
     * <p>Best-effort and silent on any failure — skipped entirely for an
     * IDE/exploded classpath or a jpackage native launcher (neither is a
     * plain jar to begin with), if a jar already exists at the destination
     * (e.g. a previous version — never overwritten), or if the move or
     * relaunch fails for any reason (a jar can be briefly locked by its own
     * running JVM on Windows) — this process just continues normally from
     * wherever it already is, as if nothing happened.
     */
    private static boolean relocateJarIntoDocumentsOnFirstRun(String[] args) {
        try {
            String classPath = System.getProperty("java.class.path", "");
            if (classPath.isBlank() || classPath.contains(java.io.File.pathSeparator)
                    || !classPath.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                return false;
            }
            java.nio.file.Path jar = java.nio.file.Path.of(classPath).toAbsolutePath();
            if (!java.nio.file.Files.isRegularFile(jar)) {
                return false;
            }
            java.nio.file.Path targetDir = com.aiassist.setup.UserPaths.documents().resolve("ai-assist");
            if (jar.getParent() != null && jar.getParent().equals(targetDir)) {
                return false; // already living there
            }
            java.nio.file.Path target = targetDir.resolve(jar.getFileName());
            if (java.nio.file.Files.exists(target)) {
                return false; // don't overwrite a jar already there
            }
            java.nio.file.Files.createDirectories(targetDir);
            java.nio.file.Files.move(jar, target);
            log.info("Moved {} into {} on first run; relaunching from there", jar, target);
            relaunch(target, args);
            return true;
        } catch (Exception e) {
            log.debug("Could not relocate the jar into Documents/ai-assist ({}); continuing from where it is",
                    e.getMessage());
            return false;
        }
    }

    /** Starts an independent {@code java -jar <jar>} process; this one exits right after. */
    private static void relaunch(java.nio.file.Path jar, String[] args) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        java.nio.file.Path javaExe = java.nio.file.Path.of(
                System.getProperty("java.home", ""), "bin", windows ? "javaw.exe" : "java");
        String javaCommand = java.nio.file.Files.isRegularFile(javaExe)
                ? javaExe.toString() : (windows ? "javaw" : "java");
        var command = new java.util.ArrayList<String>();
        command.add(javaCommand);
        command.add("-jar");
        command.add(jar.toString());
        command.addAll(java.util.List.of(args));
        new ProcessBuilder(command).directory(jar.getParent().toFile()).inheritIO().start();
    }

    /**
     * On macOS, running a plain jar (double-click or {@code java -jar}, the
     * README's primary way to use ai-assist) makes AWT show the generic
     * launcher name ("JAR Launcher" or "java") in the menu bar and Dock,
     * because that is the actual process name — there is no app bundle to
     * read a proper name from. Setting this property before any AWT class
     * loads is what actually controls the menu bar/Dock name in that case, so
     * it must run as the very first thing in main(), before Spring/Swing.
     */
    private static void applyMacMenuBarName() {
        System.setProperty("apple.awt.application.name", "ai-assist");
        // Older AWT versions on macOS read this property instead.
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "ai-assist");
    }

    /**
     * Returns the preferred port when it is free, otherwise 0 so the OS assigns
     * a free ephemeral port. This way ai-assist always starts and never blocks
     * another application that already holds the port.
     */
    private static int choosePort() {
        int preferred = Integer.getInteger("ai-assist.port", PREFERRED_PORT);
        if (isLoopbackPortFree(preferred)) {
            return preferred;
        }
        log.warn("Local API port {} is already in use; using a free port instead so ai-assist "
                + "does not block the other application", preferred);
        return 0; // let the OS pick a free port
    }

    private static boolean isLoopbackPortFree(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException inUse) {
            return false;
        }
    }
}
