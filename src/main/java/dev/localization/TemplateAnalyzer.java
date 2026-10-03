package dev.localization;

import com.ibm.icu.text.MessagePattern;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses ICU MessageFormat templates and extracts their named parameters.
 * Only plain substitutions, {@code plural} and {@code select} (including nested
 * combinations) are allowed; number/date/time/choice/selectordinal and other
 * format types are rejected. Positional arguments ({@code {0}}) are rejected.
 */
public final class TemplateAnalyzer {

    private final int maxDepth;

    public TemplateAnalyzer(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * Analyse a template.
     *
     * @throws TemplateException when the template is invalid ICU syntax, uses a
     *                          forbidden format type, exceeds nesting depth or
     *                          mixes plural/select roles for one parameter name
     */
    public TemplateInfo analyze(String template) {
        if (template == null) {
            throw new TemplateException("template is null");
        }
        MessagePattern pattern;
        try {
            pattern = new MessagePattern().parse(template);
        } catch (RuntimeException e) {
            throw new TemplateException("invalid ICU MessageFormat: " + e.getMessage());
        }
        Map<String, ParamKind> params = new LinkedHashMap<>();
        int depth = 0;
        int maxSeen = 0;
        try {
            for (int i = 0; i < pattern.countParts(); i++) {
                MessagePattern.Part part = pattern.getPart(i);
                switch (part.getType()) {
                    case ARG_START -> {
                        depth++;
                        maxSeen = Math.max(maxSeen, depth);
                        MessagePattern.ArgType argType = part.getArgType();
                        String name = readArgName(pattern, i);
                        if (argType == MessagePattern.ArgType.NONE) {
                            merge(params, name, ParamKind.SIMPLE);
                        } else if (argType == MessagePattern.ArgType.SELECT) {
                            merge(params, name, ParamKind.SELECT);
                        } else if (argType == MessagePattern.ArgType.PLURAL) {
                            merge(params, name, ParamKind.PLURAL);
                        } else if (argType == MessagePattern.ArgType.SELECTORDINAL) {
                            throw new TemplateException(
                                    "selectordinal is not allowed (parameter '" + name + "')");
                        } else {
                            throw new TemplateException(
                                    "format type other than plain/plural/select is not allowed "
                                    + "(parameter '" + name + "', type " + argType + ")");
                        }
                    }
                    case ARG_LIMIT -> depth--;
                    default -> {
                    }
                }
            }
        } finally {
            // MessagePattern holds lightweight parser state; nothing to close.
        }
        if (maxSeen > maxDepth) {
            throw new TemplateException(
                    "template nesting depth " + maxSeen + " exceeds limit " + maxDepth);
        }
        return new TemplateInfo(template, params);
    }

    private String readArgName(MessagePattern pattern, int argStartIndex) {
        // The part immediately following ARG_START is ARG_NAME or ARG_NUMBER.
        if (argStartIndex + 1 >= pattern.countParts()) {
            throw new TemplateException("malformed argument");
        }
        MessagePattern.Part next = pattern.getPart(argStartIndex + 1);
        if (next.getType() != MessagePattern.Part.Type.ARG_NAME) {
            throw new TemplateException(
                    "positional arguments are not allowed; named arguments required");
        }
        return pattern.getSubstring(next);
    }

    private void merge(Map<String, ParamKind> params, String name, ParamKind kind) {
        if (name.isEmpty()) {
            throw new TemplateException("empty parameter name");
        }
        ParamKind existing = params.get(name);
        if (existing == null) {
            params.put(name, kind);
        } else if (existing != kind) {
            throw new TemplateException("parameter '" + name + "' is used both as "
                    + existing + " and as " + kind);
        }
    }
}
