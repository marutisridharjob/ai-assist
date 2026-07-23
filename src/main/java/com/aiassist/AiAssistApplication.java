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
        int port = choosePort();
        // headless(false) lets the app open the desktop window when launched.
        new SpringApplicationBuilder(AiAssistApplication.class)
                .headless(false)
                .properties("server.port=" + port, "server.address=127.0.0.1")
                .run(args);
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
