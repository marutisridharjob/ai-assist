package com.aiassist.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.aiassist.config.TranscriptionProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoskModelManagerTest {

    @TempDir
    Path tempDir;

    private TranscriptionProperties props(Path modelDir, boolean allowDownload) {
        return new TranscriptionProperties(modelDir.toString(), null, "test-model",
                16000f, null, allowDownload);
    }

    @Test
    void findsModelAlreadyPresentOnDiskWithoutNetwork() throws Exception {
        Path model = tempDir.resolve("test-model");
        Files.createDirectories(model.resolve("am"));

        Path resolved = new VoskModelManager(props(tempDir, false)).ensureModel();

        assertThat(resolved).isEqualTo(model);
    }

    @Test
    void refusesToDownloadWhenOffline() {
        VoskModelManager manager = new VoskModelManager(props(tempDir.resolve("empty"), false));

        assertThatThrownBy(manager::ensureModel)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("download is disabled")
                .hasMessageContaining("fetch-model");
    }
}
