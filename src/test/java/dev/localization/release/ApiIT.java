package dev.localization.release;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Full-stack smoke test against a real Javalin server. */
class ApiIT {

    @TempDir
    Path dir;
    private Javalin app;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        VersionStore store = new VersionStore(dir);
        app = new ApiServer(new Publisher(store), store).javalin();
        app.start(0);
        port = app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String publishBody() {
        return """
            {
              "expectedVersion": 0,
              "base": "en",
              "fallback": { "zh-Hant": "zh", "zh": "en" },
              "messages": {
                "en": {
                  "photos": "{gender, select, female {{count, plural, offset:1 =0 {no photos} =1 {her photo} other {her # photos}}} other {{count, plural, offset:1 =0 {no photos} =1 {one photo} other {# photos}}}}",
                  "hello": "Hello {name}!"
                },
                "zh": { "photos": "{gender, select, female {{count, plural, offset:1 =0 {无照片} =1 {她的照片} other {她的#张照片}}} other {{count, plural, offset:1 =0 {无照片} =1 {一张照片} other {#张照片}}}}" },
                "zh-Hant": {}
              }
            }
            """;
    }

    @Test
    void publishFallbackRenderAndPack() throws Exception {
        HttpResponse<String> pub = post("/v1/versions", publishBody());
        assertEquals(201, pub.statusCode(), pub.body());
        assertEquals(1, mapper.readTree(pub.body()).get("version").asInt());

        HttpResponse<String> conflict = post("/v1/versions", publishBody());
        assertEquals(409, conflict.statusCode());
        assertEquals(1, mapper.readTree(get("/v1/versions").body()).get("currentVersion").asInt());

        String renderReq = """
            { "version": "latest", "lang": "zh-Hant", "key": "photos",
              "params": { "gender": "female", "count": 5 } }
            """;
        HttpResponse<String> render = post("/v1/render", renderReq);
        assertEquals(200, render.statusCode(), render.body());
        JsonNode rj = mapper.readTree(render.body());
        assertEquals("她的4张照片", rj.get("text").asText());
        assertEquals("zh", rj.get("sourceLang").asText());

        String enReq = """
            { "lang": "en", "key": "photos", "params": { "gender": "other", "count": 2 } }
            """;
        HttpResponse<String> enRender = post("/v1/render", enReq);
        assertEquals("1 photos", mapper.readTree(enRender.body()).get("text").asText());

        String badSelect = """
            { "lang": "en", "key": "photos", "params": { "gender": 1, "count": 2 } }
            """;
        String missing = """
            { "lang": "en", "key": "photos", "params": { "gender": "other" } }
            """;
        String infinite = """
            { "lang": "en", "key": "photos", "params": { "gender": "other", "count": "Infinity" } }
            """;
        assertEquals(400, post("/v1/render", badSelect).statusCode());
        assertEquals(400, post("/v1/render", missing).statusCode());
        assertEquals(400, post("/v1/render", infinite).statusCode());

        HttpResponse<String> pack = get("/v1/versions/latest/languages/zh-Hant/pack");
        assertEquals(200, pack.statusCode(), pack.body());
        JsonNode pj = mapper.readTree(pack.body());
        assertEquals("zh", pj.get("sources").get("photos").asText());
        assertEquals("en", pj.get("sources").get("hello").asText());
        assertTrue(pj.get("messages").get("hello").asText().contains("{name}"));

        assertEquals(400, get("/v1/versions/latest/languages/not_a_tag/pack").statusCode());

        String dupKeys = """
            { "expectedVersion": 1, "base": "en", "messages": { "en": { "k": "a", "k": "b" } } }
            """;
        assertEquals(400, post("/v1/versions", dupKeys).statusCode());

        String badFmt = """
            { "expectedVersion": 1, "base": "en",
              "messages": { "en": { "k": "{d, date, short}" } } }
            """;
        assertEquals(400, post("/v1/versions", badFmt).statusCode());
        assertEquals(1, mapper.readTree(get("/v1/versions").body()).get("currentVersion").asInt());
    }
}
