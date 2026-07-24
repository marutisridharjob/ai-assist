package com.aiassist.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feedback email settings. By default this routes through Gmail's SMTP relay
 * (requires a Gmail {@code username}/App Password to actually send — see
 * README "Configuration"), since delivering unauthenticated straight to the
 * recipient's mail server rarely arrives in practice: most networks block
 * outbound port 25, and even when it connects, Gmail's spam filtering
 * typically accepts the message and then silently drops or spam-folders it —
 * a failure with no way to detect from the sending side. Clearing
 * {@code relay-host} reverts to that direct-delivery behavior.
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
