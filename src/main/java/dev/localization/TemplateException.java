package dev.localization;

/** Thrown when an ICU template fails analysis or rendering parameter checks. */
public class TemplateException extends RuntimeException {
    public TemplateException(String message) {
        super(message);
    }

    public TemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
