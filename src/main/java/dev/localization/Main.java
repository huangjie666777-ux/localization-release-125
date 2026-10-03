package dev.localization;

import io.javalin.Javalin;

import java.nio.file.Path;

/** Entry point. Configuration via env vars: LOC_PORT, LOC_DATA_DIR, LOC_MAX_*. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Limits limits = Limits.fromEnv();
        int port = Integer.parseInt(env("LOC_PORT", "8080"));
        Path dataDir = Path.of(env("LOC_DATA_DIR", "data"));
        VersionStore store = new VersionStore(dataDir);
        HttpApi api = new HttpApi(store, limits);
        Javalin app = api.build();
        app.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            store.close();
        }));
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }
}
