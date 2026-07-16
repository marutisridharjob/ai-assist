package com.aiassist.draft;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
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

    /** Strong commitment language, matched on word boundaries ("should" ≠ "shoulder"). */
    private static final Pattern STRONG_ACTION = Pattern.compile(
            "\\b(need(?:s)? to|have to|has to|had to|should|must|"
            + "plan(?:s|ned)? to|let's|make sure|remember to|don't forget|follow[- ]?up|"
            + "action item|to[- ]?do|assign(?:ed)?|responsible for|take care of|"
            + "circle back|get back to|due|deadline|by (?:next |this )?"
            + "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|eod|eow|end of))\\b",
            Pattern.CASE_INSENSITIVE);

    /** Future intent — only a commitment when the subject is an actor (see below). */
    private static final Pattern FUTURE_INTENT = Pattern.compile(
            "\\b(will|'ll|going to)\\b", Pattern.CASE_INSENSITIVE);

    /** "It will rain", "There will be…" — future tense without a responsible actor. */
    private static final Pattern NON_ACTOR_START = Pattern.compile(
            "^(it|there|that|this|they say|the weather)\\b", Pattern.CASE_INSENSITIVE);

    /** A sentence that opens with an imperative verb is usually a task. */
    private static final Pattern IMPERATIVE_START = Pattern.compile(
            "^(send|schedule|review|prepare|finali[sz]e|update|confirm|share|draft|create|"
            + "set up|organi[sz]e|email|call|check|fix|add|remove|write|complete|deliver|"
            + "submit|book|arrange|contact|reach out|investigate|research|plan|define|"
            + "document|test|deploy|assign)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Words that mark a decision or a substantive point worth surfacing. */
    private static final Pattern DECISION_PATTERN = Pattern.compile(
            "\\b(decid(?:e|ed|es)|agree(?:d|s)?|approv(?:e|ed|es)|confirm(?:ed)?|chose|chosen|"
            + "select(?:ed)?|decision|conclud(?:e|ed)|resolv(?:e|ed)|propos(?:e|ed|al)|"
            + "recommend(?:ed)?|priorit(?:y|ise|ize)|budget|deadline|risk|blocker|milestone|"
            + "launch|release|target|goal|improv(?:e|ed|ement)|increase|decrease|reduc(?:e|ed))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DIGIT = Pattern.compile("\\d");

    /** Lines that are pure filler carry no information and are dropped. */
    private static final Set<String> FILLER = Set.of(
            "yeah", "yes", "no", "nope", "okay", "ok", "right", "sure", "um", "uh", "hmm",
            "well", "so", "like", "exactly", "cool", "great", "thanks", "thank you", "hello",
            "hi", "hey", "bye", "mm", "mhm", "yep", "alright", "fine", "good", "nice", "oh", "huh");

    @Override
    public Draft draft(String topic, String transcript, DraftOptions options) {
        List<String> sentences = splitSentences(transcript);
        List<String> meaningful = meaningfulSentences(sentences);
        String title = buildTitle(topic, sentences);
        List<String> actionItems = extractActionItems(meaningful);
        List<String> keyPoints = extractKeyPoints(meaningful, actionItems);
        String summary = buildSummary(title, sentences, keyPoints, actionItems, options);
        List<Draft.Section> sections = buildSections(title, sentences, keyPoints, actionItems, options);
        String fullText = renderFullText(title, summary, sections);

        return new Draft(title, options.contentType().name(), options.tone().name(),
                summary, sections, keyPoints, actionItems, fullText, name(), Instant.now(), null);
    }

    /** Filler-free, de-duplicated sentences — the basis for points and actions. */
    private List<String> meaningfulSentences(List<String> sentences) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : sentences) {
            String norm = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
            if (norm.length() < 3 || FILLER.contains(norm)) {
                continue;
            }
            if (seen.add(norm)) {
                out.add(s);
            }
        }
        return out;
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

    /** Sentences that express a commitment or open with an imperative verb. */
    private List<String> extractActionItems(List<String> sentences) {
        List<String> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String sentence : sentences) {
            String s = sentence.strip();
            boolean strong = STRONG_ACTION.matcher(s).find() || IMPERATIVE_START.matcher(s).find();
            boolean futureCommit = FUTURE_INTENT.matcher(s).find() && !NON_ACTOR_START.matcher(s).find();
            if ((strong || futureCommit) && seen.add(s.toLowerCase(Locale.ROOT))) {
                items.add(ensurePeriod(s));
            }
        }
        return items;
    }

    /**
     * The most informative sentences become key points, ranked by signal
     * (numbers, decision words, named entities, sensible length) rather than
     * raw length — kept in the order they were spoken, up to five.
     */
    private List<String> extractKeyPoints(List<String> sentences, List<String> actionItems) {
        List<String> candidates = sentences.stream()
                .filter(s -> wordCount(s) >= 4)
                .filter(s -> !actionItems.contains(ensurePeriod(s)))
                .toList();

        record Ranked(int index, int score, String text) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ranked.add(new Ranked(i, scoreKeyPoint(candidates.get(i)), ensurePeriod(candidates.get(i))));
        }
        List<Ranked> chosen = ranked.stream()
                .filter(r -> r.score() >= 2)
                .sorted(Comparator.comparingInt(Ranked::score).reversed())
                .limit(5)
                .toList();
        if (chosen.isEmpty() && !ranked.isEmpty()) {
            // Low-signal transcript: fall back to the longest few sentences.
            chosen = ranked.stream()
                    .sorted(Comparator.comparingInt((Ranked r) -> r.text().length()).reversed())
                    .limit(3)
                    .toList();
        }
        return chosen.stream()
                .sorted(Comparator.comparingInt(Ranked::index))
                .map(Ranked::text)
                .toList();
    }

    /** Higher = more likely a substantive point. Deterministic, no model. */
    private int scoreKeyPoint(String sentence) {
        int score = 0;
        if (DIGIT.matcher(sentence).find()) {
            score += 3;
        }
        Matcher decisions = DECISION_PATTERN.matcher(sentence);
        while (decisions.find()) {
            score += 2;
        }
        String[] words = sentence.split("\\s+");
        for (int i = 1; i < words.length; i++) {
            if (words[i].length() > 1 && Character.isUpperCase(words[i].charAt(0))) {
                score += 1; // a named entity mentioned mid-sentence
                break;
            }
        }
        int wc = words.length;
        if (wc >= 6 && wc <= 40) {
            score += 1;
        } else if (wc < 4) {
            score -= 2;
        }
        return score;
    }

    private int wordCount(String sentence) {
        return sentence.strip().split("\\s+").length;
    }

    private String buildSummary(String title, List<String> sentences, List<String> keyPoints,
                                List<String> actionItems, DraftOptions options) {
        if (sentences.isEmpty()) {
            return "No notes were captured for this draft.";
        }
        String opener = switch (options.tone()) {
            case FRIENDLY -> "Here's a quick rundown of what was covered on \"%s\".";
            case CONCISE -> "Summary of \"%s\":";
            case PERSUASIVE -> "The following draft on \"%s\" makes the case captured in the notes.";
            case PROFESSIONAL -> "This draft consolidates the notes captured on \"%s\".";
        };
        String highlight = keyPoints.isEmpty() ? sentences.getFirst() : keyPoints.getFirst();
        String counts = " It captures %d key point%s and %d action item%s.".formatted(
                keyPoints.size(), keyPoints.size() == 1 ? "" : "s",
                actionItems.size(), actionItems.size() == 1 ? "" : "s");
        return opener.formatted(title) + " " + ensurePeriod(highlight) + counts;
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
                if (!keyPoints.isEmpty()) {
                    sections.add(new Draft.Section("Decisions and highlights", bulleted(keyPoints)));
                }
                sections.add(new Draft.Section("Action items",
                        actionItems.isEmpty() ? "No action items were captured." : bulleted(actionItems)));
                // Last, so it sits directly before the appended full transcript.
                sections.add(new Draft.Section("Discussion", body));
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
