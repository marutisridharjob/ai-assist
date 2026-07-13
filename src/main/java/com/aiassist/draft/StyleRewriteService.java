package com.aiassist.draft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final TextRewriteService textRewrite;
    private final ObjectProvider<OllamaStyleRewriter> ollama;

    public StyleRewriteService(TextRewriteService textRewrite,
                               ObjectProvider<OllamaStyleRewriter> ollama) {
        this.textRewrite = textRewrite;
        this.ollama = ollama;
    }

    /** True when the optional local LLM handles free-form instructions. */
    public boolean llmAvailable() {
        return ollama.getIfAvailable() != null;
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
        OllamaStyleRewriter llm = ollama.getIfAvailable();
        if (llm != null) {
            try {
                StringBuilder request = new StringBuilder();
                for (Style style : styles) {
                    request.append("use a ").append(style.display()).append(" communication style; ");
                }
                if (instructions != null && !instructions.isBlank()) {
                    request.append(instructions.strip());
                }
                return llm.freeform(text, request.isEmpty() ? "improve clarity" : request.toString());
            } catch (RuntimeException e) {
                log.warn("Ollama drafting failed ({}); using the rule-based recipes", e.getMessage());
            }
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
        OllamaStyleRewriter llm = ollama.getIfAvailable();
        if (llm != null) {
            try {
                StringBuilder request = new StringBuilder();
                if (grammar) {
                    request.append("fix all grammar; ");
                }
                if (compact) {
                    request.append("make it compact; ");
                }
                if (detailed) {
                    request.append("make it more detailed; ");
                }
                if (professional) {
                    request.append("use professional wording; ");
                }
                if (bullets) {
                    request.append("format the key content as bullet points; ");
                }
                for (Style style : styles) {
                    request.append("use a ").append(style.display()).append(" communication style; ");
                }
                if (instructions != null && !instructions.isBlank()) {
                    request.append(instructions.strip());
                }
                return llm.freeform(text, request.isEmpty() ? "improve clarity" : request.toString());
            } catch (RuntimeException e) {
                log.warn("Ollama editing failed ({}); using the rule-based recipes", e.getMessage());
            }
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
