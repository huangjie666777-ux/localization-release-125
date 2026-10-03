package dev.localization.release;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.MessagePattern.ArgType;
import com.ibm.icu.text.MessagePattern.Part;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses ICU MessageFormat templates and extracts the named-argument schema.
 * Only named arguments are permitted; the only complex types are plural/select (including
 * selectordinal and nested messages). Quoting rules are enforced by ICU itself.
 */
public final class TemplateAnalyzer {

    public enum Kind { SIMPLE, PLURAL, SELECT }

    public static final class Analysis {
        public final LinkedHashMap<String, Kind> args;

        Analysis(LinkedHashMap<String, Kind> args) {
            this.args = args;
        }

        public Set<String> argumentNames() {
            return Collections.unmodifiableSet(args.keySet());
        }

        public Kind kindOf(String name) {
            return args.get(name);
        }

        public boolean sameSchema(Analysis other) {
            return args.equals(other.args);
        }
    }

    private TemplateAnalyzer() {}

    /** Parses, depth-checks and schema-extracts a template. Throws IllegalArgumentException. */
    public static Analysis analyze(String template) {
        if (template == null) {
            throw new IllegalArgumentException("template is null");
        }
        if (template.length() > Limits.MAX_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException(
                    "template too long (" + template.length() + " > " + Limits.MAX_TEMPLATE_LENGTH + ")");
        }
        MessagePattern pattern;
        try {
            pattern = new MessagePattern(template);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid ICU template: " + e.getMessage(), e);
        }
        // Full construction also validates simple-argument format types (number/date/...).
        try {
            new MessageFormat(template, com.ibm.icu.util.ULocale.US);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid ICU template: " + e.getMessage(), e);
        }
        LinkedHashMap<String, Kind> args = new LinkedHashMap<>();
        scan(pattern, 0, pattern.countParts() - 1, 0, args, null);
        return new Analysis(args);
    }

    /**
     * Scans the parts belonging to one message (from an MSG_START/ARG_LIMIT index to its limit).
     * @param depth current nesting depth of complex arguments enclosing this message
     * @param enclosingPlural name of the plural argument owning the innermost branch (for #)
     */
    private static void scan(MessagePattern p, int start, int limit, int depth,
                             LinkedHashMap<String, Kind> args, String enclosingPlural) {
        int i = start;
        while (i <= limit) {
            Part part = p.getPart(i);
            Part.Type type = part.getType();
            switch (type) {
                case ARG_START -> {
                    String name = argumentName(p, i);
                    ArgType argType = part.getArgType();
                    Kind kind = switch (argType) {
                        case NONE -> Kind.SIMPLE;
                        case PLURAL, SELECTORDINAL -> Kind.PLURAL;
                        case SELECT -> Kind.SELECT;
                        default -> throw new IllegalArgumentException(
                                "unsupported format type '" + argType + "' for argument '" + name + "'");
                    };
                    merge(args, name, kind);
                    int argLimit = p.getLimitPartIndex(i);
                    if (argType == ArgType.PLURAL || argType == ArgType.SELECTORDINAL
                            || argType == ArgType.SELECT) {
                        int nextDepth = depth + 1;
                        if (nextDepth > Limits.MAX_NESTING_DEPTH) {
                            throw new IllegalArgumentException(
                                    "template nesting exceeds " + Limits.MAX_NESTING_DEPTH + " levels");
                        }
                        scanBranches(p, i, argLimit, nextDepth, args,
                                kind == Kind.PLURAL ? name : enclosingPlural);
                    } else {
                        scan(p, i + 1, argLimit - 1, depth, args, enclosingPlural);
                    }
                    i = argLimit + 1;
                    continue;
                }
                case REPLACE_NUMBER -> {
                    if (enclosingPlural == null) {
                        throw new IllegalArgumentException("'#' is only allowed inside a plural branch");
                    }
                    merge(args, enclosingPlural, Kind.PLURAL);
                }
                case ARG_NAME, ARG_NUMBER, ARG_TYPE, ARG_STYLE, ARG_SELECTOR, ARG_INT, ARG_DOUBLE,
                        MSG_START, MSG_LIMIT, ARG_LIMIT, SKIP_SYNTAX, INSERT_CHAR -> {
                    // handled elsewhere / literal content
                }
            }
            i++;
        }
    }

    private static void scanBranches(MessagePattern p, int argStart, int argLimit, int depth,
                                     LinkedHashMap<String, Kind> args, String enclosingPlural) {
        // After ARG_START: ARG_NAME then a sequence of ARG_SELECTOR + MSG_START ... MSG_LIMIT.
        int i = argStart + 1;
        if (p.getPart(i).getType() == Part.Type.ARG_NAME) i++;
        while (i < argLimit) {
            Part part = p.getPart(i);
            if (part.getType() != Part.Type.ARG_SELECTOR) {
                i++;
                continue;
            }
            int msgStart = i + 1;
            if (p.getPart(msgStart).getType() != Part.Type.MSG_START) {
                i++;
                continue;
            }
            int msgLimit = p.getLimitPartIndex(msgStart);
            scan(p, msgStart + 1, msgLimit - 1, depth, args, enclosingPlural);
            i = msgLimit + 1;
        }
    }

    private static String argumentName(MessagePattern p, int argStart) {
        Part next = p.getPart(argStart + 1);
        if (next.getType() != Part.Type.ARG_NAME) {
            throw new IllegalArgumentException("numbered/positional arguments are not supported");
        }
        return p.getSubstring(next);
    }

    private static void merge(Map<String, Kind> args, String name, Kind kind) {
        Kind existing = args.get(name);
        if (existing != null && existing != kind) {
            throw new IllegalArgumentException(
                    "argument '" + name + "' is used both as plural and select");
        }
        args.putIfAbsent(name, kind);
    }
}
