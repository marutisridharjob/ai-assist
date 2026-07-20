package com.aiassist.feedback;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.aiassist.config.FeedbackProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sends the feedback message straight over SMTP from the app itself — no
 * desktop mail client is opened and, unless a relay is configured, no account
 * sits in the middle: the message is delivered directly to the recipient
 * domain's mail server (looked up via its MX records) on port 25, upgrading to
 * TLS with STARTTLS when the server offers it. This uses only the JDK (raw
 * sockets, JNDI DNS, the built-in TLS stack) — no third-party mail library.
 *
 * <p>Direct delivery depends on outbound port 25 being open and the receiving
 * server accepting unauthenticated mail from this machine; many home and
 * corporate networks block one or both. Set {@code ai-assist.feedback.relay-host}
 * (and, if needed, a username/password) to route through an SMTP relay instead.
 */
@Service
public class FeedbackMailSender {

    private static final Logger log = LoggerFactory.getLogger(FeedbackMailSender.class);

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final FeedbackProperties props;

    public FeedbackMailSender(FeedbackProperties props) {
        this.props = props;
    }

    /**
     * Sends the feedback email. Throws {@link IOException} if the message could
     * not be handed off to a mail server, so the caller can report failure.
     */
    public void send(String subject, String body) throws IOException {
        String message = buildMessage(subject, body);
        if (props.hasRelay()) {
            deliver(props.relayHost(), props.relayPort(), message);
            return;
        }
        List<String> mxHosts = lookupMailServers(recipientDomain());
        if (mxHosts.isEmpty()) {
            throw new IOException("no mail server (MX) found for " + recipientDomain());
        }
        IOException last = null;
        for (String host : mxHosts) {
            try {
                deliver(host, 25, message);
                return;
            } catch (IOException e) {
                log.warn("Direct mail delivery via {} failed: {}", host, e.getMessage());
                last = e;
            }
        }
        throw last != null ? last : new IOException("could not deliver to any mail server");
    }

    private String recipientDomain() {
        String to = props.to();
        int at = to.lastIndexOf('@');
        return at >= 0 ? to.substring(at + 1) : to;
    }

