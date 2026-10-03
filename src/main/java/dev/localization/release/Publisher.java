package dev.localization.release;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validates one whole submission and, if everything is valid, publishes it atomically. */
public final class Publisher {

    public static final class Batch {
        public String base;
        /** Preserves request insertion order; every value is a String. */
        public LinkedHashMap<String, LinkedHashMap<String, String>> messages;
        public LinkedHashMap<String, String> fallback;
    }

    private final VersionStore store;

    public Publisher(VersionStore store) {
        this.store = store;
    }

    public long expectedCurrentVersion() {
        return store.currentVersion();
    }

    /** Validates the batch fully; on any error throws IllegalArgumentException and nothing changes. */
    public Snapshot publish(long expectedVersion, Batch batch) {
        List<String> errors = new ArrayList<>();

        if (batch == null) throw new IllegalArgumentException("request body is required");
        if (batch.messages == null || batch.messages.isEmpty()) {
            throw new IllegalArgumentException("field 'messages' must contain at least the base language");
        }
        if (batch.fallback == null) batch.fallback = new LinkedHashMap<>();

        String base;
        try {
            base = LanguageTags.requireValid(batch.base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("base language invalid: " + e.getMessage());
        }
        if (!batch.messages.containsKey(base)) {
            throw new IllegalArgumentException(
                    "base language '" + base + "' has no message table in 'messages'");
        }
        if (batch.messages.size() > Limits.MAX_LANGUAGES) {
            throw new IllegalArgumentException(
                    "too many languages (" + batch.messages.size() + " > " + Limits.MAX_LANGUAGES + ")");
        }

        // Validate language tags and normalize table keys.
        LinkedHashMap<String, LinkedHashMap<String, String>> normalized = new LinkedHashMap<>();
        Set<String> seenTags = new HashSet<>();
        int totalEntries = 0;
        for (Map.Entry<String, LinkedHashMap<String, String>> e : batch.messages.entrySet()) {
            String tag;
            try {
                tag = LanguageTags.requireValid(e.getKey());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(ex.getMessage());
            }
            if (!seenTags.add(tag)) {
                throw new IllegalArgumentException("duplicate language tag after normalization: " + tag);
            }
            LinkedHashMap<String, String> table = e.getValue();
            if (table == null) {
                throw new IllegalArgumentException("message table for '" + tag + "' is null");
            }
            if (tag.equals(base)) {
                if (table.isEmpty()) {
                    throw new IllegalArgumentException("base language must contain at least one key");
                }
                if (table.size() > Limits.MAX_BASE_KEYS) {
                    throw new IllegalArgumentException(
                            "too many base keys (" + table.size() + " > " + Limits.MAX_BASE_KEYS + ")");
                }
            }
            totalEntries += table.size();
            normalized.put(tag, table);
        }
        if (totalEntries > Limits.MAX_TOTAL_ENTRIES) {
            throw new IllegalArgumentException(
                    "too many total message entries (" + totalEntries + " > " + Limits.MAX_TOTAL_ENTRIES + ")");
        }

        // Base keys: non-empty, unique (LinkedHashMap already rejects dup at JSON-parse level).
        List<String> baseKeys = new ArrayList<>(normalized.get(base).keySet());
        for (String key : baseKeys) {
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("keys must be non-empty");
        }
        Set<String> baseKeySet = new HashSet<>(baseKeys);
        if (baseKeySet.size() != baseKeys.size()) {
            throw new IllegalArgumentException("duplicate keys in base language");
        }

        // Every other language: only keys also present in base. Duplicate key checks per table.
        for (Map.Entry<String, LinkedHashMap<String, String>> e : normalized.entrySet()) {
            String tag = e.getKey();
            if (tag.equals(base)) continue;
            LinkedHashMap<String, String> table = e.getValue();
            Set<String> localSeen = new HashSet<>();
            for (String key : table.keySet()) {
                if (key == null || key.isEmpty()) {
                    throw new IllegalArgumentException("keys must be non-empty (" + tag + ")");
                }
                if (!localSeen.add(key)) {
                    throw new IllegalArgumentException("duplicate key '" + key + "' in " + tag);
                }
                if (!baseKeySet.contains(key)) {
                    throw new IllegalArgumentException(
                            "language '" + tag + "' contains key '" + key + "' absent from base '" + base + "'");
                }
            }
        }

        // Validate fallback declarations.
        LinkedHashMap<String, String> fb = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : batch.fallback.entrySet()) {
            String from = LanguageTags.requireValid(e.getKey());
            String to = LanguageTags.requireValid(e.getValue());
            if (!normalized.containsKey(from)) {
                throw new IllegalArgumentException(
                        "fallback declared for unknown language '" + from + "'");
            }
            if (from.equals(base)) {
                throw new IllegalArgumentException("base language must not declare a fallback");
            }
            fb.put(from, to);
        }
        FallbackResolver.validateChains(base, normalized.keySet(), fb);

        // Parse every template and compare argument schema with base. Different plural branches
        // are allowed (branch text differs), but the set/kinds of arguments must match.
        Map<String, TemplateAnalyzer.Analysis> baseAnalysis = new LinkedHashMap<>();
        for (String key : baseKeys) {
            baseAnalysis.put(key, TemplateAnalyzer.analyze(normalized.get(base).get(key)));
        }
        for (Map.Entry<String, LinkedHashMap<String, String>> e : normalized.entrySet()) {
            String tag = e.getKey();
            for (Map.Entry<String, String> kv : e.getValue().entrySet()) {
                TemplateAnalyzer.Analysis a;
                try {
                    a = TemplateAnalyzer.analyze(kv.getValue());
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "[" + tag + "] '" + kv.getKey() + "': " + ex.getMessage());
                }
                TemplateAnalyzer.Analysis expected = baseAnalysis.get(kv.getKey());
                if (!expected.sameSchema(a)) {
                    throw new IllegalArgumentException(
                            "[" + tag + "] '" + kv.getKey() + "' argument schema differs from base: "
                                    + "base=" + expected.args + ", translation=" + a.args);
                }
            }
        }

        // All checks passed: attempt atomic conditional publication.
        Optional<Snapshot> result = store.publish(expectedVersion, base, fb, normalized);
        if (result.isEmpty()) {
            throw new VersionConflictException(expectedVersion, store.currentVersion());
        }
        return result.get();
    }

    public static class VersionConflictException extends RuntimeException {
        public final long expected;
        public final long actual;

        public VersionConflictException(long expected, long actual) {
            super("expected version " + expected + " but current version is " + actual);
            this.expected = expected;
            this.actual = actual;
        }
    }
}
