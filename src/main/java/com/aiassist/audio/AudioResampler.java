package com.aiassist.audio;

import java.util.Arrays;

/**
 * Linear resampling of 16-bit little-endian mono PCM to 16 kHz — the rate
 * Whisper expects. Every capture source is resampled to 16 kHz before it is
 * recorded.
 */
final class AudioResampler {

    static final float TARGET_RATE = 16000f;

    private AudioResampler() {
    }

    static byte[] to16k(byte[] buffer, int length, float sourceRate) {
        if (Math.abs(sourceRate - TARGET_RATE) < 1f) {
            return length == buffer.length ? buffer : Arrays.copyOf(buffer, length);
        }
        int inSamples = length / 2;
        if (inSamples == 0) {
            return new byte[0];
        }
        double ratio = TARGET_RATE / sourceRate;
        int outSamples = Math.max(1, (int) (inSamples * ratio));
        byte[] out = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            double srcPos = i / ratio;
            int j = (int) srcPos;
            double frac = srcPos - j;
            int s0 = sample(buffer, j, inSamples);
            int s1 = sample(buffer, j + 1, inSamples);
            int value = (int) Math.round(s0 + (s1 - s0) * frac);
            out[2 * i] = (byte) value;
            out[2 * i + 1] = (byte) (value >> 8);
        }
        return out;
    }

    private static int sample(byte[] buffer, int index, int count) {
        int i = Math.min(index, count - 1);
        return (short) ((buffer[2 * i + 1] << 8) | (buffer[2 * i] & 0xFF));
    }
}
