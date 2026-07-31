package com.aiassist.draft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Drafts pasted content in a chosen communication style, offline and
 * identical on Windows and macOS. Grammar is tidied first, then a
 * deterministic per-style recipe is applied, built from five dials:
 * contractions (expand/contract), hedging (strip/soften), lexicon swaps
 * (formal/casual), framing lines (opener/closer), and structure (numbered
 * analysis, compaction). When the optional local Ollama LLM is enabled in
 * configuration, it drafts instead — with automatic fallback to the rules.
 */
@Service
public class StyleRewriteService {

    public enum Style {
        FORMAL, CONCISE, CONSULTATIVE, DIPLOMATIC, COMMANDING, PERSUASIVE, EMPATHETIC,
        TRANSPARENT, CONVERSATIONAL, CASUAL, DIRECT, ANALYTICAL, ASSERTIVE;

        public String display() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(StyleRewriteService.class);

    private static final Map<String, String> EXPAND_CONTRACTIONS = mapOf(
            "don't", "do not", "doesn't", "does not", "didn't", "did not", "can't", "cannot",
            "won't", "will not", "isn't", "is not", "aren't", "are not", "wasn't", "was not",
            "couldn't", "could not", "shouldn't", "should not", "wouldn't", "would not",
            "I'm", "I am", "we're", "we are", "you're", "you are", "they're", "they are",
            "it's", "it is", "that's", "that is", "there's", "there is", "let's", "let us",
            "I'll", "I will", "we'll", "we will", "you'll", "you will",
            "I've", "I have", "we've", "we have", "haven't", "have not");

    private static final Map<String, String> CONTRACT = mapOf(
            "do not", "don't", "does not", "doesn't", "did not", "didn't", "cannot", "can't",
            "will not", "won't", "is not", "isn't", "are not", "aren't",
            "could not", "couldn't", "should not", "shouldn't", "would not", "wouldn't",
            "I am", "I'm", "we are", "we're", "you are", "you're", "it is", "it's",
            "that is", "that's", "there is", "there's", "I will", "I'll", "we will", "we'll",
            "I have", "I've", "we have", "we've", "have not", "haven't");

    private static final Map<String, String> FORMAL_WORDS = mapOf(
            "get", "obtain", "got", "received", "buy", "purchase", "need", "require",
            "needs", "requires", "help", "assist", "start", "commence", "end", "conclude",
            "ask", "request", "tell", "inform", "show", "demonstrate", "also", "additionally",
            "kids", "children", "thanks", "thank you", "a lot of", "a great deal of");

    private static final Map<String, String> CASUAL_WORDS = mapOf(
            "obtain", "get", "purchase", "buy", "require", "need", "requires", "needs",
            "assist", "help", "assistance", "help", "commence", "start", "conclude", "end", "request", "ask",
            "inform", "tell", "demonstrate", "show", "additionally", "also",
            "children", "kids", "excellent", "great", "hello", "hey");

    private static final Map<String, String> DIPLOMATIC_SOFTENERS = mapOf(
            "you must", "you may wish to", "you should", "you might consider",
            "you need to", "it would help to", "problem", "challenge",
            "wrong", "not quite right", "bad", "less than ideal",
            "failed", "fell short", "mistake", "oversight", "refuse", "prefer not");

    private static final Pattern HEDGES = Pattern.compile(
            "\\b(maybe|perhaps|possibly|I think|I believe|I feel like|it seems like|it seems|"
            + "sort of|kind of|hopefully|just)\\b,?\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern APOLOGIES = Pattern.compile(
            "\\b(I'm sorry|I am sorry|sorry|I apologize)\\b[,.]?\\s*(for [^,.]*[,.]?\\s*)?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern POLITE_ASK = Pattern.compile(
            "\\b(could you possibly|could you|can you|would you mind|would you)\\b\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+|\\n+");

    // Generous enough that a real meeting's full Key points + Action items
    // lists don't get cut off mid-list — 900 was tuned for a short paragraph,
    // not an hour-long conversation with a dozen-plus action items.
    private static final int SUMMARY_TOKENS = 1800;
    private static final int REWRITE_TOKENS = 1200;

