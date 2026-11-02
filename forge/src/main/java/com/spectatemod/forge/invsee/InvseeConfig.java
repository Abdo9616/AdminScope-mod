package com.spectatemod.forge.invsee;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spectatemod.PlatformHooks;
import com.spectatemod.SpectateMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Forge-only configuration for the optional inventory viewer. */
public final class InvseeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = PlatformHooks.configDirectory().resolve("invsee.json");
    private boolean enabled = true;
    private boolean allowSelfView;
    private boolean logStaffActions = true;
    private List<Button> buttons = defaults();

    public InvseeConfig() { load(); }

    public synchronized void load() {
        JsonObject root = new JsonObject();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) root = parsed.getAsJsonObject();
            } catch (Exception e) {
                SpectateMod.LOGGER.warn("Invalid invsee.json; using safe defaults", e);
            }
        }
        enabled = bool(root, "enabled", true);
        allowSelfView = bool(root, "allowSelfView", false);
        logStaffActions = bool(root, "logStaffActions", true);
        buttons = parseButtons(root);
        writeMissing(root);
    }

    private void writeMissing(JsonObject root) {
        boolean changed = false;
        if (!root.has("enabled")) { root.addProperty("enabled", true); changed = true; }
        if (!root.has("allowSelfView")) { root.addProperty("allowSelfView", false); changed = true; }
        if (!root.has("logStaffActions")) { root.addProperty("logStaffActions", true); changed = true; }
        if (!root.has("menuButtons")) { root.add("menuButtons", toJson(buttons)); changed = true; }
        if (changed) {
            try {
                Files.createDirectories(path.getParent());
                try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) { GSON.toJson(root, writer); }
            } catch (IOException e) { SpectateMod.LOGGER.error("Could not write invsee.json", e); }
        }
    }

    private List<Button> parseButtons(JsonObject root) {
        if (!root.has("menuButtons") || !root.get("menuButtons").isJsonArray()) return defaults();
        List<Button> result = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("menuButtons")) {
            try {
                JsonObject o = element.getAsJsonObject();
                String id = o.get("id").getAsString();
                int page = o.get("page").getAsInt(), slot = o.get("slot").getAsInt();
                String item = o.get("item").getAsString(), title = o.get("title").getAsString();
                String action = o.has("action") ? o.get("action").getAsString() : "none";
                List<String> lines = new ArrayList<>();
                if (o.has("lines") && o.get("lines").isJsonArray()) for (JsonElement line : o.getAsJsonArray("lines")) lines.add(line.getAsString());
                result.add(new Button(id, page, slot, item, title, lines, action));
            } catch (Exception e) { SpectateMod.LOGGER.warn("Ignoring invalid invsee menu button", e); }
        }
        return result.isEmpty() ? defaults() : Collections.unmodifiableList(result);
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try { return o.has(key) ? o.get(key).getAsBoolean() : fallback; } catch (Exception ignored) { return fallback; }
    }

    private static JsonArray toJson(List<Button> values) {
        JsonArray array = new JsonArray();
        for (Button b : values) {
            JsonObject o = new JsonObject(); o.addProperty("id", b.id); o.addProperty("page", b.page); o.addProperty("slot", b.slot);
            o.addProperty("item", b.item); o.addProperty("title", b.title); o.addProperty("action", b.action);
            JsonArray lines = new JsonArray(); b.lines.forEach(lines::add); o.add("lines", lines); array.add(o);
        }
        return array;
    }

    private static List<Button> defaults() {
        List<Button> b = new ArrayList<>();
        b.add(new Button("xp", 1, 5, "minecraft:experience_bottle", "§e{xp_level} XP", List.of("§7Total XP: {xp_total}"), "none"));
        b.add(new Button("enderchest", 1, 6, "minecraft:ender_chest", "§6Open ender chest", List.of(), "open_enderchest"));
        b.add(new Button("health", 1, 7, "minecraft:golden_apple", "§c{health}/{max_health} ❤", List.of("§7Hunger: {hunger}"), "none"));
        b.add(new Button("next_page", 1, 8, "minecraft:arrow", "§aNext page", List.of(), "next_page"));
        b.add(new Button("location", 2, 20, "minecraft:compass", "§bLocation", List.of("§7{dimension}", "§7{xyz}"), "none"));
        b.add(new Button("previous_page", 2, 45, "minecraft:arrow", "§cBack", List.of(), "previous_page"));
        b.add(new Button("backpack", 2, 24, "minecraft:chest", "Open backpack", List.of(), "open_backpack"));
        return Collections.unmodifiableList(b);
    }

    public boolean isEnabled() { return enabled; }
    public boolean allowSelfView() { return allowSelfView; }
    public boolean logStaffActions() { return logStaffActions; }
    public List<Button> buttons() { return buttons; }
    public Path path() { return path; }

    public static final class Button {
        public final String id, item, title, action;
        public final int page, slot;
        public final List<String> lines;
        Button(String id, int page, int slot, String item, String title, List<String> lines, String action) {
            this.id=id; this.page=page; this.slot=slot; this.item=item; this.title=title; this.lines=List.copyOf(lines); this.action=action;
        }
    }
}
