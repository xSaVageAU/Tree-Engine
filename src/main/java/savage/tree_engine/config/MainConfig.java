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
    
    // Authentication settings
    public String auth_token = "";
    public boolean auth_enabled = true;
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
                String json = Files.readString(CONFIG_FILE);
                instance = GSON.fromJson(json, MainConfig.class);
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
            String json = GSON.toJson(instance);
            Files.writeString(CONFIG_FILE, json);
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
