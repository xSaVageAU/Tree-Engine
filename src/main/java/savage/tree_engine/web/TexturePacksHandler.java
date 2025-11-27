package savage.tree_engine.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.MainConfig;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class TexturePacksHandler implements HttpHandler {
    private static final Gson GSON = new Gson();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try {
            Path texturesDir = MainConfig.getTexturesDir();
            List<String> packs = new ArrayList<>();
            
            if (Files.exists(texturesDir)) {
                try (Stream<Path> stream = Files.list(texturesDir)) {
                    stream.forEach(path -> {
                        String fileName = path.getFileName().toString();
                        if (Files.isDirectory(path)) {
                            packs.add(fileName);
                        } else if (fileName.endsWith(".zip")) {
                            packs.add(fileName.substring(0, fileName.length() - 4));
                        }
                    });
                }
            }
            
            String json = GSON.toJson(packs);
            
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, json.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes());
            }
            
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to list texture packs", e);
            exchange.sendResponseHeaders(500, -1);
        }
    }
}
