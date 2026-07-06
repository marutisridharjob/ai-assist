package com.aiassist.audio;

import com.sun.jna.Pointer;

/** Loaded offline speech model; shareable across recognizers and threads. */
public final class SpeechModel implements AutoCloseable {

    private final Pointer handle;

    public SpeechModel(String modelDirectory) {
        this.handle = VoskNative.INSTANCE.vosk_model_new(modelDirectory);
        if (handle == null) {
            throw new IllegalStateException("Could not load speech model from " + modelDirectory);
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
