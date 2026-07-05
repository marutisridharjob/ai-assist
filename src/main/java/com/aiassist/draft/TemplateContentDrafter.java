package com.aiassist.draft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Deterministic drafter that structures the captured notes into a detailed
 * document without any external service. It segments the transcript into
 * sentences, promotes the most informative ones to key points, pulls out
 * commitments as action items, and assembles a document shaped by the
 * requested content type and tone.
 */
@Component
public class TemplateContentDrafter implements ContentDrafter {

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    private static final List<String> ACTION_MARKERS = List.of(
            "need to", "needs to", "should", "must", "have to", "has to",
            "todo", "to do", "action item", "follow up", "follow-up",
            "will ", "let's", "make sure", "remember to", "don't forget");

    @Override
    public Draft draft(String topic, String transcript, DraftOptions options) {
        List<String> sentences = splitSentences(transcript);
        String title = buildTitle(topic, sentences);
        List<String> actionItems = extractActionItems(sentences);
        List<String> keyPoints = extractKeyPoints(sentences, actionItems);
        String summary = buildSummary(title, sentences, options);
        List<Draft.Section> sections = buildSections(title, sentences, keyPoints, actionItems, options);
        String fullText = renderFullText(title, summary, sections);

        return new Draft(title, options.contentType().name(), options.tone().name(),
                summary, sections, keyPoints, actionItems, fullText, name(), Instant.now(), null);
    }

    @Override
    public String name() {
        return "template";
    }

    private List<String> splitSentences(String transcript) {
        List<String> sentences = new ArrayList<>();
        for (String raw : SENTENCE_SPLIT.split(transcript)) {
            String s = raw.strip();
            if (!s.isEmpty()) {
                sentences.add(StringUtils.capitalize(s));
            }
        }
        return sentences;
    }

    private String buildTitle(String topic, List<String> sentences) {
        if (StringUtils.isNotBlank(topic) && !"Untitled".equalsIgnoreCase(topic)) {
            return StringUtils.capitalize(topic.strip());
        }
        if (!sentences.isEmpty()) {
            return StringUtils.abbreviate(StringUtils.removeEnd(sentences.getFirst(), "."), 80);
        }
        return "Draft";
    }

