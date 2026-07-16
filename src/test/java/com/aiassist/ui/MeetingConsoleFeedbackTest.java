package com.aiassist.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingConsoleFeedbackTest {

    @Test
    void feedbackBodyIncludesMessageRatingAndMachineContext() {
        String body = MeetingConsole.feedbackBody("Great app, works offline!", 4);

        assertThat(body)
                .contains("Great app, works offline!")
                .contains("User rating: 4/5")
                .contains("IP address:")
                .contains("User name:")
                .contains("Location:");
    }

    @Test
    void feedbackBodyHandlesNullMessage() {
        String body = MeetingConsole.feedbackBody(null, 0);
        assertThat(body).contains("User rating: 0/5");
    }
}
