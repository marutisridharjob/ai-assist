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
 * Locates the offline English speech model. Resolution order, all local:
 * an unpacked model on disk ({@code ./models} or the configured directory),
 * then the copy embedded inside the application jar at build time
 * ({@code mvn package -Pfetch-model}), which is extracted once to the model
 * directory. The app never touches the network unless
 * {@code ai-assist.transcription.allow-download=true} is explicitly set.
 */
@Service
public class VoskModelManager {

    private static final Logger log = LoggerFactory.getLogger(VoskModelManager.class);
    private static final String EMBEDDED_MODEL_RESOURCE = "/vosk-model.zip";

    private final TranscriptionProperties properties;

    public VoskModelManager(TranscriptionProperties properties) {
        this.properties = properties;
        // Users drop downloaded model .zips next to the jar; unpack them
        // into ./models in the background so they appear in the dropdown.
        Thread unzipper = new Thread(this::unpackDroppedZips, "model-unzip");
        unzipper.setDaemon(true);
        unzipper.start();
    }

    /** Directory containing the running jar (falls back to the working dir). */
    private static Path appHome() {
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.contains(java.io.File.pathSeparator) && classPath.endsWith(".jar")) {
            Path parent = Path.of(classPath).toAbsolutePath().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of("").toAbsolutePath();
    }

    /** Everywhere a user might reasonably put a model folder. */
    private static java.util.List<Path> modelRoots() {
        return java.util.List.copyOf(new java.util.LinkedHashSet<>(java.util.List.of(
                appHome().resolve("models"), appHome(),
                Path.of("models").toAbsolutePath(), Path.of("").toAbsolutePath())));
    }

    private void unpackDroppedZips() {
        Path target = appHome().resolve("models");
        for (Path root : modelRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.list(root)) {
                for (Path zip : files.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.startsWith("vosk-model") && n.endsWith(".zip");
                }).toList()) {
                    String dirName = zip.getFileName().toString();
                    dirName = dirName.substring(0, dirName.length() - 4);
                    boolean alreadyUnpacked = false;
                    for (Path candidate : modelRoots()) {
                        if (isModelPresent(candidate.resolve(dirName))) {
                            alreadyUnpacked = true;
                            break;
                        }
                    }
                    if (alreadyUnpacked) {
                        continue;
                    }
                    log.info("Unpacking dropped model {} into {} (large models take a minute)...", zip, target);
                    Files.createDirectories(target);
                    try (InputStream in = Files.newInputStream(zip)) {
                        unzip(in, target);
                    }
                    log.info("Model {} ready; reopen the model dropdown to pick it", dirName);
                }
            } catch (IOException e) {
                log.warn("Could not unpack dropped model zips in {}: {}", root, e.getMessage());
            }
        }
    }

    /**
     * Models the user can pick from: the built-in default plus any unpacked
     * Vosk model folder found in ./models next to the app.
     */
    public java.util.List<String> listAvailableModels() {
        var names = new java.util.LinkedHashSet<String>();
        names.add(properties.modelName());
        for (Path root : modelRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var dirs = Files.list(root)) {
                dirs.filter(this::isModelPresent)
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(names::add);
            } catch (IOException ignored) {
                // listing is best-effort
            }
        }
        return java.util.List.copyOf(names);
    }

    /** Resolves a user-picked alternative model from local folders only. */
    public synchronized Path ensureModel(String name) throws IOException, InterruptedException {
        if (name == null || name.isBlank() || name.equals(properties.modelName())) {
            return ensureModel();
        }
        for (Path root : modelRoots()) {
            Path candidate = root.resolve(name);
            if (isModelPresent(candidate)) {
                return candidate;
            }
        }
        Path configured = Path.of(properties.modelDir(), name);
        if (isModelPresent(configured)) {
            return configured;
        }
        throw new IOException("Model \"" + name + "\" not found. Place its folder (or .zip) next to "
                + "the ai-assist jar and pick it again.");
    }

    /** Returns the model directory, never touching the network unless allowed. */
    public synchronized Path ensureModel() throws IOException, InterruptedException {
        Path bundled = Path.of("models", properties.modelName());
        if (isModelPresent(bundled)) {
            return bundled;
        }
        Path modelPath = Path.of(properties.modelDir(), properties.modelName());
        if (isModelPresent(modelPath)) {
            return modelPath;
        }

        // First run of a self-contained build: extract the model shipped
        // inside the jar to the model directory.
        try (InputStream embedded = embeddedModelZip()) {
            if (embedded != null) {
                log.info("Extracting embedded speech model to {}", modelPath);
                Files.createDirectories(modelPath.getParent());
                unzip(embedded, modelPath.getParent());
                if (isModelPresent(modelPath)) {
                    return modelPath;
                }
                log.warn("Embedded model archive did not contain {}", properties.modelName());
            }
        }

        if (!properties.allowDownload()) {
            throw new IOException(("Speech model \"%s\" not found (looked in %s, %s, and inside the app) "
                    + "and runtime download is disabled so the app stays offline. Rebuild with "
                    + "`mvn package -Pfetch-model` to embed the model, or download %s on another machine, "
                    + "unzip it, and place the folder in one of those locations.")
                    .formatted(properties.modelName(), bundled.toAbsolutePath(), modelPath.toAbsolutePath(),
                            properties.modelUrl()));
        }
        log.info("Vosk model not found at {}; downloading from {}", modelPath, properties.modelUrl());
        Files.createDirectories(modelPath.getParent());
        Path zip = Files.createTempFile("vosk-model", ".zip");
        try {
            download(properties.modelUrl(), zip);
            try (InputStream in = Files.newInputStream(zip)) {
                unzip(in, modelPath.getParent());
            }
        } finally {
            Files.deleteIfExists(zip);
        }
        if (!isModelPresent(modelPath)) {
            throw new IOException("Model archive did not contain expected directory " + modelPath);
        }
        log.info("Vosk model ready at {}", modelPath);
        return modelPath;
    }

    /** Overridable for tests; returns null when no model is embedded in the jar. */
    protected InputStream embeddedModelZip() {
        return getClass().getResourceAsStream(EMBEDDED_MODEL_RESOURCE);
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

    private void unzip(InputStream zipStream, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
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
