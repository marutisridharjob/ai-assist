package com.aiassist;

import java.util.Map;

import com.aiassist.draft.Draft;
import com.aiassist.listen.ListenController.SessionView;
import com.aiassist.listen.ListenController.TranscriptView;
import com.aiassist.listen.Utterance;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // keep tests hands-off: no audio capture, no browser, no scheduling side effects
                "ai-assist.auto.start-capture=false",
                "ai-assist.auto.open-browser=false",
                "ai-assist.output.dir=target/test-drafts"
        })
class ListenAndDraftIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void fullListenThenDraftFlow() {
        ResponseEntity<SessionView> created = rest.postForEntity("/api/sessions",
                Map.of("topic", "Sprint retrospective"), SessionView.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = created.getBody().id();

        rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "The release went out on time and customers reacted positively."),
                Utterance.class, id);
        ResponseEntity<Utterance> second = rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "We need to improve our test coverage before the next sprint."),
                Utterance.class, id);
        assertThat(second.getBody().sequence()).isEqualTo(2);

        TranscriptView transcript = rest.getForObject("/api/sessions/{id}/transcript", TranscriptView.class, id);
        assertThat(transcript.transcript()).contains("release went out").contains("test coverage");

        ResponseEntity<Draft> drafted = rest.postForEntity("/api/sessions/{id}/draft",
                Map.of("contentType", "MEETING_NOTES", "tone", "PROFESSIONAL"), Draft.class, id);
        assertThat(drafted.getStatusCode()).isEqualTo(HttpStatus.OK);
        Draft draft = drafted.getBody();
        assertThat(draft.title()).isEqualTo("Sprint retrospective");
        assertThat(draft.contentType()).isEqualTo("MEETING_NOTES");
        assertThat(draft.actionItems()).anySatisfy(a -> assertThat(a).contains("test coverage"));
        assertThat(draft.fullText()).contains("# Sprint retrospective");

        // Previews are never persisted; the file is only written at meeting end.
        assertThat(draft.savedTo()).isNull();
    }

    @Test
    void endingTheMeetingSavesTheFullNotesFileAndLocksTheSession() {
        String id = rest.postForEntity("/api/sessions",
                Map.of("topic", "Quarterly review"), SessionView.class).getBody().id();
        rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "Revenue grew twelve percent over the previous quarter."), Utterance.class, id);
        rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "We need to hire two more engineers for the platform team."), Utterance.class, id);

        ResponseEntity<Draft> ended = rest.postForEntity("/api/sessions/{id}/end",
                Map.of("contentType", "MEETING_NOTES", "tone", "PROFESSIONAL"), Draft.class, id);
        assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.OK);
        Draft draft = ended.getBody();

        // The final end-to-end content is saved as one timestamped file.
        assertThat(draft.savedTo()).isNotNull();
        java.nio.file.Path saved = java.nio.file.Path.of(draft.savedTo());
        assertThat(saved).exists();
        assertThat(saved.getFileName().toString())
                .matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_quarterly-review\\.md");
        assertThat(saved).content()
                .contains("# Quarterly review")
                .contains("Revenue grew twelve percent")
                .contains("hire two more engineers");

        // The session is now read-only and cannot be ended twice.
        ResponseEntity<String> lateUtterance = rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "one more thing"), String.class, id);
        assertThat(lateUtterance.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> secondEnd = rest.postForEntity("/api/sessions/{id}/end", Map.of(), String.class, id);
        assertThat(secondEnd.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        SessionView view = rest.getForObject("/api/sessions/{id}", SessionView.class, id);
        assertThat(view.ended()).isTrue();
    }

    @Test
    void endingAnEmptyMeetingIsRejected() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/{id}/end", Map.of(), String.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void endingLiveMeetingWithoutOneRunningReturns404() {
        ResponseEntity<String> response = rest.postForEntity("/api/live/end", Map.of(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adHocDraftWithoutSession() {
        ResponseEntity<Draft> response = rest.postForEntity("/api/draft",
                Map.of("topic", "Launch email", "notes", "The launch is on Friday. Please join the call.",
                        "contentType", "EMAIL", "tone", "FRIENDLY"),
                Draft.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().sections()).isNotEmpty();
    }

    @Test
    void draftingAnEmptySessionIsRejected() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/{id}/draft", Map.of(), String.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void unknownSessionReturns404() {
        ResponseEntity<String> response = rest.getForEntity("/api/sessions/does-not-exist", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void blankUtteranceIsRejected() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "   "), String.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsOversizedUtterance() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/{id}/utterances",
                Map.of("text", "x".repeat(20_001)), String.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsOversizedTopic() {
        ResponseEntity<String> response = rest.postForEntity("/api/sessions",
                Map.of("topic", "t".repeat(201)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsUnknownContentType() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        rest.postForEntity("/api/sessions/{id}/utterances", Map.of("text", "some notes"), Utterance.class, id);
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/{id}/draft",
                Map.of("contentType", "POEM"), String.class, id);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankTopicFallsBackToUntitled() {
        SessionView session = rest.postForEntity("/api/sessions",
                Map.of("topic", "   "), SessionView.class).getBody();
        assertThat(session.topic()).isEqualTo("Untitled");
    }

    @Test
    void deletedSessionIsGoneAndCannotBeDeletedTwice() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();

        rest.delete("/api/sessions/{id}", id);

        assertThat(rest.getForEntity("/api/sessions/{id}", String.class, id).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> secondDelete = rest.exchange("/api/sessions/{id}",
                org.springframework.http.HttpMethod.DELETE, null, String.class, id);
        assertThat(secondDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transcriptOfEmptySessionIsEmpty() {
        String id = rest.postForEntity("/api/sessions", Map.of(), SessionView.class).getBody().id();
        TranscriptView transcript = rest.getForObject("/api/sessions/{id}/transcript", TranscriptView.class, id);
        assertThat(transcript.transcript()).isEmpty();
    }

    @Test
    void previewOfAnEndedMeetingIsStillAllowed() {
        String id = rest.postForEntity("/api/sessions",
                Map.of("topic", "Wrap-up"), SessionView.class).getBody().id();
        rest.postForEntity("/api/sessions/{id}/utterances", Map.of("text", "final remarks"), Utterance.class, id);
        rest.postForEntity("/api/sessions/{id}/end", Map.of(), Draft.class, id);

        ResponseEntity<Draft> preview = rest.postForEntity("/api/sessions/{id}/draft", Map.of(), Draft.class, id);

        assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody().savedTo()).isNull();
    }

    @Test
    void endingUnknownSessionReturns404() {
        ResponseEntity<String> response = rest.postForEntity("/api/sessions/nope/end", Map.of(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stoppingLiveCaptureWhenIdleIsSafe() {
        ResponseEntity<Map> response = rest.postForEntity("/api/live/stop", null, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("state")).isEqualTo("IDLE");
    }

    @Test
    void liveDraftIsEmptyBeforeAnyMeeting() {
        ResponseEntity<String> response = rest.getForEntity("/api/live/draft", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void liveStatusIsIdleWhenAutoStartDisabled() {
        ResponseEntity<Map> response = rest.getForEntity("/api/live/status", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("state")).isEqualTo("IDLE");
    }

    @Test
    void audioDeviceListingWorksEvenWithoutDevices() {
        ResponseEntity<Object[]> response = rest.getForEntity("/api/audio/devices", Object[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
