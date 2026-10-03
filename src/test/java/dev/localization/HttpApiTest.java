package dev.localization;

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

class HttpApiTest {

    private Javalin app;
    private int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path dir;

    @BeforeEach
    void start() {
        Limits limits = new Limits(128 * 1024, 100, 20, 10);
        VersionStore store = new VersionStore(dir);
        app = new HttpApi(store, limits).build();
        app.start(0);
        port = app.port();
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void publishFallbackRenderAndPackEndToEnd() throws Exception {
        String body = """
        {
          "expectedVersion":0,
          "baseLanguage":"en",
          "fallbacks":{"de-AT":"de","de":"en"},
          "languages":{
            "en":{"items":"{count,plural,one{# item} other{# items}}","hi":"Hello {name}!"},
            "de":{"hi":"Hallo {name}!"},
            "de-AT":{}
          }
        }
        """;
        HttpResponse<String> created = post("/api/releases", body);
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"version\":1"));

        // Optimistic conflict.
        HttpResponse<String> conflict = post("/api/releases", body);
        assertEquals(409, conflict.statusCode());

        // de-AT key hi falls back to de; items falls all the way to en.
        HttpResponse<String> renderDeAt = post("/api/versions/1/render",
                "{\"language\":\"de-AT\",\"key\":\"hi\",\"parameters\":{\"name\":\"Max\"}}");
        assertEquals(200, renderDeAt.statusCode(), renderDeAt.body());
        assertTrue(renderDeAt.body().contains("Hallo Max!"));
        assertTrue(renderDeAt.body().contains("\"sourceLanguage\":\"de\""));

        HttpResponse<String> renderItems = post("/api/versions/latest/render",
                "{\"language\":\"de-AT\",\"key\":\"items\",\"parameters\":{\"count\":2}}");
        assertEquals(200, renderItems.statusCode(), renderItems.body());
        assertTrue(renderItems.body().contains("2 items"));
        assertTrue(renderItems.body().contains("\"sourceLanguage\":\"en\""));

        // Bad parameter types and extra params.
        assertEquals(400, post("/api/versions/1/render",
                "{\"language\":\"en\",\"key\":\"items\",\"parameters\":{\"count\":\"many\"}}")
                .statusCode());
        assertEquals(400, post("/api/versions/1/render",
                "{\"language\":\"en\",\"key\":\"items\",\"parameters\":{\"count\":1,\"x\":2}}")
                .statusCode());
        assertEquals(404, post("/api/versions/1/render",
                "{\"language\":\"fr\",\"key\":\"items\"}").statusCode());

        // Pack keeps templates and sources.
        HttpResponse<String> pack = get("/api/versions/1/languages/de-AT/pack");
        assertEquals(200, pack.statusCode(), pack.body());
        assertTrue(pack.body().contains("Hallo {name}!"));
        assertTrue(pack.body().contains("\"items\":\"en\""));
        assertTrue(pack.body().contains("\"hi\":\"de\""));
    }
}
