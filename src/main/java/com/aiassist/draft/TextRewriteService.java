package com.aiassist.draft;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Offline text rewriting for the Editor tab: deterministic, rule-based, no
 * internet — the same on Windows and macOS. Three modes: light-touch grammar
 * tidying, compaction (fillers and wordy phrases removed), and expansion
 * into a detailed structured document via the drafting engine.
 */
@Service
public class TextRewriteService {

    public enum Mode { GRAMMAR, COMPACT, DETAILED }

    private static final Pattern DUPLICATE_WORD =
            Pattern.compile("\\b(\\p{L}+)(\\s+)\\1\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONELY_I = Pattern.compile("\\bi\\b");
    private static final Pattern SPACE_BEFORE_PUNCT = Pattern.compile("\\s+([,.;:!?])");
    private static final Pattern MISSING_SPACE_AFTER = Pattern.compile("([,;:])(\\p{L})");
    private static final Pattern REPEAT_PUNCT = Pattern.compile("([!?.,])\\1+");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern SENTENCE_START =
            Pattern.compile("(^|[.!?]\\s+)(\\p{Ll})", Pattern.MULTILINE);
    private static final Pattern A_BEFORE_VOWEL =
            Pattern.compile("\\b([Aa]) (?=[aeiouAEIOU]\\p{L})");

    private static final Pattern FILLERS = Pattern.compile(
            "\\b(basically|actually|really|very|quite|just|simply|literally|"
            + "kind of|sort of|you know|i mean|needless to say|as a matter of fact)\\b\\s*",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> WORDY_PHRASES = Map.of(
            "in order to", "to",
            "due to the fact that", "because",
            "at this point in time", "now",
            "in the event that", "if",
            "for the purpose of", "for",
            "with regard to", "about",
            "a large number of", "many",
            "the majority of", "most",
            "in spite of the fact that", "although",
            "it is important to note that", "");

    private final TemplateContentDrafter drafter;

    public TextRewriteService(TemplateContentDrafter drafter) {
        this.drafter = drafter;
    }

    public String rewrite(String text, Mode mode) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return switch (mode) {
            case GRAMMAR -> tidy(text);
            case COMPACT -> tidy(compact(text));
            case DETAILED -> drafter.draft("Detailed version", text,
                    new DraftOptions(DraftOptions.ContentType.DOCUMENT,
                            DraftOptions.Tone.PROFESSIONAL)).fullText();
        };
    }

    /** Light-touch grammar tidying; never rewrites meaning. */
    private String tidy(String text) {
        String result = MULTI_SPACE.matcher(text).replaceAll(" ");
        result = SPACE_BEFORE_PUNCT.matcher(result).replaceAll("$1");
        result = MISSING_SPACE_AFTER.matcher(result).replaceAll("$1 $2");
        result = REPEAT_PUNCT.matcher(result).replaceAll("$1");
        result = DUPLICATE_WORD.matcher(result).replaceAll("$1");
        result = LONELY_I.matcher(result).replaceAll("I");
        result = A_BEFORE_VOWEL.matcher(result).replaceAll(m ->
                m.group(1).equals("A") ? "An " : "an ");
        result = capitalizeSentences(result);
        result = result.strip();
        if (!result.isEmpty() && Character.isLetterOrDigit(result.charAt(result.length() - 1))) {
            result += ".";
        }
        return result;
    }

    private String compact(String text) {
        String result = text;
        for (Map.Entry<String, String> phrase : WORDY_PHRASES.entrySet()) {
            result = Pattern.compile("\\b" + Pattern.quote(phrase.getKey()) + "\\b",
                            Pattern.CASE_INSENSITIVE)
                    .matcher(result).replaceAll(phrase.getValue());
        }
        return FILLERS.matcher(result).replaceAll("");
    }

    private String capitalizeSentences(String text) {
        Matcher matcher = SENTENCE_START.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + matcher.group(2).toUpperCase(Locale.ROOT)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
