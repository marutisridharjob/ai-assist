package com.aiassist.listen;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Accumulates everything the assistant "hears" (dictated or typed utterances)
 * until the user asks for a draft. Thread-safe: utterances arrive from
 * concurrent requests while the UI streams speech-recognition results.
 */
public class ListeningSession {

    private final String id;
    private String topic;
    private final Instant startedAt;
    private final List<Utterance> utterances = new ArrayList<>();
    private int nextSequence = 1;
    private Instant endedAt;

    public ListeningSession(String id, String topic) {
        this.id = id;
        this.topic = topic;
        this.startedAt = Instant.now();
    }

    public synchronized Utterance addUtterance(String text, String speaker) {
        if (endedAt != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Meeting " + id + " has ended; no more input is accepted");
        }
        Utterance utterance = new Utterance(nextSequence++, text.strip(), speaker, Instant.now());
        utterances.add(utterance);
        return utterance;
    }

    /** Marks the meeting as finished; the session becomes read-only. */
    public synchronized void end() {
        if (endedAt != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Meeting " + id + " has already ended");
        }
        endedAt = Instant.now();
    }

    public synchronized boolean isEnded() {
        return endedAt != null;
    }

    public synchronized Instant endedAt() {
        return endedAt;
    }

    public synchronized List<Utterance> utterances() {
        return Collections.unmodifiableList(new ArrayList<>(utterances));
    }

    /** All captured text joined in arrival order, ready to hand to the drafter. */
    public synchronized String transcript() {
        StringBuilder sb = new StringBuilder();
        for (Utterance u : utterances) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(u.text());
        }
        return sb.toString();
    }

    public String id() {
        return id;
    }

    public synchronized String topic() {
        return topic;
    }

    /** Renames the meeting; the title drives the notes file name. */
    public synchronized void rename(String newTopic) {
        if (endedAt != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Meeting " + id + " has ended; the title can no longer change");
        }
        if (newTopic != null && !newTopic.isBlank()) {
            this.topic = newTopic.strip();
        }
    }

    public Instant startedAt() {
        return startedAt;
    }
}
