package com.aiassist.listen;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListeningSessionTest {

    @Test
    void concurrentUtterancesGetUniqueConsecutiveSequences() throws Exception {
        ListeningSession session = new ListeningSession("s1", "load test");
        int writers = 8;
        int perWriter = 50;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int w = 0; w < writers; w++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perWriter; i++) {
                        session.addUtterance("utterance " + i, "speaker");
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        List<Utterance> utterances = session.utterances();
        assertThat(utterances).hasSize(writers * perWriter);
        assertThat(utterances.stream().map(Utterance::sequence).distinct())
                .hasSize(writers * perWriter);
        // Stored in arrival order with sequences 1..N.
        for (int i = 0; i < utterances.size(); i++) {
            assertThat(utterances.get(i).sequence()).isEqualTo(i + 1);
        }
    }

    @Test
    void transcriptJoinsUtterancesInOrderAndStripsWhitespace() {
        ListeningSession session = new ListeningSession("s2", "t");
        session.addUtterance("  first thought  ", "user");
        session.addUtterance("second thought", "user");

        assertThat(session.transcript()).isEqualTo("first thought\nsecond thought");
    }

    @Test
    void emptySessionHasEmptyTranscript() {
        assertThat(new ListeningSession("s3", "t").transcript()).isEmpty();
    }

    @Test
    void renameChangesTopicUntilTheMeetingEnds() {
        ListeningSession session = new ListeningSession("s5", "Live meeting notes");

        session.rename("  Quarterly sync  ");
        assertThat(session.topic()).isEqualTo("Quarterly sync");
        session.rename("   ");
        assertThat(session.topic()).isEqualTo("Quarterly sync");

        session.addUtterance("something", "mic");
        session.end();
        assertThatThrownBy(() -> session.rename("Too late"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ended");
    }

    @Test
    void endingLocksTheSessionAndCannotHappenTwice() {
        ListeningSession session = new ListeningSession("s4", "t");
        session.addUtterance("before end", "user");

        session.end();

        assertThat(session.isEnded()).isTrue();
        assertThat(session.endedAt()).isNotNull();
        assertThatThrownBy(() -> session.addUtterance("after end", "user"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ended");
        assertThatThrownBy(session::end)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already ended");
        // Existing content is still readable after the lock.
        assertThat(session.transcript()).isEqualTo("before end");
    }
}
