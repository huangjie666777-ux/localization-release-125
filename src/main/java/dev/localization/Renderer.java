package dev.localization;

import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.util.ULocale;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Validates render arguments against the analysed template and formats it. */
public final class Renderer {

    private final TemplateAnalyzer analyzer;

    public Renderer(int maxTemplateDepth) {
        this.analyzer = new TemplateAnalyzer(maxTemplateDepth);
    }

    /**
     * @param template      resolved (fallback-applied) template
     * @param sourceLanguage language actually supplying the template;
     *                       its ICU plural rules drive plural/selectordinal choice
     */
    public String render(String template, String sourceLanguage, Map<String, Object> args) {
        TemplateInfo info = analyzer.analyze(template);
        validateArgs(info, args);
        ULocale locale = ULocale.forLanguageTag(sourceLanguage);
        try {
            return new MessageFormat(template, locale).format(args == null ? Map.of() : args);
        } catch (IllegalArgumentException e) {
            throw new TemplateException("ICU rendering failed: " + e.getMessage(), e);
        }
    }

    /** Validate rendered arguments (base parameter set is guaranteed by release checks). */
    private void validateArgs(TemplateInfo info, Map<String, Object> args) {
        Map<String, ParamKind> params = info.params();
        Set<String> provided = args == null ? Set.of() : new LinkedHashSet<>(args.keySet());
        for (Map.Entry<String, ParamKind> e : params.entrySet()) {
            String name = e.getKey();
            if (!provided.contains(name)) {
                throw new TemplateException("missing parameter: '" + name + "'");
            }
            Object value = args.get(name);
            if (e.getValue() == ParamKind.PLURAL) {
                if (!(value instanceof Number n)) {
                    throw new TemplateException("plural parameter '" + name + "' requires a number");
                }
                double d = n.doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) {
                    throw new TemplateException("plural parameter '" + name + "' must be a finite number");
                }
            } else if (e.getValue() == ParamKind.SELECT) {
                if (!(value instanceof String)) {
                    throw new TemplateException("select parameter '" + name + "' requires a string");
                }
            } else if (value != null && !(value instanceof String || value instanceof Number
                    || value instanceof Boolean)) {
                throw new TemplateException("parameter '" + name
                        + "' must be a string, number or boolean");
            }
        }
        for (String extra : provided) {
            if (!params.containsKey(extra)) {
                throw new TemplateException("unexpected parameter: '" + extra + "'");
            }
        }
    }
}
