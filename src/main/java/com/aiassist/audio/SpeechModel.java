package com.aiassist.audio;

import com.sun.jna.Pointer;

/** Loaded offline speech model; shareable across recognizers and threads. */
public final class SpeechModel implements AutoCloseable {

    private final Pointer handle;

    public SpeechModel(String modelDirectory) {
        this.handle = VoskNative.INSTANCE.vosk_model_new(modelDirectory);
        if (handle == null) {
            // Name only — the full path is noise in the on-screen status.
            java.nio.file.Path path = java.nio.file.Path.of(modelDirectory);
            throw new IllegalStateException("Could not load speech model \""
                    + path.getFileName() + "\"");
        }
    }

    Pointer handle() {
        return handle;
    }

    @Override
    public void close() {
        VoskNative.INSTANCE.vosk_model_free(handle);
    }
}
