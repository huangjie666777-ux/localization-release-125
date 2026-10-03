package dev.localization.release;

import java.nio.file.Path;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        Path dataDir = Path.of(System.getenv().getOrDefault("DATA_DIR", "data/versions"));
        VersionStore store = new VersionStore(dataDir);
        Publisher publisher = new Publisher(store);
        ApiServer server = new ApiServer(publisher, store);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.javalin().stop();
            store.close();
        }));
        server.javalin().start(port);
    }
}
