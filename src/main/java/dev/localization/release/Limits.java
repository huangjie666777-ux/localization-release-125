package dev.localization.release;

/** Hard limits enforced on every submission. */
public final class Limits {
    private Limits() {}

    public static final int MAX_BODY_BYTES = 1_048_576;
    public static final int MAX_BASE_KEYS = 1_000;
    public static final int MAX_LANGUAGES = 100;
    public static final int MAX_TOTAL_ENTRIES = 20_000;
    public static final int MAX_TEMPLATE_LENGTH = 10_000;
    public static final int MAX_NESTING_DEPTH = 32;
}
