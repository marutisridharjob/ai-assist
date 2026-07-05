package com.aiassist.listen;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for the "listen" half of the assistant: open a session, feed it
 * utterances (from the browser's speech recognition or typed input), and
 * inspect the accumulated transcript.
 */
@RestController
@RequestMapping("/api/sessions")
public class ListenController {

    private final SessionStore store;

    public ListenController(SessionStore store) {
        this.store = store;
    }

    public record CreateSessionRequest(@Size(max = 200) String topic) {
    }

    public record UtteranceRequest(@NotBlank @Size(max = 20_000) String text,
                                   @Size(max = 100) String speaker) {
    }

    public record SessionView(String id, String topic, Instant startedAt, Instant endedAt,
                              boolean ended, int utteranceCount, List<Utterance> utterances) {

        static SessionView of(ListeningSession session) {
            List<Utterance> utterances = session.utterances();
            return new SessionView(session.id(), session.topic(), session.startedAt(),
                    session.endedAt(), session.isEnded(), utterances.size(), utterances);
        }
    }

    @PostMapping
    public ResponseEntity<SessionView> createSession(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        String topic = request == null ? null : request.topic();
        ListeningSession session = store.create(topic == null || topic.isBlank() ? "Untitled" : topic.strip());
        return ResponseEntity.created(URI.create("/api/sessions/" + session.id()))
                .body(SessionView.of(session));
    }

    @GetMapping
    public List<SessionView> listSessions() {
        return store.all().stream().map(SessionView::of).toList();
    }

    @GetMapping("/{id}")
    public SessionView getSession(@PathVariable String id) {
        return SessionView.of(store.get(id));
    }

    @PostMapping("/{id}/utterances")
    public Utterance addUtterance(@PathVariable String id, @Valid @RequestBody UtteranceRequest request) {
        String speaker = request.speaker() == null || request.speaker().isBlank() ? "user" : request.speaker().strip();
        return store.get(id).addUtterance(request.text(), speaker);
    }

    @GetMapping("/{id}/transcript")
    public TranscriptView getTranscript(@PathVariable String id) {
        ListeningSession session = store.get(id);
        return new TranscriptView(session.id(), session.transcript());
    }

    public record TranscriptView(String sessionId, String transcript) {
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        store.delete(id);
        return ResponseEntity.noContent().build();
    }
}
