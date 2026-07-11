package com.aiassist.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void extractsModelEmbeddedInsideTheApp() throws Exception {
        byte[] zip = zipWithEntries("test-model/am/final.mdl", "test-model/conf/model.conf");
        VoskModelManager manager = new VoskModelManager(props(tempDir.resolve("extracted"), false)) {
            @Override
            protected InputStream embeddedModelZip() {
                return new ByteArrayInputStream(zip);
            }
        };

        Path resolved = manager.ensureModel();

        assertThat(resolved).isEqualTo(tempDir.resolve("extracted").resolve("test-model"));
        assertThat(resolved.resolve("am/final.mdl")).exists();
    }

    @Test
    void refusesToDownloadWhenOfflineAndNothingIsEmbedded() {
        VoskModelManager manager = new VoskModelManager(props(tempDir.resolve("empty"), false)) {
            @Override
            protected InputStream embeddedModelZip() {
                return null;
            }
        };

        assertThatThrownBy(manager::ensureModel)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No speech model found")
                .hasMessageContaining("next to");
    }

    @Test
    void rejectsZipEntriesEscapingTheModelDirectory() throws Exception {
        byte[] hostileZip = zipWithEntries("../escape.txt");
        VoskModelManager manager = new VoskModelManager(props(tempDir.resolve("target"), false)) {
            @Override
            protected InputStream embeddedModelZip() {
                return new ByteArrayInputStream(hostileZip);
            }
        };

        assertThatThrownBy(manager::ensureModel)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("escapes target directory");
    }

    private byte[] zipWithEntries(String... entryNames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String name : entryNames) {
                zos.putNextEntry(new ZipEntry(name));
                zos.write("stub".getBytes());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
