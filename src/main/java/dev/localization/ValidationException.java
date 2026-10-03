package dev.localization;

import java.util.Collections;
import java.util.List;

/** Collects everything wrong with one submit so callers see all problems. */
public class ValidationException extends RuntimeException {
    private final List<String> errors;

    public ValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public ValidationException(String single) {
        this(Collections.singletonList(single));
    }

    public List<String> errors() {
        return errors;
    }
}