    private final TextRewriteService textRewrite;
    private final ObjectProvider<OllamaStyleRewriter> ollama;
    private final ContentDrafter drafter;
    private final LocalLlmService localLlm;
    // Concrete (not the ContentDrafter interface, which OllamaContentDrafter can
    // become @Primary for): its deterministic, regex-based action-item scan is
    // always available regardless of which drafter is otherwise configured, and
    // is used as a completeness safety net on the LLM summary path below.
    private final TemplateContentDrafter templateDrafter;

    public StyleRewriteService(TextRewriteService textRewrite,
                               ObjectProvider<OllamaStyleRewriter> ollama,
                               ContentDrafter drafter,
                               LocalLlmService localLlm,
                               TemplateContentDrafter templateDrafter) {
        this.textRewrite = textRewrite;
        this.ollama = ollama;
        this.drafter = drafter;
        this.localLlm = localLlm;
        this.templateDrafter = templateDrafter;
    }

    /**
     * True when an LLM can act on free-form instructions — either the
     * in-process local model (a GGUF file next to the jar) or an enabled
     * local Ollama. When false, only the offline rules run.
     */
    public boolean llmAvailable() {
        return localLlm.isAvailable() || ollama.getIfAvailable() != null;
    }

    /** One-line status of the local model after the last run (for the UI). */
    public String llmReport() {
        return localLlm.report();
    }

    /** Where the app looked for a model and what it found (for the UI). */
    public String llmDescribe() {
        return localLlm.describe();
    }

