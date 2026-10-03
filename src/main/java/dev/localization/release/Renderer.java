package dev.localization.release;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;

import java.util.LinkedHashMap;
import java.util.Map;

/** Renders a published key with strictly-typed parameters using the source language plural rules. */
public final class Renderer {

    private Renderer() {}

    public static String render(Snapshot snapshot, String lang, String key,
                                Map<String, Object> params) {
        if (!snapshot.messages.containsKey(lang)) {
            throw new IllegalArgumentException("unknown language in this version: " + lang);
        }
        if (!snapshot.messages.get(snapshot.baseTag).containsKey(key)) {
            throw new IllegalArgumentException("unknown key: " + key);
        }
        String sourceLang = snapshot.sources.get(lang).get(key);
        String template = snapshot.messages.get(sourceLang).get(key);
        TemplateAnalyzer.Analysis schema = snapshot.analyses.get(lang).get(key);

        Map<String, Object> supplied = params == null ? Map.of() : params;
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : supplied.entrySet()) {
            if (!schema.args.containsKey(e.getKey())) {
                throw new IllegalArgumentException("unexpected parameter: " + e.getKey());
            }
            TemplateAnalyzer.Kind kind = schema.kindOf(e.getKey());
            Object value = e.getValue();
            if (value == null) {
                throw new IllegalArgumentException("parameter '" + e.getKey() + "' must not be null");
            }
            switch (kind) {
                case SIMPLE -> converted.put(e.getKey(), value);
                case SELECT -> {
                    if (!(value instanceof String)) {
                        throw new IllegalArgumentException(
                                "select parameter '" + e.getKey() + "' must be a string");
                    }
                    converted.put(e.getKey(), value);
                }
                case PLURAL -> {
                    Number number = asFiniteNumber(e.getKey(), value);
                    converted.put(e.getKey(), number);
                }
            }
        }
        for (String required : schema.args.keySet()) {
            if (!converted.containsKey(required)) {
                throw new IllegalArgumentException("missing parameter: " + required);
            }
        }

        ULocale locale = ULocale.forLanguageTag(sourceLang);
        try {
            MessageFormat mf = new MessageFormat(template, locale);
            return mf.format(converted);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("rendering failed: " + ex.getMessage(), ex);
        }
    }

    private static Number asFiniteNumber(String name, Object value) {
        Number n;
        if (value instanceof Number number) {
            n = number;
        } else if (value instanceof String s) {
            try {
                n = Double.valueOf(s);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "plural parameter '" + name + "' must be a finite number");
            }
        } else {
            throw new IllegalArgumentException(
                    "plural parameter '" + name + "' must be a finite number");
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            throw new IllegalArgumentException(
                    "plural parameter '" + name + "' must be finite (got " + n + ")");
        }
        return n;
    }
}
