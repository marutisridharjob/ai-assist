package com.aiassist.draft;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingEndServiceMicBleedThroughTest {

    @Test
    void dropsAYouSegmentThatEchoesAnOtherSegmentSaidAtNearlyTheSameMoment() {
        List<MeetingEndService.Timed> segments = List.of(
                new MeetingEndService.Timed(10.0, "other", "let's ship the release on Friday afternoon"),
                new MeetingEndService.Timed(11.5, "you", "let's ship the release on Friday afternoon"));

        List<MeetingEndService.Timed> kept = MeetingEndService.dropMicBleedThrough(segments);

        assertThat(kept).containsExactly(segments.get(0));
    }

    @Test
    void keepsAYouSegmentThatSaysSomethingDifferentFromWhatOtherJustSaid() {
        List<MeetingEndService.Timed> segments = List.of(
                new MeetingEndService.Timed(10.0, "other", "let's ship the release on Friday afternoon"),
                new MeetingEndService.Timed(11.5, "you", "sounds good, I'll update the ticket"));

        List<MeetingEndService.Timed> kept = MeetingEndService.dropMicBleedThrough(segments);

        assertThat(kept).containsExactlyElementsOf(segments);
    }

    @Test
    void keepsAYouSegmentThatEchoesOtherButFarOutsideTheBleedThroughWindow() {
        List<MeetingEndService.Timed> segments = List.of(
                new MeetingEndService.Timed(10.0, "other", "let's ship the release on Friday afternoon"),
                new MeetingEndService.Timed(60.0, "you", "let's ship the release on Friday afternoon"));

        List<MeetingEndService.Timed> kept = MeetingEndService.dropMicBleedThrough(segments);

        assertThat(kept).containsExactlyElementsOf(segments);
    }

    @Test
    void neverDropsOtherSegmentsRegardlessOfMatches() {
        List<MeetingEndService.Timed> segments = List.of(
                new MeetingEndService.Timed(10.0, "other", "let's ship the release on Friday afternoon"),
                new MeetingEndService.Timed(10.1, "other", "let's ship the release on Friday afternoon"));

        List<MeetingEndService.Timed> kept = MeetingEndService.dropMicBleedThrough(segments);

        assertThat(kept).containsExactlyElementsOf(segments);
    }

    @Test
    void keepsEverythingWhenThereIsNoOtherChannelAtAll() {
        List<MeetingEndService.Timed> segments = List.of(
                new MeetingEndService.Timed(10.0, "you", "let's ship the release on Friday afternoon"));

        List<MeetingEndService.Timed> kept = MeetingEndService.dropMicBleedThrough(segments);

        assertThat(kept).containsExactlyElementsOf(segments);
    }
}
