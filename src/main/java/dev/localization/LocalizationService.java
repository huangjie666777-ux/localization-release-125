package dev.localization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-side logic: fallback resolution, rendering and full language packs. */
public final class LocalizationService {

    private final Renderer renderer;

    public LocalizationService(int maxTemplateDepth) {
        this.renderer = new Renderer(maxTemplateDepth);
    }

    /** Resolve the template and the language that actually supplies it. */
    public Resolved resolve(ReleaseBundle bundle, String language, String key) {
        if (!known(bundle, language)) {
            throw new NotFoundException("unknown target language: '" + language + "'");
        }
        Map<String, String> own = bundle.messages().getOrDefault(language, Map.of());
        if (own.containsKey(key)) {
            return new Resolved(own.get(key), language);
        }
        for (String ancestor : chainOf(bundle, language)) {
            Map<String, String> candidate = bundle.messages().getOrDefault(ancestor, Map.of());
            if (candidate.containsKey(key)) {
                return new Resolved(candidate.get(key), ancestor);
            }
        }
        throw new NotFoundException("unknown key: '" + key + "'");
    }

    public RenderResult render(ReleaseBundle bundle, String language, String key,
                               Map<String, Object> args) {
        Resolved resolved = resolve(bundle, language, key);
        String text = renderer.render(resolved.template(), resolved.sourceLanguage(), args);
        return new RenderResult(bundle.version(), language, key, resolved.sourceLanguage(), text);
    }

    /** Full fallback-applied language pack: every base key, with template + source. */
    public LanguagePack pack(ReleaseBundle bundle, String language) {
        if (!known(bundle, language)) {
            throw new NotFoundException("unknown target language: '" + language + "'");
        }
        Map<String, String> base = bundle.messages().get(bundle.baseLanguage());
        Map<String, String> messages = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String key : base.keySet()) {
            Resolved resolved = resolve(bundle, language, key);
            messages.put(key, resolved.template());
            sources.put(key, resolved.sourceLanguage());
        }
        return new LanguagePack(bundle.version(), language, bundle.baseLanguage(), messages, sources);
    }

    public List<String> languages(ReleaseBundle bundle) {
        return new ArrayList<>(bundle.messages().keySet());
    }

    private boolean known(ReleaseBundle bundle, String language) {
        return bundle.messages().containsKey(language);
    }

    private List<String> chainOf(ReleaseBundle bundle, String language) {
        List<String> chain = new ArrayList<>();
        String cur = language;
        // Walk explicit edges; chains were validated at publish time and end at base.
        while (true) {
            String next = bundle.fallbacks().get(cur);
            if (next == null) {
                return chain;
            }
            chain.add(next);
            cur = next;
        }
    }

    public record Resolved(String template, String sourceLanguage) {
    }

    public record RenderResult(int version, String language, String key,
                               String sourceLanguage, String rendered) {
    }

    public record LanguagePack(int version, String language, String baseLanguage,
                               Map<String, String> messages, Map<String, String> sources) {
    }
}
