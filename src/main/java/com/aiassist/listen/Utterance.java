package com.aiassist.listen;

import java.time.Instant;

/** A single captured piece of speech or typed input within a listening session. */
public record Utterance(int sequence, String text, String speaker, Instant capturedAt) {
}
