package dev.localization;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Result of analysing an ICU MessageFormat template. */
public final class TemplateInfo {
    private final String template;
    private final Map<String, ParamKind> params;

    public TemplateInfo(String template, Map<String, ParamKind> params) {
        this.template = template;
        this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    public String template() {
        return template;
    }

    /** Parameter name -> structural role, in first-appearance order. */
    public Map<String, ParamKind> params() {
        return params;
    }
}
