package com.aiassist.audio;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.aiassist.config.TranscriptionProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Locates the Vosk speech model on local disk. The app is offline by
 * default: the model must already be present (unzipped from
 * https://alphacephei.com/vosk/models, fetched once at build time via
 * {@code mvn package -Pfetch-model}, or copied from another machine).
 * Runtime download only happens when explicitly enabled with
 * {@code ai-assist.transcription.allow-download=true}.
 */
@Service
public class VoskModelManager {

    private static final Logger log = LoggerFactory.getLogger(VoskModelManager.class);

    private final TranscriptionProperties properties;

    public VoskModelManager(TranscriptionProperties properties) {
        this.properties = properties;
    }

    /** Returns the model directory, never touching the network unless allowed. */
    public synchronized Path ensureModel() throws IOException, InterruptedException {
        // Search local locations: a models/ folder next to the app, then the configured dir.
        Path bundled = Path.of("models", properties.modelName());
        if (isModelPresent(bundled)) {
            return bundled;
        }
        Path modelPath = Path.of(properties.modelDir(), properties.modelName());
        if (isModelPresent(modelPath)) {
            return modelPath;
        }
        if (!properties.allowDownload()) {
            throw new IOException(("Speech model \"%s\" not found (looked in %s and %s) and runtime "
                    + "download is disabled so the app stays offline. Either build with "
                    + "`mvn package -Pfetch-model`, or download %s on another machine, unzip it, and "
                    + "place the folder in one of those locations.")
                    .formatted(properties.modelName(), bundled.toAbsolutePath(), modelPath.toAbsolutePath(),
                            properties.modelUrl()));
        }
        log.info("Vosk model not found at {}; downloading from {}", modelPath, properties.modelUrl());
        Files.createDirectories(modelPath.getParent());
        Path zip = Files.createTempFile("vosk-model", ".zip");
        try {
            download(properties.modelUrl(), zip);
            unzip(zip, modelPath.getParent());
        } finally {
            Files.deleteIfExists(zip);
        }
        if (!isModelPresent(modelPath)) {
            throw new IOException("Model archive did not contain expected directory " + modelPath);
        }
        log.info("Vosk model ready at {}", modelPath);
        return modelPath;
    }

    private boolean isModelPresent(Path modelPath) {
        return Files.isDirectory(modelPath.resolve("am")) || Files.isRegularFile(modelPath.resolve("final.mdl"));
    }

    private void download(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Model download failed with HTTP " + response.statusCode() + " from " + url);
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unzip(Path zip, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir)) {
                    throw new IOException("Zip entry escapes target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