    private List<String> extractActionItems(List<String> sentences) {
        List<String> items = new ArrayList<>();
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (ACTION_MARKERS.stream().anyMatch(lower::contains)) {
                items.add(ensurePeriod(sentence));
            }
        }
        return items;
    }

    /** The longest sentences carry the most information; keep up to five, in original order. */
    private List<String> extractKeyPoints(List<String> sentences, List<String> actionItems) {
        List<String> candidates = sentences.stream()
                .filter(s -> s.split("\\s+").length >= 4)
                .filter(s -> !actionItems.contains(ensurePeriod(s)))
                .toList();
        int threshold = candidates.stream()
                .mapToInt(String::length)
                .sorted()
                .skip(Math.max(0, candidates.size() - 5))
                .findFirst()
                .orElse(0);
        return candidates.stream()
                .filter(s -> s.length() >= threshold)
                .limit(5)
                .map(this::ensurePeriod)
                .toList();
    }

    private String buildSummary(String title, List<String> sentences, DraftOptions options) {
        if (sentences.isEmpty()) {
            return "No notes were captured for this draft.";
        }
        String opener = switch (options.tone()) {
            case FRIENDLY -> "Here's a quick rundown of what was covered on \"%s\".";
            case CONCISE -> "Summary of \"%s\":";
            case PERSUASIVE -> "The following draft on \"%s\" makes the case captured in the notes.";
            case PROFESSIONAL -> "This draft consolidates the notes captured on \"%s\".";
        };
        String lead = sentences.getFirst();
        return opener.formatted(title) + " " + ensurePeriod(lead)
                + (sentences.size() > 1
                        ? " It covers %d captured points in total.".formatted(sentences.size())
                        : "");
    }

    private List<Draft.Section> buildSections(String title, List<String> sentences,
                                              List<String> keyPoints, List<String> actionItems,
                                              DraftOptions options) {
        List<Draft.Section> sections = new ArrayList<>();
        String body = paragraphs(sentences);

        switch (options.contentType()) {
            case EMAIL -> {
                sections.add(new Draft.Section("Greeting", greeting(options.tone())));
                sections.add(new Draft.Section("Body", body));
                if (!actionItems.isEmpty()) {
                    sections.add(new Draft.Section("Requested actions", bulleted(actionItems)));
                }
                sections.add(new Draft.Section("Closing", closing(options.tone())));
            }
            case MEETING_NOTES -> {
                sections.add(new Draft.Section("Discussion", body));
                if (!keyPoints.isEmpty()) {
                    sections.add(new Draft.Section("Decisions and highlights", bulleted(keyPoints)));
                }
                sections.add(new Draft.Section("Action items",
                        actionItems.isEmpty() ? "No action items were captured." : bulleted(actionItems)));
            }
            case BLOG_POST -> {
                sections.add(new Draft.Section("Introduction",
                        "In this post we take a detailed look at " + StringUtils.uncapitalize(title) + "."));
                sections.add(new Draft.Section("Main content", body));
                if (!keyPoints.isEmpty()) {
                    sections.add(new Draft.Section("Key takeaways", bulleted(keyPoints)));
                }
            }
            case SUMMARY -> {
                if (!keyPoints.isEmpty()) {
                    sections.add(new Draft.Section("Key points", bulleted(keyPoints)));
                }
                sections.add(new Draft.Section("Details", body));
            }
            case DOCUMENT -> {
                sections.add(new Draft.Section("Overview",
                        "This document elaborates on " + StringUtils.uncapitalize(title)
                                + ", based on the notes captured during the listening session."));
                sections.add(new Draft.Section("Details", body));
                if (!keyPoints.isEmpty()) {
                    sections.add(new Draft.Section("Key points", bulleted(keyPoints)));
                }
                if (!actionItems.isEmpty()) {
                    sections.add(new Draft.Section("Next steps", bulleted(actionItems)));
                }
            }
        }
        return sections;
    }

    /** Group sentences into paragraphs of at most three for readability. */
    private String paragraphs(List<String> sentences) {
        if (sentences.isEmpty()) {
            return "No content was captured.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            sb.append(ensurePeriod(sentences.get(i)));
            boolean endOfParagraph = (i + 1) % 3 == 0;
            if (i < sentences.size() - 1) {
                sb.append(endOfParagraph ? "\n\n" : " ");
            }
        }
        return sb.toString();
    }

    private String greeting(DraftOptions.Tone tone) {
        return switch (tone) {
            case FRIENDLY -> "Hi there,";
            case CONCISE -> "Hello,";
            case PERSUASIVE, PROFESSIONAL -> "Dear recipient,";
        };
    }

    private String closing(DraftOptions.Tone tone) {
        return switch (tone) {
            case FRIENDLY -> "Thanks so much!\n\nBest,\n[Your name]";
            case CONCISE -> "Thanks,\n[Your name]";
            case PERSUASIVE -> "I look forward to your response.\n\nSincerely,\n[Your name]";
            case PROFESSIONAL -> "Kind regards,\n[Your name]";
        };
    }

    private String bulleted(List<String> items) {
        return String.join("\n", items.stream().map(i -> "- " + i).toList());
    }

    private String ensurePeriod(String sentence) {
        String s = sentence.strip();
        return s.matches(".*[.!?]$") ? s : s + ".";
    }

    private String renderFullText(String title, String summary, List<Draft.Section> sections) {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n").append(summary);
        for (Draft.Section section : sections) {
            sb.append("\n\n## ").append(section.heading()).append("\n\n").append(section.body());
        }
        return sb.toString();
    }
}
