package com.aiassist.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingRecorderTest {

    @Test
    void recordsPerSourceAndReturnsFilesOnFinish() throws Exception {
        MeetingRecorder recorder = new MeetingRecorder("test-" + System.nanoTime());
        byte[] a = {1, 2, 3, 4};
        byte[] b = {5, 6};
        recorder.record("you", a, a.length);
        recorder.record("other", a, a.length);
        recorder.record("you", b, b.length);

        Map<String, Path> files = recorder.finish();

        assertThat(files).containsOnlyKeys("you", "other");
        assertThat(Files.readAllBytes(files.get("you"))).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(Files.readAllBytes(files.get("other"))).containsExactly(1, 2, 3, 4);

        recorder.discard();
        assertThat(files.get("you")).doesNotExist();
    }

    @Test
    void finishWithNoAudioReturnsEmpty() throws Exception {
        MeetingRecorder recorder = new MeetingRecorder("empty-" + System.nanoTime());
        assertThat(recorder.finish()).isEmpty();
        recorder.discard();
    }
}
