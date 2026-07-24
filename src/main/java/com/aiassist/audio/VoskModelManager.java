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
 * Locates the offline English speech model. To keep the download small the
 * shipped app bundles <b>no</b> model — the user places it themselves (see the
 * Settings tab &rarr; Instructions). Resolution order, all local: an unpacked
 * model on disk ({@code ./models}, the user's Documents models folder, or the
 * configured directory). A developer build may opt to embed a model with
 * {@code mvn package -Pfetch-model}; when present it is extracted once, but the
 * released app never ships one. The app never touches the network unless
 * {@code ai-assist.transcription.allow-download=true} is explicitly set.
 */
@Service
public class VoskModelManager {

    private static final Logger log = LoggerFactory.getLogger(VoskModelManager.class);
    private static final String EMBEDDED_MODEL_RESOURCE = "/vosk-model.zip";

    private final TranscriptionProperties properties;
    private final java.util.Set<String> unpacking = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean scanning =
            new java.util.concurrent.atomic.AtomicBoolean();

    public VoskModelManager(TranscriptionProperties properties) {
        this.properties = properties;
        // Users drop downloaded model .zips next to the jar; unpack them
        // into ./models in the background so they appear in the dropdown.
        rescanDroppedZips();
    }

    /**
     * Unpacks any newly dropped model zips in the background. Also invoked
     * every time the model dropdown opens, so zips added while the app is
     * running are picked up without a restart.
     */
    public void rescanDroppedZips() {
        if (!scanning.compareAndSet(false, true)) {
            return;
        }
        Thread unzipper = new Thread(() -> {
            try {
                unpackDroppedZips();
            } finally {
                scanning.set(false);
            }
        }, "model-unzip");
        unzipper.setDaemon(true);
        unzipper.start();
    }

    /**
     * Unpacks any dropped model zips and returns only when finished (unlike the
     * background {@link #rescanDroppedZips()}). Used by the Recheck button so
     * the model list reflects newly added archives immediately. Call off the UI
     * thread — a large model takes a while to unpack.
     */
    public void unpackDroppedZipsNow() {
        unpackDroppedZips();
    }

