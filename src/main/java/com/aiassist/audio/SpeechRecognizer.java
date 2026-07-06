package com.aiassist.audio;

import com.sun.jna.Pointer;

/** Streaming recognizer over a {@link SpeechModel}; one per capture thread. */
public final class SpeechRecognizer implements AutoCloseable {

    private final Pointer handle;

    public SpeechRecognizer(SpeechModel model, float sampleRate) {
        this.handle = VoskNative.INSTANCE.vosk_recognizer_new(model.handle(), sampleRate);
        if (handle == null) {
            throw new IllegalStateException("Could not create speech recognizer");
        }
    }

    /** Feeds PCM audio; returns true when a completed phrase is ready in {@link #result()}. */
    public boolean acceptWaveform(byte[] data, int length) {
        return VoskNative.INSTANCE.vosk_recognizer_accept_waveform(handle, data, length) > 0;
    }

    /** JSON with the completed phrase, e.g. {@code {"text": "hello world"}}. */
    public String result() {
        return VoskNative.INSTANCE.vosk_recognizer_result(handle);
    }

    /** JSON with whatever remains buffered; call once when capture ends. */
    public String finalResult() {
        return VoskNative.INSTANCE.vosk_recognizer_final_result(handle);
    }

    @Override
    public void close() {
        VoskNative.INSTANCE.vosk_recognizer_free(handle);
    }
}