    /**
     * Runs an LLM instruction over the text: the in-process local model first
     * (the user's dropped-in GGUF), then Ollama if enabled. Empty when neither
     * is available or both fail, so callers fall back to the offline rules.
     */
    private Optional<String> runLlm(String instruction, String text, int maxTokens) {
        Optional<String> local = localLlm.generate(instruction, text, maxTokens);
        if (local.isPresent()) {
            return local;
        }
        OllamaStyleRewriter o = ollama.getIfAvailable();
        if (o != null) {
            try {
                return Optional.ofNullable(o.freeform(text, instruction))
                        .map(String::strip)
                        .filter(s -> !s.isBlank());
            } catch (RuntimeException e) {
                log.warn("Ollama request failed ({}); using the offline rules", e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * Turns the given text (a meeting transcript, pasted notes, anything) into
     * a detailed meeting summary with action points. When the optional local
     * LLM is enabled it writes the summary directly; otherwise the built-in
     * drafter produces the structured meeting notes (overview, decisions and
     * highlights, action items). Free-form instructions refine the LLM path.
     */
    /** Below this many words there is nothing to summarize; a small model would just hallucinate. */
    public static final int MIN_WORDS_TO_SUMMARIZE = 6;

    /** Word count of a transcript, 0 when blank. */
    public static int wordCount(String text) {
        String t = text == null ? "" : text.strip();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }

    public String summarizeMeeting(String text, String instructions) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (wordCount(text) < MIN_WORDS_TO_SUMMARIZE) {
            // Too little to summarize meaningfully — never hand a stray word or
            // two to the model, which would invent an unrelated "summary".
            return "Not enough was captured to summarize.";
        }
        StringBuilder request = new StringBuilder(
                "You are a meeting-notes assistant. Write a detailed, thorough summary of the "
                + "following meeting transcript — do not compress it down to only the highlights. "
                + "Start with a short Overview paragraph, then a 'Key points' section as a bulleted "
                + "list covering every topic discussed, then an 'Action items' section as a bulleted "
                + "list with the owner and any due date when they are mentioned. List every action "
                + "item mentioned anywhere in the transcript, including small or informal ones, not "
                + "just the first few — do not omit any. Use plain text.");
        if (instructions != null && !instructions.isBlank()) {
            request.append(" Also: ").append(instructions.strip());
        }
        Optional<String> llm = runLlm(request.toString(), text, SUMMARY_TOKENS);
        if (llm.isPresent()) {
            return appendMissedActionItems(llm.get(), text);
        }
        return offlineSummary(text);
    }

    /**
     * Cross-checks the LLM's summary against a deterministic, pattern-based
     * scan of the transcript ({@link TemplateContentDrafter#detectActionItems})
     * and appends anything the scan found that isn't already substantially
     * present in the summary's own text, under its own clearly-labeled
     * heading. An LLM is a judgement call on what to include and a small
     * model especially can drop a real commitment; the deterministic scan
     * can't skip a pattern match, so this guarantees nothing found that way
     * is silently lost even if the LLM's own "Action items" section missed it.
     */
    String appendMissedActionItems(String llmSummary, String transcript) {
        List<String> detected = templateDrafter.detectActionItems(transcript);
        if (detected.isEmpty()) {
            return llmSummary;
        }
        String normalizedSummary = normalizeForMatch(llmSummary);
        List<String> missed = detected.stream()
                .filter(item -> !normalizedSummary.contains(normalizeForMatch(item)))
                .toList();
        if (missed.isEmpty()) {
            return llmSummary;
        }
        StringBuilder out = new StringBuilder(llmSummary.strip()).append(
                "\n\nAction items detected directly in the transcript (automatic check, in case "
                + "the summary above missed any):\n");
        for (String item : missed) {
            out.append("- ").append(item).append("\n");
        }
        return out.toString().strip();
    }

    private static String normalizeForMatch(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /**
     * The same meeting summary, skipping the LLM entirely — instant and always
     * available, since it's pure Java with no native model call. Used as a
     * bounded-time fallback when the LLM is taking unreasonably long (see
     * MeetingEndService.finishNotes): ending a meeting must always finish and
     * save something in bounded time, never block indefinitely on a slow or
     * stuck native call.
     */
    public String offlineSummary(String text) {
        Draft draft = drafter.draft("Meeting notes", text,
                new DraftOptions(DraftOptions.ContentType.MEETING_NOTES, DraftOptions.Tone.PROFESSIONAL));
        return draft.fullText();
    }

    /** Grammar-corrected draft of the text in the requested style. */
    public String draft(String text, Style style) {
        return applyStyles(text, List.of(style), null);
    }

    /**
     * Applies every selected style in order, plus free-form instructions.
     * Instructions need the optional local LLM; the deterministic recipes
     * ignore them (the UI says so).
     */
    public String applyStyles(String text, List<Style> styles, String instructions) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder request = new StringBuilder("Rewrite the text below. ");
        for (Style style : styles) {
            request.append("Use a ").append(style.display()).append(" communication style. ");
        }
        if (instructions != null && !instructions.isBlank()) {
            request.append(instructions.strip()).append(". ");
        }
        request.append("Return only the rewritten text, no preamble.");
        Optional<String> llm = runLlm(request.toString(), text, REWRITE_TOKENS);
        if (llm.isPresent()) {
            return llm.get();
        }
        String result = textRewrite.rewrite(text, TextRewriteService.Mode.GRAMMAR);
        for (Style style : styles) {
            result = applyRules(result, style);
        }
        return result;
    }

    /**
     * Editor pipeline: the checked options applied in a sensible order
     * (grammar, compact, detailed, professional wording, bullet points),
     * plus free-form instructions when the local LLM is available.
     */
    public String applyEditor(String text, boolean grammar, boolean compact, boolean detailed,
                              boolean professional, boolean bullets, List<Style> styles,
                              String instructions) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder request = new StringBuilder("Edit the text below. ");
        if (grammar) {
            request.append("Fix all grammar, spelling and punctuation. ");
        }
        if (compact) {
            request.append("Make it more concise. ");
        }
        if (detailed) {
            request.append("Expand it with more detail. ");
        }
        if (professional) {
            request.append("Use professional wording. ");
        }
        if (bullets) {
            request.append("Format the key content as bullet points. ");
        }
        for (Style style : styles) {
            request.append("Use a ").append(style.display()).append(" communication style. ");
        }
        if (instructions != null && !instructions.isBlank()) {
            request.append(instructions.strip()).append(". ");
        }
        request.append("Return only the edited text, no preamble.");
        Optional<String> llm = runLlm(request.toString(), text, REWRITE_TOKENS);
        if (llm.isPresent()) {
            return llm.get();
        }
        String result = text;
        if (grammar) {
            result = textRewrite.rewrite(result, TextRewriteService.Mode.GRAMMAR);
        }
        if (compact) {
            result = textRewrite.rewrite(result, TextRewriteService.Mode.COMPACT);
        }
        if (detailed) {
            result = textRewrite.rewrite(result, TextRewriteService.Mode.DETAILED);
        }
        if (professional) {
            result = applyRules(result, Style.FORMAL);
        }
        for (Style style : styles) {
            result = applyRules(result, style);
        }
        if (bullets) {
            result = bulletize(result);
        }
        return result;
    }

    /** One sentence per bullet line. */
    private static String bulletize(String text) {
        StringBuilder out = new StringBuilder();
        SENTENCE_SPLIT.splitAsStream(text)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .forEach(s -> out.append(out.isEmpty() ? "" : "\n")
                        .append("• ").append(s.startsWith("• ") ? s.substring(2) : s));
        return out.toString();
    }

    private String applyRules(String tidy, Style style) {
        return switch (style) {
            case FORMAL -> replaceAll(replaceAll(tidy, EXPAND_CONTRACTIONS), FORMAL_WORDS)
                    .replace("!", ".");
            case CONCISE -> textRewrite.rewrite(tidy, TextRewriteService.Mode.COMPACT);
            case CONSULTATIVE -> frame(
                    replaceAll(tidy, mapOf("you should", "you might consider",
                            "you must", "you might consider", "you need to", "you might consider")),
                    "Here is my thinking — I would value your input:",
                    "What are your thoughts on this?");
            case DIPLOMATIC -> frame(replaceAll(tidy, DIPLOMATIC_SOFTENERS), null, null);
            case COMMANDING -> imperative(stripHedges(tidy));
            case PERSUASIVE -> frame(replaceAll(tidy, mapOf("should", "will want to")),
                    "Here is why this matters:",
                    "Acting on this now puts us ahead.");
            case EMPATHETIC -> frame(
                    replaceAll(tidy, mapOf("you must", "when you're ready, it would help to",
                            "you need to", "when you're ready, it would help to",
                            "you should", "when you're ready, it would help to")),
                    "I understand there is a lot going on.",
                    "Happy to help with any of this.");
            case TRANSPARENT -> frame(tidy, "To be fully transparent:",
                    "That is the complete picture as of today, including the open questions.");
            case CONVERSATIONAL -> frame(replaceAll(replaceAll(tidy, CONTRACT), CASUAL_WORDS),
                    "Here's the thing:", null);
            case CASUAL -> replaceAll(replaceAll(tidy, CONTRACT), CASUAL_WORDS);
            case DIRECT -> frame(textRewrite.rewrite(stripHedges(tidy),
                    TextRewriteService.Mode.COMPACT), "Bottom line:", null);
            case ANALYTICAL -> analytical(tidy);
            case ASSERTIVE -> APOLOGIES.matcher(
                    replaceAll(tidy, mapOf("I think", "I am confident", "I believe", "I am confident",
                            "I feel", "I am confident", "hopefully", "I expect")))
                    .replaceAll("").strip();
        };
    }

    private static String stripHedges(String text) {
        return HEDGES.matcher(text).replaceAll("").replaceAll(" {2,}", " ");
    }

    /** Turns "you/we should do X" and polite asks into direct instructions. */
    private static String imperative(String text) {
        String result = POLITE_ASK.matcher(text).replaceAll("please ");
        Matcher matcher = Pattern.compile(
                "(^|(?<=[.!?]\\s))(You|We|you|we)\\s+(should|must|need to|have to)\\s+(\\p{L})")
                .matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(4).toUpperCase(java.util.Locale.ROOT)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String analytical(String text) {
        List<String> sentences = SENTENCE_SPLIT.splitAsStream(text)
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (sentences.size() < 2) {
            return "Analysis:\n1. " + text;
        }
        StringBuilder out = new StringBuilder("Analysis:");
        for (int i = 0; i < sentences.size() - 1; i++) {
            out.append("\n").append(i + 1).append(". ").append(sentences.get(i));
        }
        return out.append("\n\nConclusion: ").append(sentences.getLast()).toString();
    }

    private static String frame(String body, String opener, String closer) {
        StringBuilder out = new StringBuilder();
        if (opener != null) {
            out.append(opener).append("\n\n");
        }
        out.append(body);
        if (closer != null) {
            out.append("\n\n").append(closer);
        }
        return out.toString();
    }

    /** Whole-word, case-insensitive replacement preserving a leading capital. */
    private static String replaceAll(String text, Map<String, String> replacements) {
        String result = text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(entry.getKey()) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(result);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String replacement = entry.getValue();
                if (!replacement.isEmpty() && Character.isUpperCase(matcher.group().charAt(0))) {
                    replacement = Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            result = out.toString();
        }
        return result;
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
