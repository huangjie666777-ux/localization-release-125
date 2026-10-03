package dev.localization.release;

import com.ibm.icu.util.ULocale;

import java.util.regex.Pattern;

/** Strict BCP 47 language-tag validation (RFC 5646). */
public final class LanguageTags {
    private LanguageTags() {}

    private static final String ALPHA2 = "[A-Za-z]{2,3}";
    private static final String ALPHA4 = "[A-Za-z]{4}";
    private static final String ALPHA58 = "[A-Za-z]{5,8}";
    private static final String LANGUAGE = "(" + ALPHA2 + "(-[A-Za-z]{3}){0,3}|" + ALPHA4 + "|" + ALPHA58 + ")";
    private static final String SCRIPT = "[A-Za-z]{4}";
    private static final String REGION = "([A-Za-z]{2}|[0-9]{3})";
    private static final String VARIANT = "([A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3})";
    private static final String EXTENSION = "[0-9A-WY-Za-wy-z](-[A-Za-z0-9]{2,8})+";
    private static final String PRIVATEUSE = "x(-[A-Za-z0-9]{1,8})+";

    private static final String GRANDFATHERED = String.join("|",
            "en-GB-oed", "i-ami", "i-bnn", "i-default", "i-enochian", "i-hak", "i-klingon",
            "i-lux", "i-mingo", "i-navajo", "i-pwn", "i-tao", "i-tay", "i-tsu",
            "sgn-BE-FR", "sgn-BE-NL", "sgn-CH-DE",
            "art-lojban", "cel-gaulish", "no-bok", "no-nyn", "zh-guoyu", "zh-hakka",
            "zh-min", "zh-min-nan", "zh-xiang");

    private static final Pattern BCP47 = Pattern.compile(
            "(" + LANGUAGE + "(-" + SCRIPT + ")?(-" + REGION + ")?(-" + VARIANT + ")*"
                    + "(-" + EXTENSION + ")*(-" + PRIVATEUSE + ")?"
                    + "|" + PRIVATEUSE + "|" + GRANDFATHERED + ")");

    /** Returns the canonical tag, or throws IllegalArgumentException. */
    public static String requireValid(String tag) {
        if (tag == null || tag.isEmpty() || !BCP47.matcher(tag).matches()) {
            throw new IllegalArgumentException("invalid BCP 47 language tag: " + tag);
        }
        String canonical = ULocale.forLanguageTag(tag).toLanguageTag();
        if (canonical == null || canonical.isEmpty() || "und".equals(canonical)) {
            // Grandfathered tags recognized by the regex but unresolved by ICU: accept as-is.
            return tag;
        }
        return canonical;
    }
}