    private static String topLevelDirOf(Path zip) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry first = zis.getNextEntry();
            if (first != null && first.getName().contains("/")) {
                return first.getName().substring(0, first.getName().indexOf('/'));
            }
        } catch (IOException ignored) {
            // unreadable zip; skip it
        }
        return null;
    }

    /** Directory containing the running jar (falls back to the working dir). */
    /** The folder the app runs from — the jar's directory, or the working dir. */
    public static Path appHome() {
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
                // The installed app's user-writable models folder (Documents),
                // then the folders next to the jar / working directory.
                com.aiassist.setup.UserPaths.documents().resolve("ai-assist").resolve("models"),
                appHome().resolve("models"), appHome(),
                Path.of("models").toAbsolutePath(), Path.of("").toAbsolutePath())));
    }

    /** Same locations, shared with the Whisper and local-LLM model lookups. */
    public static java.util.List<Path> modelSearchRoots() {
        return modelRoots();
    }

    private void unpackDroppedZips() {
        // Unpack into the user-writable models folder (Documents), which is
        // where the app tells users to put models and is writable even when the
        // app is installed under /Applications or Program Files.
        Path target = com.aiassist.setup.UserPaths.modelsDir();
        for (Path root : modelRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var files = Files.list(root)) {
                for (Path zip : files.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.contains("vosk-model") && n.endsWith(".zip");
                }).toList()) {
                    // The model folder name comes from inside the zip, so a
                    // renamed download (e.g. "... (1).zip") still works.
                    String dirName = topLevelDirOf(zip);
                    if (dirName == null) {
                        continue;
                    }
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
                    unpacking.add(dirName);
                    try {
                        Files.createDirectories(target);
                        try (InputStream in = Files.newInputStream(zip)) {
                            unzip(in, target);
                        }
                    } finally {
                        unpacking.remove(dirName);
                    }
                    // Keep the original download: move the .zip into the
                    // Documents backup folder now that it is unpacked.
                    moveToBackup(zip);
                    log.info("Model {} ready; reopen the model dropdown to pick it", dirName);
                }
            } catch (IOException e) {
                log.warn("Could not unpack dropped model zips in {}: {}", root, e.getMessage());
            }
            // Whisper (.bin) and LLM (.gguf) files aren't zips — there's
            // nothing to unpack, they're used directly from wherever they're
            // found — but they got no safekeeping copy at all before, unlike
            // a Vosk model's original .zip. Keep one alongside it for the
            // same reason: protection against an accidental delete/overwrite
            // of the copy actually in use.
            try (var files = Files.list(root)) {
                for (Path file : files.filter(VoskModelManager::isBackupWorthyModelFile).toList()) {
                    backupModelFileCopy(file);
                }
            } catch (IOException e) {
                log.warn("Could not check {} for model files to back up: {}", root, e.getMessage());
            }
        }
    }

    private static boolean isBackupWorthyModelFile(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return (n.startsWith("ggml-") && n.endsWith(".bin")) || n.endsWith(".gguf");
    }

    /** Keeps a safekeeping copy in model-backups; the original stays in place and in use. */
    private void backupModelFileCopy(Path file) {
        try {
            Path backupDir = com.aiassist.setup.UserPaths.modelBackupDir();
            Path target = backupDir.resolve(file.getFileName().toString());
            if (Files.exists(target)) {
                return; // already backed up
            }
            Files.copy(file, target);
            log.info("Backed up model file to {}", target);
        } catch (IOException e) {
            log.warn("Could not back up model file {} ({})", file, e.getMessage());
        }
    }

    /**
     * Ranks installed models by how well-suited they are as the automatic
     * default for live captioning when the user hasn't picked one — not by
     * raw accuracy, since the single most accurate Vosk model
     * (vosk-model-en-us-0.22) is explicitly too heavy to keep up with live
     * speech (see the Settings tab's model tooltip) and would make captions
     * drop. "lgraph" is the real-time-capable sweet spot; the small model is
     * a safe, fast fallback; the full model is listed last so it's still
     * available to pick by hand but never auto-chosen over a lighter option.
     * Anything not in this list (a future or unrecognized model) sorts after
     * all of these, in whatever order it was found.
     */
    private static final java.util.List<String> PREFERRED_DEFAULT_MODEL_ORDER = java.util.List.of(
            "vosk-model-en-us-0.22-lgraph",
            "vosk-model-small-en-us-0.15",
            "vosk-model-en-us-0.22");

    /** The best of the installed models — the default until the user picks another. */
    public String defaultModelName() {
        var available = listAvailableModels();
        if (available.isEmpty()) {
            return properties.modelName();
        }
        for (String preferred : PREFERRED_DEFAULT_MODEL_ORDER) {
            if (available.contains(preferred)) {
                return preferred;
            }
        }
        return available.getFirst();
    }

    public java.util.List<String> listAvailableModels() {
        // Deliberately excludes properties.modelDir() (an OS-managed temp
        // folder, only ever populated by the opt-in allow-download path in
        // ensureModel()): that folder is invisible to the user (not
        // Documents, not next to the jar) and never cleaned up, so a model
        // downloaded there once would otherwise keep winning as "active"
        // forever, silently overriding a real model the user later placed in
        // one of modelRoots() — surprising and impossible to notice by
        // browsing. ensureModel() still finds and reuses it directly (its own
        // check at the same path), so allow-download keeps working; it's
        // just never offered/preferred in the visible model list.
        var names = new java.util.LinkedHashSet<String>();
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
        names.removeAll(unpacking);
        return java.util.List.copyOf(names);
    }

    /** Models currently being unpacked from dropped zips (not yet usable). */
    public java.util.Set<String> unpackingNow() {
        return java.util.Set.copyOf(unpacking);
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
        // Check every place a model can legitimately be — including the
        // Documents models folder the app itself tells users to drop models
        // into (modelRoots(), the same list listAvailableModels() and the
        // named ensureModel(String) use). Without this, a model correctly
        // unpacked there was never found here: the default model name always
        // routes to this method (see ensureModel(String)'s equals check
        // below), so every "just start" path silently missed it and fell
        // through to the temp-dir embedded/download path instead — creating
        // and looking in the wrong folder even though the real model sat
        // right there in Documents.
        String name = properties.modelName();
        for (Path root : modelRoots()) {
            Path candidate = root.resolve(name);
            if (isModelPresent(candidate)) {
                return candidate;
            }
        }
        Path modelPath = Path.of(properties.modelDir(), name);
        if (isModelPresent(modelPath)) {
            return modelPath;
        }

        // Released builds ship no model, so this is normally null. A developer
        // build made with -Pfetch-model may embed one; extract it if present.
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
            throw new IOException(("No speech model found. ai-assist ships without models to keep the "
                    + "download small — see the Settings tab → Instructions to get one. Place the "
                    + "Vosk model folder or .zip (e.g. vosk-model-small-en-us-0.15 from "
                    + "alphacephei.com/vosk/models) in %s, then press Start again.")
                    .formatted(com.aiassist.setup.UserPaths.modelsDir()));
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

    /** Moves an unpacked model .zip into the Documents backup folder. */
    private void moveToBackup(Path zip) {
        try {
            Path backupDir = com.aiassist.setup.UserPaths.modelBackupDir();
            Path target = backupDir.resolve(zip.getFileName().toString());
            try {
                Files.move(zip, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException crossDevice) {
                // Backup may be on a different filesystem than the source.
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(zip);
            }
            log.info("Backed up model archive to {}", target);
        } catch (IOException e) {
            log.warn("Could not back up model archive {} ({}); leaving it in place", zip, e.getMessage());
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
