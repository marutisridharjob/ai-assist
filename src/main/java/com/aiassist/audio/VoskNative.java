package com.aiassist.audio;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

/**
 * Minimal binding to the Vosk native library, declaring only the functions
 * this app calls. Uses JNA interface mapping, which resolves each symbol
 * lazily on first call — unlike the vosk-java wrapper's eager registration,
 * which fails outright on macOS because the dylib bundled with vosk 0.3.45
 * is older than its Java class and lacks symbols we never use (e.g.
 * {@code vosk_recognizer_set_grm}).
 */
interface VoskNative extends Library {

    VoskNative INSTANCE = Native.load("vosk", VoskNative.class);

    Pointer vosk_model_new(String path);

    void vosk_model_free(Pointer model);

    Pointer vosk_recognizer_new(Pointer model, float sampleRate);

    void vosk_recognizer_free(Pointer recognizer);

    int vosk_recognizer_accept_waveform(Pointer recognizer, byte[] data, int length);

    String vosk_recognizer_result(Pointer recognizer);

    String vosk_recognizer_partial_result(Pointer recognizer);

    String vosk_recognizer_final_result(Pointer recognizer);
}
