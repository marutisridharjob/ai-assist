package com.aiassist.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The models ai-assist can use and how to recognise them on disk. The app
 * ships with no models; the user downloads and drops them into the models
 * folder. The Whisper model is <em>required</em> for a transcript; the rest
 * are <em>recommended</em> upgrades.
 */
public final class ModelCatalog {

    private ModelCatalog() {
    }

    public enum Tier { REQUIRED, RECOMMENDED }

    /**
     * A catalogued model: how to describe it, where to download it, and how to
     * tell whether it is already present in a given folder.
     */
    public record ModelSpec(String id, String title, Tier tier, String purpose,
                            String fileName, String downloadUrl, String instructions,
                            Predicate<Path> presentIn) {
    }

    /** Present-in-folder tests, kept next to the catalogue so they stay in sync. */
    private static boolean hasFile(Path dir, String prefix, String suffix) {
        return anyChild(dir, p -> {
            if (!Files.isRegularFile(p)) {
                return false;
            }
            String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
            return (prefix.isEmpty() || n.startsWith(prefix)) && n.endsWith(suffix);
        });
    }

    private static boolean anyChild(Path dir, Predicate<Path> test) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var children = Files.list(dir)) {
            return children.anyMatch(test);
        } catch (IOException e) {
            return false;
        }
    }

    /** The catalogue, most important first. */
    public static final List<ModelSpec> MODELS = List.of(
            new ModelSpec("whisper", "Whisper transcription model", Tier.REQUIRED,
                    "the meeting transcript, produced when you press Stop",
                    "ggml-medium.bin",
                    "https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases/latest/download/ggml-medium.bin",
                    "Download from the GitHub mirror and put the .bin file in the models folder. The whole "
                            + "recording is transcribed after Stop, so a bigger model means meaningfully more "
                            + "accurate saved notes. For the most accurate notes possible, use ggml-large-v3.bin (~3 GB) "
                            + "instead; if more than one is present, the app automatically uses the most "
                            + "accurate one installed.",
                    dir -> hasFile(dir, "ggml-", ".bin")),
            new ModelSpec("llm", "Instruct LLM model (GGUF)", Tier.RECOMMENDED,
                    "richer AI summaries and rewrites",
                    "qwen2.5-3b-instruct-q4_k_m.gguf",
                    "https://github.com/ggerganov/llama.cpp",
                    "Optional. For noticeably better summaries and rewrites, use a model like "
                            + "qwen2.5-3b-instruct-q4_k_m.gguf (~2 GB); llama-3.2-1b-instruct-q4_k_m.gguf "
                            + "(~800 MB) is a lighter default. Put a single GGUF instruct model in the "
                            + "models folder.",
                    dir -> hasFile(dir, "", ".gguf")));

    /** The outcome of checking the catalogue against the model folders. */
    public record Status(List<ModelSpec> present, List<ModelSpec> missing) {
        public boolean allRequiredPresent() {
            return missing.stream().noneMatch(m -> m.tier() == Tier.REQUIRED);
        }

        public List<ModelSpec> missingRequired() {
            return missing.stream().filter(m -> m.tier() == Tier.REQUIRED).toList();
        }

        public List<ModelSpec> missingRecommended() {
            return missing.stream().filter(m -> m.tier() == Tier.RECOMMENDED).toList();
        }
    }

    /** Classifies every catalogued model as present or missing across the given folders. */
    public static Status scan(List<Path> roots) {
        List<ModelSpec> present = new ArrayList<>();
        List<ModelSpec> missing = new ArrayList<>();
        for (ModelSpec spec : MODELS) {
            boolean found = roots.stream().anyMatch(spec.presentIn());
            (found ? present : missing).add(spec);
        }
        return new Status(present, missing);
    }
}
