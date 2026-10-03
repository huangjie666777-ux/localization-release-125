package dev.localization;

import java.util.Locale;
import java.util.regex.Pattern;

/** BCP 47 language tag validation. */
public final class LangTags {

    // RFC 5646 "Language-Tag" ABNF (case-insensitive); hyphen is the only separator.
    private static final Pattern BCP47 = Pattern.compile(
            "(?:"
            // langtag
            + "(?:[a-z]{2,3}(?:-[a-z]{3}){0,3}|[a-z]{4}|[a-z]{5,8})"
            + "(?:-[a-z]{4})?"
            + "(?:-(?:[a-z]{2}|[0-9]{3}))?"
            + "(?:-(?:[a-z0-9]{5,8}|[0-9][a-z0-9]{3}))*"
            + "(?:-[0-9a-wy-z](?:-[a-z0-9]{2,8})+)*"
            + "(?:-x(?:-[a-z0-9]{1,8})+)?"
            + "|"
            // private-use-only
            + "x(?:-[a-z0-9]{1,8})+"
            + ")",
            Pattern.CASE_INSENSITIVE);

    private LangTags() {
    }

    /** @return true when the tag is a syntactically valid BCP 47 tag. */
    public static boolean isValid(String tag) {
        if (tag == null || tag.isEmpty() || tag.length() > 64) {
            return false;
        }
        if (!BCP47.matcher(tag).matches()) {
            return false;
        }
        if (!primaryShapeValid(tag)) {
            return false;
        }
        // Round-trip through the JDK parser to reject residual malformed constructs.
        Locale locale = Locale.forLanguageTag(tag);
        String language = locale.getLanguage();
        if (language == null || language.isEmpty() || "und".equals(language)) {
            return false;
        }
        String back = locale.toLanguageTag();
        if (back == null || back.isEmpty() || "und".equals(back)) {
            return false;
        }
        return compact(tag).equals(compact(back));
    }

    private static String compact(String tag) {
        return tag.toLowerCase(Locale.ROOT).replace("-", "");
    }

    /** Registered irregular/simple-language primary subtags with 5+ letters (RFC 5646 2.2.2). */
    private static final java.util.Set<String> GRANDFATHERED_PRIMARY = java.util.Set.of(
            "i-ami", "i-bnn", "i-default", "i-enochian", "i-hak", "i-klingon", "i-lux",
            "i-mingo", "i-navajo", "i-pwn", "i-tao", "i-tay", "i-tsu", "sgn-BE-FR",
            "sgn-BE-NL", "sgn-CH-DE", "art-lojban", "cel-gaulish", "no-bok", "no-nyn",
            "zh-guoyu", "zh-hakka", "zh-min", "zh-min-nan", "zh-xiang");

    private static boolean primaryShapeValid(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        String primary = lower.split("-", 2)[0];
        int len = primary.length();
        if (len == 2 || len == 3) {
            return primary.matches("[a-z]{2,3}");
        }
        if (len == 4) {
            return primary.matches("[a-z]{4}"); // reserved-for-future script-shaped primary
        }
        // 5-8 letter primaries are valid only when the whole tag is a registered
        // grandfathered tag; bare words like "english" must be rejected.
        return GRANDFATHERED_PRIMARY.contains(lower);
    }
}
