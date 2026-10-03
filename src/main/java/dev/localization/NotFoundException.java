package dev.localization;

/** Unknown version/language/key lookup failure. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
