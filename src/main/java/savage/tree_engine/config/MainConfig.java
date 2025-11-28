package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MainConfig {
    public int server_port = 3000;
    public boolean dev_mode_enabled = false;
    public String source_path = "";
    public boolean open_browser_on_start = false;
    
    public String auth_token = "";
    public boolean auth_enabled = true;
    public boolean regenerate_token_on_restart = true;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path CONFIG_DIR = Path.of("config", "tree_engine");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final Path TEXTURES_DIR = CONFIG_DIR.resolve("textures");
    private static final Path WEB_DIR = CONFIG_DIR.resolve("web");
    
    private static MainConfig instance;
    
    public static void init() {
        try {
            // Create directories
            Files.createDirectories(CONFIG_DIR);
            Files.createDirectories(TEXTURES_DIR);
            Files.createDirectories(WEB_DIR);
            
            // Load or create config
            if (Files.exists(CONFIG_FILE)) {
                // Use lenient reader to allow comments
                try (java.io.Reader reader = Files.newBufferedReader(CONFIG_FILE)) {
                    com.google.gson.stream.JsonReader jsonReader = new com.google.gson.stream.JsonReader(reader);
                    jsonReader.setLenient(true);
                    instance = GSON.fromJson(jsonReader, MainConfig.class);
                }
                // Save back to ensure format is updated
                save();
                TreeEngine.LOGGER.info("Loaded main config");
            } else {
                instance = new MainConfig();
                save();
                TreeEngine.LOGGER.info("Created default main config");
            }
            
            TreeEngine.LOGGER.info("Textures folder: " + TEXTURES_DIR.toAbsolutePath());
            TreeEngine.LOGGER.info("Web folder: " + WEB_DIR.toAbsolutePath());
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to initialize main config", e);
            instance = new MainConfig();
        }
    }
    
    public static void save() {
        try {
            // Manually build JSON string to include comments
            // Note: We use GSON.toJson() for values to handle escaping properly
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            
            sb.append("  // General Server Settings\n");
            sb.append("  \"server_port\": ").append(instance.server_port).append(",\n");
            sb.append("  \"dev_mode_enabled\": ").append(instance.dev_mode_enabled).append(",\n");
            sb.append("  \"source_path\": ").append(GSON.toJson(instance.source_path)).append(",\n");
            sb.append("  \"open_browser_on_start\": ").append(instance.open_browser_on_start).append(",\n");
            
            sb.append("\n  // Security & Authentication Settings\n");
            sb.append("  // If auth_token is empty, a new one is generated on webserver startup\n");
            sb.append("  \"auth_token\": ").append(GSON.toJson(instance.auth_token)).append(",\n");
            sb.append("  \"auth_enabled\": ").append(instance.auth_enabled).append(",\n");
            sb.append("  \"regenerate_token_on_restart\": ").append(instance.regenerate_token_on_restart).append("\n");
            
            sb.append("}");
            
            Files.writeString(CONFIG_FILE, sb.toString());
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to save main config", e);
        }
    }
    
    public static MainConfig get() {
        if (instance == null) {
            init();
        }
        return instance;
    }
    
    public static Path getTexturesDir() {
        return TEXTURES_DIR;
    }
    
    public static Path getWebDir() {
        return WEB_DIR;
    }
}
