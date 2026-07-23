package com.aiassist.draft;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import com.aiassist.config.OutputProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DraftFileWriterTest {

    @TempDir
    Path tempDir;

    private Draft draft(String title) {
        return new Draft(title, "DOCUMENT", "PROFESSIONAL", "summary",
                List.of(new Draft.Section("Details", "body")),
                List.of(), List.of(), "# " + title + "\n\nbody", "template", Instant.now(), null);
    }

    @Test
    void savesTimestampedFileWithSluggedTitle() {
        DraftFileWriter writer = new DraftFileWriter(new OutputProperties(true, tempDir.toString()));

        Path saved = writer.save(draft("Weekly Status: Meeting #7!"));

        assertThat(saved).exists();
        assertThat(saved.getFileName().toString())
                .matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_weekly-status-meeting-7\\.rtf");
        assertThat(saved).content().contains("Weekly Status: Meeting #7!");
    }

    @Test
    void savedFileLeadsWithASummaryHeading() {
        DraftFileWriter writer = new DraftFileWriter(new OutputProperties(true, tempDir.toString()));

        Path saved = writer.save(draft("Team sync"));

        assertThat(saved).content().contains("Summary");
    }

    @Test
    void returnsNullWhenSavingDisabled() {
        DraftFileWriter writer = new DraftFileWriter(new OutputProperties(false, tempDir.toString()));

        assertThat(writer.save(draft("Anything"))).isNull();
        assertThat(tempDir.toFile().list()).isEmpty();
    }

    @Test
    void fallsBackToGenericSlugForUnusableTitles() {
        DraftFileWriter writer = new DraftFileWriter(new OutputProperties(true, tempDir.toString()));

        Path symbols = writer.save(draft("!!!***???"));
        Path nullTitle = writer.save(draft(null));

        assertThat(symbols.getFileName().toString()).contains("_draft.rtf");
        assertThat(nullTitle.getFileName().toString()).contains("_draft.rtf");
    }

    @Test
    void truncatesVeryLongTitlesInFileName() {
        DraftFileWriter writer = new DraftFileWriter(new OutputProperties(true, tempDir.toString()));

        Path saved = writer.save(draft("a".repeat(500)));

        assertThat(saved.getFileName().toString().length()).isLessThan(100);
        assertThat(saved).exists();
    }

    @Test
    void blankDirSavesIntoAppFolderMeetingNotes(@TempDir Path isolatedHome) throws Exception {
        // A blank dir resolves to ~/Documents/meeting-notes, the same folder
        // real meetings are saved to. On a machine that has actually used
        // ai-assist, that folder is real, shared, and non-empty, so point
        // user.home at an empty temp dir for the duration of this test —
        // both to avoid writing into the real folder and because the old
        // version of this test deleted the shared parent directory in
        // `finally`, which fails with DirectoryNotEmptyException as soon as
        // any other real note lives there.
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedHome.toString());
        try {
            DraftFileWriter writer = new DraftFileWriter(new OutputProperties(true, ""));

            Path saved = writer.save(draft("Team sync"));

            assertThat(saved).exists();
            assertThat(saved.getParent().getFileName().toString()).isEqualTo("meeting-notes");
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
