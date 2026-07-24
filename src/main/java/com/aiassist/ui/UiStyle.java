package com.aiassist.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import java.util.Set;

/**
 * One shared source for the desktop UI's look — font family, sizes, button
 * size, the link color — so every tab and every OS draws from the exact same
 * values instead of scattered literals.
 *
 * <p>Sizes and colors are plain numbers; the same Java source runs unchanged
 * on every OS, so they were never actually OS-dependent. The font FAMILY is
 * different: Swing's logical name {@code Font.SANS_SERIF} is substituted by
 * the JVM with a different real font per OS (and macOS's substitute has
 * rendered visibly heavier than Windows's at the same nominal PLAIN style).
 * Resolving one explicit, real installed family per OS up front — instead of
 * a logical name each platform maps differently — is what keeps text weight
 * consistent everywhere.
 */
final class UiStyle {

    static final String FONT_FAMILY = resolveFontFamily();
    static final float BODY_SIZE = 13f;
    static final float SMALL_SIZE = 11f;
    static final Color LINK_COLOR = new Color(0x3B82F6);
    static final Dimension BUTTON_SIZE = new Dimension(80, 28);

    // Dropdowns are deliberately kept light in both themes so their down-arrow
    // stays visible in dark mode.
    static final Color DROPDOWN_BACKGROUND = new Color(0xF2F2F2);
    static final Color DROPDOWN_FOREGROUND = new Color(0x1A1A1A);

    private UiStyle() {
    }

    static Font font(int style, float size) {
        return new Font(FONT_FAMILY, style, Math.round(size));
    }

    /** The main text areas' (transcript, summary, notes fields) background. */
    static Color textBackground(boolean dark) {
        return dark ? new Color(0x1E1E1E) : Color.WHITE;
    }

    /** The main text areas' foreground, and labels/checkboxes that follow the theme. */
    static Color textForeground(boolean dark) {
        return dark ? new Color(0xE6E6E6) : Color.BLACK;
    }

    /** Background for panels/tab strips/split-pane dividers. */
    static Color panelBackground(boolean dark) {
        return dark ? new Color(0x2B2B2B) : new Color(0xF2F2F2);
    }

    /** De-emphasized text (status captions, hints). */
    static Color mutedText(boolean dark) {
        return dark ? new Color(0x9A9A9A) : Color.GRAY;
    }

    /** Rounded-button fill. */
    static Color buttonBackground(boolean dark) {
        return dark ? new Color(0x3C4043) : new Color(0xE8E8E8);
    }

    /**
     * Primary near-black / near-white text — button labels, non-"you"
     * transcript speech, and anywhere else plain (non-link, non-muted) text
     * needs to sit on the theme's panel/button color rather than the main
     * text areas' own {@link #textForeground}.
     */
    static Color primaryText(boolean dark) {
        return dark ? new Color(0xE6E6E6) : new Color(0x1A1A1A);
    }

    /** Rounded-button text (not applied to indicator buttons, which keep their own color). */
    static Color buttonForeground(boolean dark) {
        return primaryText(dark);
    }

    /** The active tab's label color; other tabs use {@link #textForeground}-like plain text. */
    static Color activeTabColor(boolean dark) {
        return dark ? new Color(0x69D08A) : new Color(0x2E7D32);
    }

    /** An inactive tab's label color. */
    static Color inactiveTabColor(boolean dark) {
        return dark ? new Color(0xC8C8C8) : new Color(0x1A1A1A);
    }

    private static String resolveFontFamily() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] candidates = os.contains("mac")
                ? new String[] {"Helvetica Neue", "Helvetica"}
                : os.contains("win")
                        ? new String[] {"Segoe UI"}
                        : new String[] {"DejaVu Sans", "Noto Sans", "Liberation Sans"};
        try {
            Set<String> available = Set.of(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            for (String candidate : candidates) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            // headless/unusual environment: fall back to the logical name below
        }
        return Font.SANS_SERIF;
    }
}
