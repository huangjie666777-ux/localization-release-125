package dev.localization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-batch validation: structure, language tags, key coverage, ICU template
 * parameter parity and fallback graph. Nothing is published until every check
 * passes, so a failure leaves no partial state and consumes no version number.
 */
public final class ReleaseValidator {

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final TemplateAnalyzer analyzer;

    public ReleaseValidator(Limits limits) {
        this.analyzer = new TemplateAnalyzer(limits.maxTemplateDepth());
    }

    /** Parse + validate a submit body into immutable maps ready for publishing. */
    public ValidatedBatch validate(String json, Limits limits) {
        JsonNode root = parse(json);
        List<String> errors = new ArrayList<>();

        String base = textField(root, "baseLanguage", errors);
        if (base != null && !LangTags.isValid(base)) {
            errors.add("illegal BCP 47 base language tag: '" + base + "'");
            base = null;
        }

        Map<String, String> fallbacks = readFallbacks(root, errors);
        Map<String, Map<String, String>> languages = readLanguages(root, errors);

        if (languages.size() > limits.maxLanguageEntries()) {
            errors.add("language count " + languages.size() + " exceeds limit " + limits.maxLanguageEntries());
        }

        if (base != null && !languages.containsKey(base)) {
            errors.add("base language '" + base + "' must be present in languages");
        }

        // Every language mentioned by fallbacks must be a declared target.
        if (base != null) {
            for (String referenced : new java.util.ArrayList<>(fallbacks.keySet())) {
                if (!languages.containsKey(referenced)) {
                    errors.add("language '" + referenced + "' appears in fallbacks but not in languages");
                }
            }
        }

        // Fallback graph validation.
        Map<String, List<String>> chains = base == null
                ? Map.of()
                : FallbackResolver.chains(base, fallbacks, errors);

        // Key coverage: base defines the complete key set.
        Map<String, String> baseMessages = base == null ? Map.of() : languages.getOrDefault(base, Map.of());
        if (baseMessages.size() > limits.maxKeys()) {
            errors.add("message key count " + baseMessages.size() + " exceeds limit " + limits.maxKeys());
        }
        for (Map.Entry<String, Map<String, String>> e : languages.entrySet()) {
            String lang = e.getKey();
            if (lang.equals(base)) {
                continue;
            }
            for (String key : e.getValue().keySet()) {
                if (!baseMessages.containsKey(key)) {
                    errors.add("language '" + lang + "' adds key '" + key + "' not present in base");
                }
            }
        }

        // Template analysis + parameter parity against base.
        Map<String, TemplateInfo> baseTemplates = analyzeSet(base, baseMessages, errors);
        for (Map.Entry<String, Map<String, String>> e : languages.entrySet()) {
            String lang = e.getKey();
            if (lang.equals(base)) {
                continue;
            }
            Map<String, TemplateInfo> translated = analyzeSet(lang, e.getValue(), errors);
            for (Map.Entry<String, TemplateInfo> te : translated.entrySet()) {
                String key = te.getKey();
                TemplateInfo baseInfo = baseTemplates.get(key);
                if (baseInfo == null) {
                    continue; // already reported as extra key
                }
                compareParams(lang, key, baseInfo, te.getValue(), errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        return new ValidatedBatch(base, fallbacks, languages, chains);
    }

    private void compareParams(String lang, String key, TemplateInfo baseInfo, TemplateInfo info,
                               List<String> errors) {
        Map<String, ParamKind> bp = baseInfo.params();
        Map<String, ParamKind> tp = info.params();
        for (Map.Entry<String, ParamKind> e : bp.entrySet()) {
            ParamKind t = tp.get(e.getKey());
            if (t == null) {
                errors.add("['" + lang + "' / '" + key + "'] missing parameter '" + e.getKey() + "'");
            } else if (t != e.getValue()) {
                errors.add("['" + lang + "' / '" + key + "'] parameter '" + e.getKey()
                        + "' is " + t + " but " + e.getValue() + " in base");
            }
        }
        for (String extra : tp.keySet()) {
            if (!bp.containsKey(extra)) {
                errors.add("['" + lang + "' / '" + key + "'] unexpected parameter '" + extra + "'");
            }
        }
    }

    private Map<String, TemplateInfo> analyzeSet(String lang, Map<String, String> messages,
                                                List<String> errors) {
        Map<String, TemplateInfo> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : messages.entrySet()) {
            try {
                result.put(e.getKey(), analyzer.analyze(e.getValue()));
            } catch (TemplateException ex) {
                errors.add("['" + lang + "' / '" + e.getKey() + "'] " + ex.getMessage());
            }
        }
        return result;
    }

    private JsonNode parse(String json) {
        try {
            JsonNode node = STRICT.readTree(json);
            if (node == null || !node.isObject()) {
                throw new ValidationException("request body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new ValidationException("invalid JSON: " + cleanJacksonMessage(e.getMessage()));
        }
    }

    private String cleanJacksonMessage(String msg) {
        if (msg == null) {
            return "parse error";
        }
        int idx = msg.indexOf('\n');
        return idx > 0 ? msg.substring(0, idx) : msg;
    }

    private String textField(JsonNode root, String name, List<String> errors) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            errors.add("missing required field '" + name + "'");
            return null;
        }
        if (!node.isTextual()) {
            errors.add("field '" + name + "' must be a string");
            return null;
        }
        String value = node.asText();
        if (value.isEmpty()) {
            errors.add("field '" + name + "' must not be empty");
        }
        return value;
    }

    private Map<String, String> readFallbacks(JsonNode root, List<String> errors) {
        Map<String, String> map = new LinkedHashMap<>();
        JsonNode node = root.get("fallbacks");
        if (node == null || node.isNull()) {
            return map;
        }
        if (!node.isObject()) {
            errors.add("field 'fallbacks' must be an object");
            return map;
        }
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!LangTags.isValid(e.getKey())) {
                errors.add("illegal BCP 47 language tag in fallbacks: '" + e.getKey() + "'");
            }
            if (!e.getValue().isTextual()) {
                errors.add("fallback value for '" + e.getKey() + "' must be a string");
            } else if (!LangTags.isValid(e.getValue().asText())) {
                errors.add("illegal BCP 47 fallback target tag: '" + e.getValue().asText() + "'");
            } else {
                map.put(e.getKey(), e.getValue().asText());
            }
        }
        return map;
    }

