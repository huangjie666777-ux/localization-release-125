package dev.localization;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FallbackResolverTest {
    @Test
    void resolvesChainsAndRejectsCyclesAndBrokenChains() {
        List<String> errors = new ArrayList<>();
        Map<String, List<String>> chains = FallbackResolver.chains("en",
                Map.of("de", "en", "de-AT", "de", "fr", "es", "es", "it", "it", "fr"), errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("cycle")));
        assertEquals(List.of("de", "en"), chains.get("de-AT"));
    }

    @Test
    void rejectsUnknownTargetAndBaseFallback() {
        List<String> errors = new ArrayList<>();
        FallbackResolver.chains("en", Map.of("en", "fr", "de", "xx"), errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("base language")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown language")));
    }
}
