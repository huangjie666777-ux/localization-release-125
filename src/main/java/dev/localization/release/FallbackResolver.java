package dev.localization.release;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates fallback declarations and resolves every key to its actual source language. */
public final class FallbackResolver {

    private FallbackResolver() {}

    /**
     * @param fallback declared fallback per non-base language; must not contain the base itself
     * @param languages all published language tags (includes base)
     * @throws IllegalArgumentException on unknown target, cycle or chain that never reaches base
     */
    public static void validateChains(String base, Set<String> languages,
                                      Map<String, String> fallback) {
        for (Map.Entry<String, String> e : fallback.entrySet()) {
            String from = e.getKey();
            String to = e.getValue();
            if (from.equals(base)) {
                throw new IllegalArgumentException("base language must not declare a fallback");
            }
            if (!languages.contains(to)) {
                throw new IllegalArgumentException(
                        "fallback of '" + from + "' points to unknown language '" + to + "'");
            }
            List<String> path = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            String cur = from;
            while (true) {
                if (!seen.add(cur)) {
                    throw new IllegalArgumentException("fallback cycle detected: " + path);
                }
                path.add(cur);
                if (cur.equals(base)) break;
                String next = fallback.get(cur);
                if (next == null) {
                    throw new IllegalArgumentException(
                            "fallback chain from '" + from + "' never reaches base '" + base + "' (" + path + ")");
                }
                cur = next;
            }
        }
        // Languages other than base that declare no fallback must implicitly reach base directly;
        // require explicit reachability, i.e. every language must eventually have a path.
        for (String lang : languages) {
            if (lang.equals(base)) continue;
            if (fallback.containsKey(lang)) continue;
            throw new IllegalArgumentException(
                    "language '" + lang + "' has no fallback chain to base '" + base + "'");
        }
    }

    /**
     * Resolves each key for one language by walking its fallback chain until a language carrying
     * the key is found (always ending at base, which covers every key).
     */
    public static LinkedHashMap<String, String> resolveSources(
            String lang, String base, Map<String, String> fallback,
            Map<String, LinkedHashMap<String, String>> messages,
            List<String> orderedKeys) {
        LinkedHashMap<String, String> sources = new LinkedHashMap<>();
        for (String key : orderedKeys) {
            String cur = lang;
            String source = null;
            Set<String> seen = new HashSet<>();
            while (true) {
                if (!seen.add(cur)) {
                    throw new IllegalArgumentException("fallback cycle at " + seen);
                }
                LinkedHashMap<String, String> map = messages.get(cur);
                if (map != null && map.containsKey(key)) {
                    source = cur;
                    break;
                }
                if (cur.equals(base)) break;
                cur = fallback.get(cur);
                if (cur == null) break;
            }
            if (source == null) source = base;
            sources.put(key, source);
        }
        return sources;
    }
}
