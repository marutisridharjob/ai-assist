package com.aiassist.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioResamplerTest {

    /** Builds N mono 16-bit LE samples from the given values. */
    private static byte[] pcm(int... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[2 * i] = (byte) samples[i];
            out[2 * i + 1] = (byte) (samples[i] >> 8);
        }
        return out;
    }

    private static int sampleAt(byte[] pcm, int i) {
        return (short) ((pcm[2 * i + 1] << 8) | (pcm[2 * i] & 0xFF));
    }

    @Test
    void returnsInputUnchangedWhenAlready16k() {
        byte[] in = pcm(100, -200, 300);
        assertThat(AudioResampler.to16k(in, in.length, 16000f)).isEqualTo(in);
    }

    @Test
    void halvesSampleCountWhenDownsamplingFrom32k() {
        byte[] in = pcm(0, 1000, 2000, 3000, 4000, 5000, 6000, 8000);
        byte[] out = AudioResampler.to16k(in, in.length, 32000f);
        assertThat(out.length / 2).isEqualTo(in.length / 2 / 2);
        assertThat(sampleAt(out, 0)).isEqualTo(0); // first sample preserved
    }

    @Test
    void producesRoughly16kFrom48kInput() {
        byte[] in = new byte[48000 * 2]; // 1 second at 48 kHz
        byte[] out = AudioResampler.to16k(in, in.length, 48000f);
        assertThat(out.length / 2).isBetween(15900, 16100);
    }

    @Test
    void interpolatesBetweenSamples() {
        byte[] in = pcm(0, 1000); // upsampling exposes the interpolation
        byte[] out = AudioResampler.to16k(in, in.length, 8000f);
        // 8k -> 16k doubles the samples; the inserted point lies between 0 and 1000.
        assertThat(out.length / 2).isEqualTo(4);
        assertThat(sampleAt(out, 1)).isBetween(0, 1000);
    }

    @Test
    void handlesEmptyInput() {
        assertThat(AudioResampler.to16k(new byte[0], 0, 48000f)).isEmpty();
    }
}
