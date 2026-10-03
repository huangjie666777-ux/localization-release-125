package dev.localization;

import java.util.List;
import java.util.Map;

/** Fully validated submit content. */
public record ValidatedBatch(String baseLanguage,
                             Map<String, String> fallbacks,
                             Map<String, Map<String, String>> messages,
                             Map<String, List<String>> chains) {
}
