package dev.localization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Append-only, immutable version storage.
 *
 * Each version is a single JSON file written to a temp name and atomically
 * renamed, so a crash never leaves a half batch and never consumes a version
 * number. On startup the directory is recovered and {@code .tmp} leftovers are
 * removed. Published snapshots are referenced atomically, so concurrent reads
 * always observe one complete version, never a mix of two.
 */
public final class VersionStore implements AutoCloseable {

    private static final String PREFIX = "v";
    private static final String SUFFIX = ".json";
    private static final String TMP_SUFFIX = ".json.tmp";

    private final Path dir;
    private final ObjectMapper mapper;
    private final AtomicReference<Map<Integer, ReleaseBundle>> versions = new AtomicReference<>(Map.of());
    private final Object publishLock = new Object();

    public VersionStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.ALWAYS);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data directory " + dir, e);
        }
        recover();
    }

    private void recover() {
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            Map<Integer, ReleaseBundle> loaded = new LinkedHashMap<>();
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(TMP_SUFFIX)) {
                    Files.deleteIfExists(file);
                    continue;
                }
                if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX)) {
                    continue;
                }
                Stored stored = mapper.readValue(Files.readAllBytes(file), Stored.class);
                if (stored.version != parseIntName(name)) {
                    throw new IllegalStateException("version file/name mismatch: " + name);
                }
                loaded.put(stored.version, stored.toBundle());
            }
            versions.set(Map.copyOf(loaded));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to recover versions from " + dir, e);
        }
    }

    private int parseIntName(String name) {
        return Integer.parseInt(name.substring(PREFIX.length(), name.length() - SUFFIX.length()));
    }

    public ReleaseBundle latest() {
        Map<Integer, ReleaseBundle> snapshot = versions.get();
        if (snapshot.isEmpty()) {
            return null;
        }
        return snapshot.get(snapshot.keySet().stream().max(Comparator.naturalOrder()).orElseThrow());
    }

    public ReleaseBundle get(int version) {
        return versions.get().get(version);
    }

    public List<Integer> versions() {
        List<Integer> all = new ArrayList<>(versions.get().keySet());
        all.sort(Comparator.naturalOrder());
        return List.copyOf(all);
    }

    /**
     * Validate-then-publish under one lock. The supplier performs validation
     * while holding the lock (so expectedVersion cannot become stale), and
     * failure throws without writing a file.
     */
    public <T> T publish(Integer expectedVersion,
                         java.util.function.BiFunction<Integer, String, ValidatedBatch> prepare,
                         java.util.function.Function<ReleaseBundle, T> done) {
        synchronized (publishLock) {
            Map<Integer, ReleaseBundle> snapshot = versions.get();
            int currentMax = snapshot.isEmpty() ? 0 : snapshot.keySet().stream()
                    .max(Comparator.naturalOrder()).orElseThrow();
            int expected = expectedVersion == null ? currentMax : expectedVersion;
            if (expected != currentMax) {
                throw new ConflictException(currentMax, expected);
            }
            String base = snapshot.isEmpty() ? null
                    : snapshot.get(currentMax).baseLanguage();
            ValidatedBatch batch = prepare.apply(currentMax, base);
            int nextVersion = currentMax + 1;
            ReleaseBundle bundle = new ReleaseBundle(nextVersion, batch.baseLanguage(),
                    batch.fallbacks(), batch.messages(), System.currentTimeMillis());
            persist(bundle);
            Map<Integer, ReleaseBundle> updated = new LinkedHashMap<>(snapshot);
            updated.put(nextVersion, bundle);
            versions.set(Map.copyOf(updated));
            return done.apply(bundle);
        }
    }

    private void persist(ReleaseBundle bundle) {
        Path target = dir.resolve(PREFIX + bundle.version() + SUFFIX);
        Path tmp = dir.resolve(PREFIX + bundle.version() + TMP_SUFFIX);
        try {
            byte[] bytes = mapper.writer(new DefaultPrettyPrinter())
                    .writeValueAsBytes(Stored.from(bundle));
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort; recovery also removes .tmp files
            }
            throw new UncheckedIOException("failed to persist version " + bundle.version(), e);
        }
    }

    @Override
    public void close() {
        // nothing external held; API kept for symmetry / future use
    }

    /** On-disk JSON shape. */
    public static final class Stored {
        public int version;
        public String baseLanguage;
        public long publishedAt;
        public Map<String, String> fallbacks = new LinkedHashMap<>();
        public Map<String, Map<String, String>> languages = new LinkedHashMap<>();

        public static Stored from(ReleaseBundle b) {
            Stored s = new Stored();
            s.version = b.version();
            s.baseLanguage = b.baseLanguage();
            s.publishedAt = b.publishedAt();
            s.fallbacks = new LinkedHashMap<>(b.fallbacks());
            s.languages = new LinkedHashMap<>();
            b.messages().forEach((lang, map) -> s.languages.put(lang, new LinkedHashMap<>(map)));
            return s;
        }

        ReleaseBundle toBundle() {
            return new ReleaseBundle(version, baseLanguage, fallbacks, languages, publishedAt);
        }
    }
}
