package dev.localization;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates explicit fallback edges and resolves, for every known language,
 * the ordered ancestor chain (excluding the language itself, ending at base).
 */
public final class FallbackResolver {

    private FallbackResolver() {
    }

    /** language -> ordered ancestor chain (excluding self; base maps to empty). */
    public static Map<String, List<String>> chains(String base, Map<String, String> fallbacks,
                                                   List<String> errors) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        Set<String> known = new HashSet<>(fallbacks.keySet());
        known.add(base);
        for (Map.Entry<String, String> e : fallbacks.entrySet()) {
            String lang = e.getKey();
            String target = e.getValue();
            if (lang.equals(base)) {
                errors.add("base language '" + base + "' must not declare a fallback");
            } else if (target == null || target.isEmpty()) {
                errors.add("fallback target for '" + lang + "' is empty");
            } else if (!known.contains(target)) {
                errors.add("fallback for '" + lang + "' points to unknown language '" + target + "'");
            }
        }
        result.put(base, List.of());
        for (String lang : known) {
            if (lang.equals(base)) {
                continue;
            }
            List<String> chain = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            seen.add(lang);
            String cur = lang;
            boolean valid = true;
            while (true) {
                String next = fallbacks.get(cur);
                if (next == null) {
                    errors.add("fallback chain for '" + lang + "' cannot reach base '" + base + "'");
                    valid = false;
                    break;
                }
                if (!seen.add(next)) {
                    errors.add("fallback cycle detected for '" + lang + "' (loop at '" + next + "')");
                    valid = false;
                    break;
                }
                chain.add(next);
                if (next.equals(base)) {
                    break;
                }
                cur = next;
            }
            if (valid) {
                result.put(lang, List.copyOf(chain));
            }
        }
        return result;
    }
}