    private Map<String, Map<String, String>> readLanguages(JsonNode root, List<String> errors) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        JsonNode node = root.get("languages");
        if (node == null || node.isNull()) {
            errors.add("missing required field 'languages'");
            return result;
        }
        if (!node.isObject()) {
            errors.add("field 'languages' must be an object");
            return result;
        }
        Iterator<Map.Entry<String, JsonNode>> langs = node.fields();
        while (langs.hasNext()) {
            Map.Entry<String, JsonNode> le = langs.next();
            String lang = le.getKey();
            if (!LangTags.isValid(lang)) {
                errors.add("illegal BCP 47 language tag: '" + lang + "'");
            }
            JsonNode messages = le.getValue();
            if (!messages.isObject()) {
                errors.add("messages for '" + lang + "' must be an object");
                continue;
            }
            Map<String, String> kv = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> msgs = messages.fields();
            while (msgs.hasNext()) {
                Map.Entry<String, JsonNode> me = msgs.next();
                if (me.getKey().isEmpty()) {
                    errors.add("['" + lang + "'] empty message key");
                }
                if (!me.getValue().isTextual()) {
                    errors.add("['" + lang + "' / '" + me.getKey() + "'] message must be a string");
                } else {
                    kv.put(me.getKey(), me.getValue().asText());
                }
            }
            result.put(lang, kv);
        }
        return result;
    }
}
