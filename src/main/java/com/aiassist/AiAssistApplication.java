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
        int port = choosePort();
        // headless(false) lets the app open the desktop window when launched.
        new SpringApplicationBuilder(AiAssistApplication.class)
                .headless(false)
                .properties("server.port=" + port, "server.address=127.0.0.1")
                .run(args);
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
