package dev.localization.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.HttpStatus;
import io.javalin.http.NotFoundResponse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP layer: batch publish, render and language-pack download. */
public final class ApiServer {

    private final Javalin app;
    private final ObjectMapper mapper = JsonMapperBuilder.build();

    private static final class JsonMapperBuilder {
        static ObjectMapper build() {
            ObjectMapper m = new ObjectMapper();
            // Reject duplicate keys anywhere in the submitted JSON (bcp tags, message keys, ...).
            m.getFactory().configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
            return m;
        }
    }

    public ApiServer(Publisher publisher, VersionStore store) {
        this.app = Javalin.create(cfg -> cfg.http.maxRequestSize = Limits.MAX_BODY_BYTES);

        app.exception(Publisher.VersionConflictException.class, (e, ctx) -> {
            ctx.status(HttpStatus.CONFLICT);
            ctx.json(Map.of("error", e.getMessage(), "expectedVersion", e.expected,
                    "currentVersion", e.actual));
        });
        app.exception(IllegalArgumentException.class, (e, ctx) -> {
            ctx.status(HttpStatus.BAD_REQUEST);
            ctx.json(Map.of("error", String.valueOf(e.getMessage())));
        });
        app.exception(BadRequestResponse.class, (e, ctx) ->
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", String.valueOf(e.getMessage()))));

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok",
                "currentVersion", store.currentVersion())));

        app.post("/v1/versions", ctx -> {
            JsonNode root = readBody(ctx.body(), ctx.header("Content-Length"));
            long expected = requireLong(root, "expectedVersion");

            Publisher.Batch batch = new Publisher.Batch();
            JsonNode baseNode = root.get("base");
            if (baseNode == null || !baseNode.isTextual()) {
                throw new IllegalArgumentException("field 'base' must be a BCP 47 string");
            }
            batch.base = baseNode.asText();

            JsonNode messagesNode = root.get("messages");
            if (messagesNode == null || !messagesNode.isObject()) {
                throw new IllegalArgumentException("field 'messages' must be an object of language -> object");
            }
            batch.messages = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> langs = messagesNode.fields();
            while (langs.hasNext()) {
                Map.Entry<String, JsonNode> langEntry = langs.next();
                JsonNode tableNode = langEntry.getValue();
                if (!tableNode.isObject()) {
                    throw new IllegalArgumentException(
                            "messages." + langEntry.getKey() + " must be an object of key -> string");
                }
                LinkedHashMap<String, String> table = new LinkedHashMap<>();
                Iterator<Map.Entry<String, JsonNode>> fields = tableNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    if (!f.getValue().isTextual()) {
                        throw new IllegalArgumentException(
                                "messages." + langEntry.getKey() + "." + f.getKey()
                                        + " must be a string template");
                    }
                    table.put(f.getKey(), f.getValue().asText());
                }
                batch.messages.put(langEntry.getKey(), table);
            }

            batch.fallback = new LinkedHashMap<>();
            JsonNode fbNode = root.get("fallback");
            if (fbNode != null && !fbNode.isNull()) {
                if (!fbNode.isObject()) {
                    throw new IllegalArgumentException("field 'fallback' must be an object");
                }
                fbNode.fields().forEachRemaining(e -> {
                    if (!e.getValue().isTextual()) {
                        throw new IllegalArgumentException(
                                "fallback." + e.getKey() + " must be a language-tag string");
                    }
                    batch.fallback.put(e.getKey(), e.getValue().asText());
                });
            }

            Snapshot snap = publisher.publish(expected, batch);
            ctx.status(HttpStatus.CREATED);
            ctx.json(Map.of("version", snap.version, "base", snap.baseTag,
                    "languages", snap.languages()));
        });

        app.get("/v1/versions", ctx -> {
            long current = store.currentVersion();
            List<Long> versions = new ArrayList<>();
            for (long v = current; v >= 1; v--) versions.add(v);
            ctx.json(Map.of("currentVersion", current, "versions", versions));
        });

        app.post("/v1/render", ctx -> {
            JsonNode root = readBody(ctx.body(), ctx.header("Content-Length"));
            long version = resolveVersion(root.get("version"), store);
            Snapshot snap = store.version(version)
                    .orElseThrow(() -> new NotFoundResponse("unknown version: " + version));
            String lang = requireText(root, "lang");
            String key = requireText(root, "key");

            Map<String, Object> params = new LinkedHashMap<>();
            JsonNode pNode = root.get("params");
            if (pNode != null && !pNode.isNull()) {
                if (!pNode.isObject()) throw new IllegalArgumentException("params must be an object");
                pNode.fields().forEachRemaining(e -> params.put(e.getKey(), jsonScalar(e.getKey(), e.getValue())));
            }
            // Language tag is validated by schema lookup; give a clear error for invalid tags.
            String canonicalTag;
            try {
                canonicalTag = LanguageTags.requireValid(lang);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            String rendered = Renderer.render(snap, canonicalTag, key, params);
            String source = snap.sources.get(canonicalTag).get(key);
            ctx.json(Map.of("version", snap.version, "lang", canonicalTag, "key", key,
                    "sourceLang", source, "text", rendered));
        });

        app.get("/v1/versions/{version}/languages/{lang}/pack", ctx -> {
            String versionRaw = ctx.pathParam("version");
            long version;
            try {
                version = "latest".equals(versionRaw)
                        ? store.currentVersion() : Long.parseLong(versionRaw);
            } catch (NumberFormatException e) {
                throw new BadRequestResponse("version must be a number or 'latest'");
            }
            if (version == 0) throw new NotFoundResponse("no versions published yet");
            Snapshot snap = store.version(version)
                    .orElseThrow(() -> new NotFoundResponse("unknown version: " + version));
            String lang;
            try {
                lang = LanguageTags.requireValid(ctx.pathParam("lang"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            if (!snap.messages.containsKey(lang)) {
                throw new NotFoundResponse("unknown language in this version: " + lang);
            }
            ObjectNode root = mapper.createObjectNode();
            root.put("version", snap.version);
            root.put("base", snap.baseTag);
            root.put("lang", lang);
            ObjectNode messages = root.putObject("messages");
            ObjectNode sources = root.putObject("sources");
            LinkedHashMap<String, String> srcMap = snap.sources.get(lang);
            for (String k : snap.keys) {
                String src = srcMap.get(k);
                messages.put(k, snap.messages.get(src).get(k));
                sources.put(k, src);
            }
            ctx.contentType("application/json").result(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root));
        });
    }

    public Javalin javalin() {
        return app;
    }

    private static long resolveVersion(JsonNode node, VersionStore store) {
        if (node == null || node.isNull()) return store.currentVersion();
        if (node.isNumber()) {
            long v = node.asLong();
            if (v <= 0) throw new IllegalArgumentException("version must be positive");
            return v;
        }
        if (node.isTextual() && "latest".equals(node.asText())) return store.currentVersion();
        throw new IllegalArgumentException("version must be a positive number or 'latest'");
    }

    private static Object jsonScalar(String name, JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isIntegralNumber()) return node.asLong();
        if (node.isFloatingPointNumber()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        throw new IllegalArgumentException("parameter '" + name + "' must be a string or number");
    }

    private JsonNode readBody(String body, String contentLength) {
        if (body == null || body.isEmpty()) throw new BadRequestResponse("empty request body");
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw new BadRequestResponse("request body must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new BadRequestResponse("malformed JSON: " + e.getOriginalMessage());
        }
    }

    private static long requireLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || v.asLong() < 0) {
            throw new IllegalArgumentException("field '" + field + "' must be a non-negative integer");
        }
        return v.asLong();
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isEmpty()) {
            throw new IllegalArgumentException("field '" + field + "' must be a non-empty string");
        }
        return v.asText();
    }
}
