package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feedback email settings. By default the app delivers the feedback message
 * directly to the recipient's mail server over SMTP — no desktop mail app and
 * no account in the middle. Optionally point it at an SMTP relay (host + port,
 * with credentials when the relay requires them) for reliable delivery on
 * networks that block direct outbound mail.
 */
@ConfigurationProperties(prefix = "ai-assist.feedback")
public record FeedbackProperties(String from, String to, String relayHost, int relayPort,
                                 String username, String password, Boolean startTls) {

    public FeedbackProperties {
        if (from == null || from.isBlank()) {
            from = "noreply@ai-assist.com";
        }
        if (to == null || to.isBlank()) {
            to = "marutisridhar.job@gmail.com";
        }
        if (relayHost == null) {
            relayHost = "";
        }
        if (relayPort <= 0) {
            // Submission port when a relay is set; direct-to-server uses 25 regardless.
            relayPort = 587;
        }
        if (startTls == null) {
            startTls = Boolean.TRUE;
        }
    }

    /** True when an SMTP relay is configured; otherwise mail goes straight to the recipient's server. */
    public boolean hasRelay() {
        return relayHost != null && !relayHost.isBlank();
    }
}
