package com.spectatemod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spectatemod.SpectateMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigManager {
    private static final String CONFIG_DIR = "config/spectatemod";
    private static final String CONFIG_FILE = "config.json";
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private ModConfig config;

    public ConfigManager() {
        this.config = new ModConfig();
    }

    public void loadConfig() {
        try {
            Path configPath = Paths.get(CONFIG_DIR);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
                SpectateMod.LOGGER.info("Created config directory: {}", CONFIG_DIR);
            }

            File configFile = new File(CONFIG_DIR, CONFIG_FILE);

            if (!configFile.exists()) {
                saveConfig();
                SpectateMod.LOGGER.info("Created default config file");
            } else {
                loadAndPatchConfig(configFile);
            }
        } catch (IOException e) {
            SpectateMod.LOGGER.error("Failed to load config", e);
        }
    }

    private void loadAndPatchConfig(File configFile) {
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject existingConfig = JsonParser.parseReader(reader).getAsJsonObject();

            ModConfig defaultConfig = new ModConfig();
            String defaultJson = gson.toJson(defaultConfig);
            JsonObject defaultConfigJson = JsonParser.parseString(defaultJson).getAsJsonObject();

            boolean needsPatching = false;

            for (String key : defaultConfigJson.keySet()) {
                if (!existingConfig.has(key)) {
                    existingConfig.add(key, defaultConfigJson.get(key));
                    needsPatching = true;
                    SpectateMod.LOGGER.info("Patched missing config field: {}", key);
                }
            }

            config = gson.fromJson(existingConfig, ModConfig.class);

            if (needsPatching) {
                saveConfig();
                SpectateMod.LOGGER.info("Config file patched with missing fields");
            }

            SpectateMod.LOGGER.info("Config loaded successfully");
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Failed to load config, using defaults", e);
            config = new ModConfig();
            saveConfig();
        }
    }

    public void saveConfig() {
        try {
            Path configPath = Paths.get(CONFIG_DIR);
            if (!Files.exists(configPath)) {
                Files.createDirectories(configPath);
            }

            File configFile = new File(CONFIG_DIR, CONFIG_FILE);
            try (FileWriter writer = new FileWriter(configFile)) {
                gson.toJson(config, writer);
                SpectateMod.LOGGER.info("Config saved successfully");
            }
        } catch (IOException e) {
            SpectateMod.LOGGER.error("Failed to save config", e);
        }
    }

    public void reloadConfig() {
        loadConfig();
        SpectateMod.LOGGER.info("Config reloaded");
    }

    public ModConfig getConfig() {
        return config;
    }
}