    /** Resolves a domain's mail servers (MX), lowest preference first, via JNDI DNS. */
    private List<String> lookupMailServers(String domain) {
        List<String> hosts = new ArrayList<>();
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[] {"MX"});
            Attribute mx = attrs.get("MX");
            ctx.close();
            record Ranked(int pref, String host) {
            }
            List<Ranked> ranked = new ArrayList<>();
            if (mx != null) {
                for (int i = 0; i < mx.size(); i++) {
                    // Each value looks like "10 mail.example.com."
                    String[] parts = mx.get(i).toString().trim().split("\\s+");
                    String host = parts[parts.length - 1].replaceAll("\\.$", "");
                    int pref = parts.length > 1 ? parseIntSafe(parts[0]) : 0;
                    if (!host.isBlank()) {
                        ranked.add(new Ranked(pref, host));
                    }
                }
            }
            ranked.sort(Comparator.comparingInt(Ranked::pref));
            ranked.forEach(r -> hosts.add(r.host()));
        } catch (Exception e) {
            log.warn("MX lookup for {} failed: {}", domain, e.getMessage());
        }
        return hosts;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Runs the SMTP conversation to hand the message to one server. */
    private void deliver(String host, int port, String message) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(READ_TIMEOUT_MS);
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();
            expect(in, 220);

            String ehloHost = localHostName();
            List<String> caps = ehlo(in, out, ehloHost);

            boolean secure = false;
            if (advertises(caps, "STARTTLS")) {
                send(out, "STARTTLS");
                expect(in, 220);
                socket = upgradeToTls(socket, host, port);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = socket.getOutputStream();
                caps = ehlo(in, out, ehloHost);
                secure = true;
            }

            if (props.hasRelay() && props.username() != null && !props.username().isBlank()) {
                if (!secure) {
                    // Never put credentials on the wire in the clear.
                    throw new IOException("relay requires authentication but did not offer STARTTLS; "
                            + "refusing to send credentials over an unencrypted connection");
                }
                authLogin(in, out);
            }

            send(out, "MAIL FROM:<" + address(props.from()) + ">");
            expect(in, 250);
            send(out, "RCPT TO:<" + address(props.to()) + ">");
            expect(in, 250, 251);
            send(out, "DATA");
            expect(in, 354);
            out.write(dotStuff(message).getBytes(StandardCharsets.UTF_8));
            out.write("\r\n.\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(in, 250);
            try {
                send(out, "QUIT");
            } catch (IOException ignored) {
                // the message is already accepted; a failed QUIT is harmless
            }
            log.info("Feedback email delivered to {} via {}", props.to(), host);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing best-effort
            }
        }
    }

    private List<String> ehlo(BufferedReader in, OutputStream out, String ehloHost) throws IOException {
        send(out, "EHLO " + ehloHost);
        return readCapabilities(in);
    }

    private void authLogin(BufferedReader in, OutputStream out) throws IOException {
        send(out, "AUTH LOGIN");
        expect(in, 334);
        send(out, base64(props.username()));
        expect(in, 334);
        send(out, base64(props.password() == null ? "" : props.password()));
        expect(in, 235);
    }

    private SSLSocket upgradeToTls(Socket socket, String host, int port) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(socket, host, port, true);
        ssl.setUseClientMode(true);
        ssl.setSoTimeout(READ_TIMEOUT_MS);
        // Verify the server certificate matches the host we connected to, so a
        // network attacker cannot intercept the STARTTLS session (and, on the
        // relay path, steal the credentials sent after it).
        javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }

    private String buildMessage(String subject, String body) {
        String date = ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME);
        String messageId = "<" + UUID.randomUUID() + "@" + senderDomain() + ">";
        String safeBody = body == null ? "" : body.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n");
        return "From: ai-assist <" + address(props.from()) + ">\r\n"
                + "To: <" + address(props.to()) + ">\r\n"
                + "Subject: " + sanitizeHeader(subject) + "\r\n"
                + "Date: " + date + "\r\n"
                + "Message-ID: " + messageId + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n"
                + safeBody;
    }

    private String senderDomain() {
        String from = props.from();
        int at = from.lastIndexOf('@');
        return at >= 0 ? from.substring(at + 1) : "ai-assist.com";
    }

    /** Escapes lines that begin with '.' so SMTP doesn't read them as end-of-data. */
    private static String dotStuff(String message) {
        return message.replace("\r\n.", "\r\n..");
    }

    private static String address(String value) {
        return sanitizeHeader(value == null ? "" : value).trim();
    }

    /** Strips CR/LF from a header value to prevent header injection. */
    private static String sanitizeHeader(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ");
    }

    private static String base64(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean advertises(List<String> caps, String keyword) {
        return caps.stream().anyMatch(c -> c.toUpperCase(Locale.ROOT).startsWith(keyword));
    }

    private static String localHostName() {
        try {
            String name = java.net.InetAddress.getLocalHost().getCanonicalHostName();
            return (name == null || name.isBlank()) ? "localhost" : name;
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static void send(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Reads a (possibly multi-line) reply and returns its lines' text after the code. */
    private static List<String> readReply(BufferedReader in) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        int code = -1;
        boolean more = true;
        while (more && (line = in.readLine()) != null) {
            if (line.length() < 3) {
                throw new IOException("malformed SMTP reply: " + line);
            }
            code = parseIntSafe(line.substring(0, 3));
            // A hyphen after the code marks a continuation line.
            more = line.length() > 3 && line.charAt(3) == '-';
            lines.add(line.length() > 4 ? line.substring(4) : "");
        }
        if (code == -1) {
            throw new IOException("no SMTP reply from server");
        }
        lines.add(0, String.valueOf(code));
        return lines;
    }

    private static List<String> readCapabilities(BufferedReader in) throws IOException {
        List<String> reply = readReply(in);
        int code = parseIntSafe(reply.get(0));
        if (code != 250) {
            throw new IOException("EHLO refused: " + String.join(" ", reply));
        }
        return reply.subList(1, reply.size());
    }

    private static void expect(BufferedReader in, int... acceptable) throws IOException {
        List<String> reply = readReply(in);
        int code = parseIntSafe(reply.get(0));
        for (int ok : acceptable) {
            if (code == ok) {
                return;
            }
        }
        throw new IOException("SMTP server replied " + code + ": " + String.join(" ", reply.subList(1, reply.size())));
    }
}
