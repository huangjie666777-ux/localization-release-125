package dev.localization.release;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublisherRendererTest {

    @TempDir
    Path dir;
    private VersionStore store;
    private Publisher publisher;

    private Publisher.Batch batch(String base,
                                 Map<String, Map<String, String>> msgs,
                                 Map<String, String> fb) {
        Publisher.Batch b = new Publisher.Batch();
        b.base = base;
        b.messages = new LinkedHashMap<>();
        msgs.forEach((lang, table) -> b.messages.put(lang, new LinkedHashMap<>(table)));
        b.fallback = fb == null ? new LinkedHashMap<>() : new LinkedHashMap<>(fb);
        return b;
    }

    @BeforeEach
    void setup() {
        store = new VersionStore(dir);
        publisher = new Publisher(store);
    }

    @Test
    void publishesAndRendersWithFallback() {
        Map<String, Map<String, String>> msgs = Map.of(
                "en", Map.of("photos", "{count, plural, =1 {one photo} other {# photos}}",
                        "hello", "Hello {name}!"),
                "zh", Map.of("photos", "{count, plural, other {# 张照片}}"),
                "zh-Hant", Map.of());
        Snapshot v1 = publisher.publish(0,
                batch("en", msgs, Map.of("zh", "en", "zh-Hant", "zh")));
        assertEquals(1, v1.version);

        // zh-Hant has no keys: photos falls back to zh, hello all the way to en.
        assertEquals("zh", v1.sources.get("zh-Hant").get("photos"));
        assertEquals("en", v1.sources.get("zh-Hant").get("hello"));

        assertEquals("3 张照片",
                Renderer.render(v1, "zh-Hant", "photos", Map.of("count", 3)));
        assertEquals("Hello Ada!",
                Renderer.render(v1, "zh", "hello", Map.of("name", "Ada")));
    }

    @Test
    void differentPluralBranchesAllowedSameArgs() {
        Map<String, Map<String, String>> msgs = Map.of(
                "en", Map.of("p", "{n, plural, one {# thing} other {# things}}"),
                "ru", Map.of("p", "{n, plural, one {# штука} few {# штуки} many {# штук} other {# штука}}"));
        assertDoesNotThrow(() -> publisher.publish(0, batch("en", msgs, Map.of("ru", "en"))));
    }

    @Test
    void rejectsSchemaMismatch() {
        Map<String, Map<String, String>> msgs = Map.of(
                "en", Map.of("k", "{a} {b}"),
                "fr", Map.of("k", "{a}"));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", msgs, Map.of("fr", "en"))));
    }

    @Test
    void rejectsExtraKeyUnknownTargetAndCycle() {
        Map<String, Map<String, String>> msgs = Map.of(
                "en", Map.of("k", "x"),
                "de", Map.of("extra", "y"));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", msgs, Map.of("de", "en"))));

        Map<String, Map<String, String>> ok = Map.of("en", Map.of("k", "x"),
                "de", Map.of(), "fr", Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", ok, Map.of("de", "fr", "fr", "de"))));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", ok, Map.of("de", "it"))));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", ok, Map.of())));
    }

    @Test
    void optimisticConflictDoesNotConsumeVersion() {
        Map<String, Map<String, String>> msgs = Map.of("en", Map.of("k", "v1"));
        publisher.publish(0, batch("en", msgs, Map.of()));
        assertThrows(Publisher.VersionConflictException.class,
                () -> publisher.publish(0, batch("en", msgs, Map.of())));
        Snapshot v2 = publisher.publish(1, batch("en", Map.of("en", Map.of("k", "v2")), Map.of()));
        assertEquals(2, v2.version);
    }

    @Test
    void failedBatchLeavesNoTrace() {
        Map<String, Map<String, String>> bad = Map.of("en", Map.of("k", "{x, date}"));
        assertThrows(IllegalArgumentException.class,
                () -> publisher.publish(0, batch("en", bad, Map.of())));
        assertEquals(0, store.currentVersion());
        assertTrue(store.current().isEmpty());
    }

    @Test
    void survivesRestartAndOldVersionsImmutable() {
        publisher.publish(0, batch("en", Map.of("en", Map.of("k", "first")), Map.of()));
        publisher.publish(1, batch("en", Map.of("en", Map.of("k", "second")), Map.of()));
        VersionStore reopened = new VersionStore(dir);
        assertEquals(2, reopened.currentVersion());
        assertEquals("first", reopened.version(1).orElseThrow().messages.get("en").get("k"));
        assertEquals("second", reopened.version(2).orElseThrow().messages.get("en").get("k"));
    }

    @Test
    void renderEnforcesParamTypesAndPresence() {
        Snapshot v1 = publisher.publish(0,
                batch("en", Map.of("en", Map.of(
                                "g", "{who, select, m {he} other {they}} {n, plural, other {#}}")),
                        Map.of()));
        assertEquals("he 5", Renderer.render(v1, "en", "g",
                Map.of("who", "m", "n", 5)));
        assertThrows(IllegalArgumentException.class,
                () -> Renderer.render(v1, "en", "g", Map.of("who", "m", "n", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> Renderer.render(v1, "en", "g", Map.of("who", 1, "n", 5)));
        assertThrows(IllegalArgumentException.class,
                () -> Renderer.render(v1, "en", "g", Map.of("who", "m")));
        assertThrows(IllegalArgumentException.class,
                () -> Renderer.render(v1, "en", "g", Map.of("who", "m", "n", 5, "z", 1)));
    }

    @Test
    void emptyStringIsValidTranslation() {
        Snapshot v1 = publisher.publish(0, batch("en",
                Map.of("en", Map.of("k", "x"), "de", Map.of("k", "")),
                Map.of("de", "en")));
        assertEquals("de", v1.sources.get("de").get("k"));
        assertEquals("", Renderer.render(v1, "de", "k", Map.of()));
    }
}
