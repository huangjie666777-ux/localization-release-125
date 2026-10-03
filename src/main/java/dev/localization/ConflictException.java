package dev.localization;

/** Optimistic-concurrency mismatch on {@code expectedVersion}. */
public class ConflictException extends RuntimeException {
    private final int currentVersion;
    private final int expectedVersion;

    public ConflictException(int currentVersion, int expectedVersion) {
        super("expected latest version " + expectedVersion + " but server has " + currentVersion);
        this.currentVersion = currentVersion;
        this.expectedVersion = expectedVersion;
    }

    public int currentVersion() {
        return currentVersion;
    }

    public int expectedVersion() {
        return expectedVersion;
    }
}
