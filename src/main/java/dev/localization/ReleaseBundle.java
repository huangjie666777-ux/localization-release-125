package dev.localization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fully validated, immutable snapshot of one published batch.
 * Every version is self-contained; old versions are never mutated later.
 */
public final class ReleaseBundle {
    private final int version;
    private final String baseLanguage;
    private final Map<String, String> fallbacks;
    private final Map<String, Map<String, String>> messages;
    private final long publishedAt;

    public ReleaseBundle(int version,
                         String baseLanguage,
                         Map<String, String> fallbacks,
                         Map<String, Map<String, String>> messages,
                         long publishedAt) {
        this.version = version;
        this.baseLanguage = baseLanguage;
        this.fallbacks = Collections.unmodifiableMap(new LinkedHashMap<>(fallbacks));
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        messages.forEach((lang, map) -> copy.put(lang, Collections.unmodifiableMap(new LinkedHashMap<>(map))));
        this.messages = Collections.unmodifiableMap(copy);
        this.publishedAt = publishedAt;
    }

    public int version() {
        return version;
    }

    public String baseLanguage() {
        return baseLanguage;
    }

    /** Explicit fallback: language -> next language to try. */
    public Map<String, String> fallbacks() {
        return fallbacks;
    }

    /** language -> (key -> template), only templates explicitly supplied. */
    public Map<String, Map<String, String>> messages() {
        return messages;
    }

    public long publishedAt() {
        return publishedAt;
    }
}
