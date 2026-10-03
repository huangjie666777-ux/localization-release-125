package dev.localization.release;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Append-only immutable versions on disk under {@code dataDir}. Each version is a JSON file;
 * {@code current} points at the latest. Publication is atomic: either a complete new version +
 * pointer exist, or nothing changed, so failed batches never consume a version number.
 */
public final class VersionStore implements AutoCloseable {

    public static final class StoredBatch {
        public long version;
        public String base;
        public LinkedHashMap<String, String> fallback;
        public LinkedHashMap<String, LinkedHashMap<String, String>> messages;
    }

    private final Path dataDir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile long latestVersion = 0;
    private volatile Snapshot latest;
    private final ConcurrentHashMap<Long, Snapshot> history = new ConcurrentHashMap<>();

    public VersionStore(Path dataDir) {
        this.dataDir = dataDir;
        this.mapper = new ObjectMapper();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        recover();
    }

    private void recover() {
        lock.writeLock().lock();
        try {
            Files.createDirectories(dataDir);
            long pointer = readPointer();
            long highest = 0;
            try (Stream<Path> files = Files.list(dataDir)) {
                List<Path> json = files
                        .filter(f -> f.getFileName().toString().matches("v\\d+\\.json"))
                        .toList();
                for (Path f : json) {
                    String n = f.getFileName().toString();
                    long v = Long.parseLong(n.substring(1, n.indexOf('.')));
                    highest = Math.max(highest, v);
                }
            }
            // Reconstruct from the highest well-formed version. Orphaned files from a crashed
            // write are ignored (they never had a JSON flushed/fsync'd successfully).
            long target = pointer > 0 ? pointer : highest;
            if (target > 0) {
                StoredBatch batch = readFile(target);
                Snapshot snap = toSnapshot(batch);
                this.latestVersion = target;
                this.latest = snap;
                this.history.put(target, snap);
                if (pointer != target) {
                    writePointer(target);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot recover version store", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public long currentVersion() {
        lock.readLock().lock();
        try {
            return latestVersion;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Snapshot> current() {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(latest);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Loads any published version; old versions are immutable and reconstructed from disk. */
    public Optional<Snapshot> version(long v) {
        Snapshot cached = history.get(v);
        if (cached != null) return Optional.of(cached);
        lock.readLock().lock();
        try {
            Path file = dataDir.resolve("v" + v + ".json");
            if (!Files.exists(file)) return Optional.empty();
            StoredBatch batch = readFile(v);
            if (batch.version != v) return Optional.empty();
            Snapshot snap = toSnapshot(batch);
            history.put(v, snap);
            return Optional.of(snap);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read version " + v, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Conditional publication. {@code expectedVersion} must equal the current version.
     * @return the new snapshot; empty if optimistic-lock conflict
     */
    public Optional<Snapshot> publish(long expectedVersion, String base,
                                      LinkedHashMap<String, String> fallback,
                                      LinkedHashMap<String, LinkedHashMap<String, String>> messages) {
        lock.writeLock().lock();
        try {
            if (expectedVersion != latestVersion) {
                return Optional.empty();
            }
            long next = latestVersion + 1;
            StoredBatch batch = new StoredBatch();
            batch.version = next;
            batch.base = base;
            batch.fallback = fallback;
            batch.messages = messages;

            Path versionFile = dataDir.resolve("v" + next + ".json");
            Path tmp = dataDir.resolve("v" + next + ".json.tmp");
            try {
                byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(batch);
                Files.write(tmp, bytes, StandardOpenOptions());
                atomicMove(tmp, versionFile);
                Snapshot snap = toSnapshot(batch);
                writePointer(next);
                this.latestVersion = next;
                this.latest = snap;
                this.history.put(next, snap);
                return Optional.of(snap);
            } catch (IOException e) {
                deleteQuietly(tmp);
                deleteQuietly(versionFile);
                throw new UncheckedIOException("failed to persist version " + next, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static java.nio.file.OpenOption[] StandardOpenOptions() {
        return new java.nio.file.OpenOption[]{
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.SYNC};
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private long readPointer() throws IOException {
        Path pointer = dataDir.resolve("current");
        if (!Files.exists(pointer)) return 0;
        String s = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return 0;
        return Long.parseLong(s);
    }

    private void writePointer(long v) throws IOException {
        Path pointer = dataDir.resolve("current");
        Path tmp = dataDir.resolve("current.tmp");
        Files.write(tmp, String.valueOf(v).getBytes(StandardCharsets.UTF_8), StandardOpenOptions());
        atomicMove(tmp, pointer);
    }

    private StoredBatch readFile(long v) throws IOException {
        Path file = dataDir.resolve("v" + v + ".json");
        return mapper.readValue(Files.readAllBytes(file), new TypeReference<>() {});
    }

    private static Snapshot toSnapshot(StoredBatch b) {
        List<String> keys = new java.util.ArrayList<>(b.messages.get(b.base).keySet());
        return Snapshot.build(b.version, b.base, keys, b.messages, b.fallback);
    }

    @Override
    public void close() {
        // nothing to close; directory is durable by construction
    }
}
