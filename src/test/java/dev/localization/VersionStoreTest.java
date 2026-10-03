package dev.localization;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VersionStoreTest {

    private final Limits limits = new Limits(64 * 1024, 100, 20, 10);

    private ValidatedBatch batch(String base) {
        return new ValidatedBatch(base, Map.of(), Map.of(base, Map.of("k", "{n,plural,other{#}}")),
                Map.of(base, List.of()));
    }

    @Test
    void versionsAreImmutableAndIncrementOptimistically(@TempDir Path dir) throws Exception {
        VersionStore store = new VersionStore(dir);
        ReleaseBundle v1 = store.publish(0, (v, b) -> batch("en"), b -> b);
        assertEquals(1, v1.version());
        ReleaseBundle v2 = store.publish(1, (v, b) -> batch("fr"), b -> b);
        assertEquals(2, v2.version());
        assertEquals("en", store.get(1).baseLanguage());
        assertEquals("fr", store.get(2).baseLanguage());

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> store.publish(1, (v, b) -> batch("de"), b -> b));
        assertEquals(2, conflict.currentVersion());

        // A failing publish consumes no version and writes no file.
        int filesBefore = files(dir);
        assertThrows(ValidationException.class, () -> store.publish(2, (v, b) -> {
            throw new ValidationException("boom");
        }, b -> b));
        assertEquals(filesBefore, files(dir));
        assertEquals(2, store.latest().version());

        store.close();
        // Restart recovery.
        VersionStore restored = new VersionStore(dir);
        assertEquals(List.of(1, 2), restored.versions());
        assertEquals("en", restored.get(1).baseLanguage());
    }

    @Test
    void removesTempFilesOnRecovery(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("v1.json"), "{}");
        // Empty/garbage temp file must be cleaned without breaking startup.
        Files.writeString(dir.resolve("v2.json.tmp"), "partial");
        Files.deleteIfExists(dir.resolve("v1.json"));
        VersionStore store = new VersionStore(dir);
        assertNull(store.latest());
        assertFalse(Files.exists(dir.resolve("v2.json.tmp")));
    }

    private int files(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return (int) s.filter(Files::isRegularFile).count();
        }
    }
}
