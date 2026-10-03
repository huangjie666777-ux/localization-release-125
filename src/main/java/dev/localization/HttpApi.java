package dev.localization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** Javalin wiring: publish, render, language pack download and version listing. */
public final class HttpApi {

    private final VersionStore store;
    private final Limits limits;
    private final ReleaseValidator validator;
    private final LocalizationService service;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpApi(VersionStore store, Limits limits) {
        this.store = store;
        this.limits = limits;
        this.validator = new ReleaseValidator(limits);
        this.service = new LocalizationService(limits.maxTemplateDepth());
    }

    public Javalin build() {
        Javalin app = Javalin.create(config ->
                config.http.maxRequestSize = limits.maxBodyBytes());
        app.post("/api/releases", this::publish);
        app.get("/api/versions", this::listVersions);
        app.get("/api/versions/latest", this::latest);
        app.post("/api/versions/latest/render", this::renderLatest);
        app.get("/api/versions/latest/languages/{lang}/pack", this::packLatest);
        app.post("/api/versions/{version}/render", this::render);
        app.get("/api/versions/{version}/languages/{lang}/pack", this::pack);

        app.exception(ValidationException.class, (e, ctx) ->
                writeError(ctx, 400, "validation_failed", e.errors()));
        app.exception(TemplateException.class, (e, ctx) ->
                writeError(ctx, 400, "template_error", java.util.List.of(e.getMessage())));
        app.exception(ConflictException.class, (e, ctx) ->
                writeError(ctx, 409, "version_conflict", java.util.List.of(e.getMessage()),
                        Map.of("currentVersion", e.currentVersion(),
                              "expectedVersion", e.expectedVersion())));
        app.exception(NotFoundException.class, (e, ctx) ->
                writeError(ctx, 404, "not_found", java.util.List.of(e.getMessage())));
        app.exception(NotFoundResponse.class, (e, ctx) ->
                writeError(ctx, 404, "not_found", java.util.List.of(e.getMessage() == null ? "not found" : e.getMessage())));
        app.exception(BadRequestResponse.class, (e, ctx) ->
                writeError(ctx, 400, "bad_request", java.util.List.of(e.getMessage())));
        app.exception(NumberFormatException.class, (e, ctx) ->
                writeError(ctx, 400, "bad_request", java.util.List.of("version must be an integer")));
        app.exception(IllegalArgumentException.class, (e, ctx) ->
                writeError(ctx, 400, "bad_request", java.util.List.of(e.getMessage())));
        return app;
    }

    private void publish(Context ctx) {
        String body = ctx.body();
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            throw new ValidationException("invalid JSON: " + firstLine(e.getMessage()));
        }
        if (node == null || !node.isObject()) {
            throw new ValidationException("request body must be a JSON object");
        }
        Integer expected = null;
        if (node.has("expectedVersion") && !node.get("expectedVersion").isNull()) {
            JsonNode ev = node.get("expectedVersion");
            if (!ev.isInt() || ev.intValue() < 0) {
                throw new ValidationException("expectedVersion must be a non-negative integer");
            }
            expected = ev.intValue();
        }
        // Validate while holding the publish lock so expectedVersion cannot race.
        ReleaseBundle bundle = store.publish(expected,
                (currentMax, base) -> validator.validate(body, limits),
                b -> b);
        ctx.status(201).json(Map.of(
                "version", bundle.version(),
                "baseLanguage", bundle.baseLanguage(),
                "languages", service.languages(bundle),
                "publishedAt", bundle.publishedAt()));
    }

    private void listVersions(Context ctx) {
        ctx.json(Map.of("versions", store.versions(),
                "latest", store.latest() == null ? 0 : store.latest().version()));
    }

    private void latest(Context ctx) {
        ReleaseBundle b = store.latest();
        if (b == null) {
            throw new NotFoundException("no versions published yet");
        }
        ctx.json(meta(b));
    }

    private void render(Context ctx) {
        ReleaseBundle bundle = requireVersion(Integer.parseInt(ctx.pathParam("version")));
        ctx.json(renderBundle(bundle, ctx.body()));
    }

    private void renderLatest(Context ctx) {
        ReleaseBundle bundle = requireLatest();
        ctx.json(renderBundle(bundle, ctx.body()));
    }

    private LocalizationService.RenderResult renderBundle(ReleaseBundle bundle, String body) {
        JsonNode node;
        try {
            node = mapper.readTree(body);
        } catch (Exception e) {
            throw new ValidationException("invalid JSON: " + firstLine(e.getMessage()));
        }
        if (node == null || !node.isObject()) {
            throw new ValidationException("render body must be a JSON object");
        }
        if (!node.has("language") || !node.get("language").isTextual()) {
            throw new ValidationException("missing string field 'language'");
        }
        if (!node.has("key") || !node.get("key").isTextual() || node.get("key").asText().isEmpty()) {
            throw new ValidationException("missing non-empty string field 'key'");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (node.has("parameters") && !node.get("parameters").isNull()) {
            JsonNode params = node.get("parameters");
            if (!params.isObject()) {
                throw new ValidationException("field 'parameters' must be an object");
            }
            params.fields().forEachRemaining(e -> args.put(e.getKey(), jsonScalar(e.getValue())));
        }
        return service.render(bundle, node.get("language").asText(), node.get("key").asText(), args);
    }

    private Object jsonScalar(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isNumber()) {
            return value.asDouble();
        }
        if (value.isNull()) {
            return null;
        }
        throw new ValidationException("parameter values must be strings, numbers or booleans");
    }

    private void pack(Context ctx) {
        ReleaseBundle bundle = requireVersion(Integer.parseInt(ctx.pathParam("version")));
        writePack(ctx, bundle, ctx.pathParam("lang"));
    }

    private void packLatest(Context ctx) {
        ReleaseBundle bundle = requireLatest();
        writePack(ctx, bundle, ctx.pathParam("lang"));
    }

    private void writePack(Context ctx, ReleaseBundle bundle, String lang) {
        LocalizationService.LanguagePack pack = service.pack(bundle, lang);
        ctx.json(pack);
    }

    private Map<String, Object> meta(ReleaseBundle b) {
        return Map.of("version", b.version(),
                "baseLanguage", b.baseLanguage(),
                "languages", service.languages(b),
                "fallbacks", b.fallbacks(),
                "publishedAt", b.publishedAt());
    }

    private ReleaseBundle requireVersion(int version) {
        ReleaseBundle b = store.get(version);
        if (b == null) {
            throw new NotFoundException("unknown version: " + version);
        }
        return b;
    }

    private ReleaseBundle requireLatest() {
        ReleaseBundle b = store.latest();
        if (b == null) {
            throw new NotFoundException("no versions published yet");
        }
        return b;
    }

    private void writeError(Context ctx, int status, String code, java.util.List<String> messages) {
        writeError(ctx, status, code, messages, Map.of());
    }

    private void writeError(Context ctx, int status, String code, java.util.List<String> messages,
                            Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("messages", messages);
        body.putAll(extra);
        ctx.status(status).json(body);
    }

    private String firstLine(String msg) {
        if (msg == null) {
            return "parse error";
        }
        int i = msg.indexOf('\n');
        return i > 0 ? msg.substring(0, i) : msg;
    }
}
