package com.aiassist.audio;

import com.sun.jna.Pointer;

/** Streaming recognizer over a {@link SpeechModel}; one per capture thread. */
public final class SpeechRecognizer implements AutoCloseable {

    private final Pointer handle;

    public SpeechRecognizer(SpeechModel model, float sampleRate) {
        this(model, sampleRate, null);
    }

    /** With a speaker model, results carry an x-vector for voice identification. */
    public SpeechRecognizer(SpeechModel model, float sampleRate, Pointer speakerModel) {
        this.handle = speakerModel == null
                ? VoskNative.INSTANCE.vosk_recognizer_new(model.handle(), sampleRate)
                : VoskNative.INSTANCE.vosk_recognizer_new_spk(model.handle(), sampleRate, speakerModel);
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

    /** JSON with the in-progress hypothesis, e.g. {@code {"partial": "hello wor"}}. */
    public String partialResult() {
        return VoskNative.INSTANCE.vosk_recognizer_partial_result(handle);
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
