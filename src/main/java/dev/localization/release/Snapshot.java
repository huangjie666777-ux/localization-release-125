package dev.localization.release;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An immutable, published version. Old snapshots never change once published. */
public final class Snapshot {
    public final long version;
    public final String baseTag;
    public final List<String> keys;
    public final Map<String, LinkedHashMap<String, String>> messages;
    public final Map<String, String> fallback;
    /** lang -> key -> source language actually used after fallback. */
    public final Map<String, LinkedHashMap<String, String>> sources;
    /** lang -> key -> parsed schema. */
    public final Map<String, Map<String, TemplateAnalyzer.Analysis>> analyses;

    public Snapshot(long version, String baseTag, List<String> keys,
                    Map<String, LinkedHashMap<String, String>> messages,
                    Map<String, String> fallback,
                    Map<String, LinkedHashMap<String, String>> sources,
                    Map<String, Map<String, TemplateAnalyzer.Analysis>> analyses) {
        this.version = version;
        this.baseTag = baseTag;
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.messages = deepImmutable(messages);
        this.fallback = Collections.unmodifiableMap(new LinkedHashMap<>(fallback));
        this.sources = deepImmutable(sources);
        this.analyses = Map.copyOf(analyses);
    }

    private static Map<String, LinkedHashMap<String, String>> deepImmutable(
            Map<String, LinkedHashMap<String, String>> input) {
        LinkedHashMap<String, LinkedHashMap<String, String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, String>> e : input.entrySet()) {
            out.put(e.getKey(), new LinkedHashMap<>(Collections.unmodifiableMap(e.getValue())));
        }
        return Collections.unmodifiableMap(out);
    }

    public List<String> languages() {
        return new ArrayList<>(messages.keySet());
    }

    /** Builds derived structures (resolved sources and parsed schemas) for a fully validated batch. */
    public static Snapshot build(long version, String base, List<String> keys,
                                 Map<String, LinkedHashMap<String, String>> messages,
                                 Map<String, String> fallback) {
        Map<String, LinkedHashMap<String, String>> sources = new LinkedHashMap<>();
        Map<String, Map<String, TemplateAnalyzer.Analysis>> analyses = new LinkedHashMap<>();
        for (String lang : messages.keySet()) {
            sources.put(lang, FallbackResolver.resolveSources(lang, base, fallback, messages, keys));
            LinkedHashMap<String, TemplateAnalyzer.Analysis> perKey = new LinkedHashMap<>();
            Map<String, String> srcMap = sources.get(lang);
            for (String key : keys) {
                String srcLang = srcMap.get(key);
                String template = messages.get(srcLang).get(key);
                perKey.put(key, TemplateAnalyzer.analyze(template));
            }
            analyses.put(lang, Collections.unmodifiableMap(perKey));
        }
        return new Snapshot(version, base, keys, messages, fallback, sources, analyses);
    }
}
