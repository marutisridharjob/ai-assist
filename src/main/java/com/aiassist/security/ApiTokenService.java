package com.aiassist.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

import com.aiassist.config.SecurityProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the local API bearer token. The token is a 256-bit random secret,
 * generated once and stored in a user-private file (owner read/write only on
 * POSIX systems) under the user's home directory, so the same value survives
 * restarts and can be read by any local client the user trusts. A fixed token
 * from configuration takes precedence, which is handy for tests and scripted
 * use. The raw token is never logged.
 */
@Service
public class ApiTokenService {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityProperties props;
    private volatile String token;

    public ApiTokenService(SecurityProperties props) {
        this.props = props;
    }

    /** The active token, resolved (and persisted if freshly generated) on first use. */
    public synchronized String token() {
        if (token != null) {
            return token;
        }
        if (props.apiToken() != null && !props.apiToken().isBlank()) {
            token = props.apiToken().strip();
            return token;
        }
        token = loadOrCreate();
        return token;
    }

    /** Constant-time comparison of a presented token against the active one. */
    public boolean matches(String presented) {
        if (presented == null || presented.isBlank()) {
            return false;
        }
        byte[] expected = token().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String loadOrCreate() {
        Path file = tokenFile();
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!existing.isBlank()) {
                    log.info("Loaded local API token from {}", file);
                    return existing;
                }
            }
        } catch (IOException e) {
            log.warn("Could not read the API token file {} ({}); generating a new one", file, e.getMessage());
        }
        String fresh = generateToken();
        persist(file, fresh);
        return fresh;
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void persist(Path file, String value) {
        try {
            Files.createDirectories(file.getParent());
            restrictDirectory(file.getParent());
            Files.writeString(file, value + System.lineSeparator(), StandardCharsets.UTF_8);
            restrictFile(file);
            log.info("Generated a new local API token and stored it (owner-only) at {}", file);
        } catch (IOException e) {
            // Non-fatal: keep the in-memory token for this run so the app still works.
            log.warn("Could not persist the API token to {} ({}); it will be regenerated next start",
                    file, e.getMessage());
        }
    }

    private Path tokenFile() {
        if (props.tokenFile() != null && !props.tokenFile().isBlank()) {
            return Path.of(props.tokenFile().strip());
        }
        return Path.of(System.getProperty("user.home", "."), ".ai-assist", "api-token");
    }

    /** Owner-only file permissions on POSIX; best-effort (ignored) elsewhere. */
    private static void restrictFile(Path file) {
        Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        trySetPosix(file, perms);
    }

    private static void restrictDirectory(Path dir) {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------");
        trySetPosix(dir, perms);
    }

    private static void trySetPosix(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem (e.g. Windows) — the user-home location is the protection there.
        }
    }
}
