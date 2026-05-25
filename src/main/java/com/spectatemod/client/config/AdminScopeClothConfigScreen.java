package com.spectatemod.client.config;

import com.spectatemod.SpectateMod;
import com.spectatemod.config.ConfigManager;
import com.spectatemod.config.ModConfig;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public final class AdminScopeClothConfigScreen {
    private AdminScopeClothConfigScreen() {
    }

    public static Screen create(Screen parent) {
        ConfigManager manager = SpectateMod.getConfigManager();
        if (manager == null) {
            manager = new ConfigManager();
            manager.loadConfig();
            SpectateMod.LOGGER.warn("Config manager was not initialized yet; loaded a temporary instance for the UI.");
        }

        ModConfig config = manager.getConfig();
        if (config == null) {
            config = new ModConfig();
        }

        String initialRoles = String.join(", ", config.getAdminRoles());
        String[] adminRoles = {initialRoles};
        int[] cooldownSeconds = {config.getSpectateCooldown()};
        boolean[] preventCombatSpectate = {config.isPreventCombatSpectate()};
        double[] combatRadius = {config.getCombatCheckRadius()};
        boolean[] saveSpectatePositions = {config.isSaveSpectatePositions()};
        double[] freecamDistance = {config.getFreecamDistanceLimit()};

        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("AdminScope Configuration"));

        ConfigEntryBuilder entries = builder.entryBuilder();
        ConfigCategory general = builder.getOrCreateCategory(Component.literal("General"));

        general.addEntry(entries.startStrField(Component.literal("Admin roles"), adminRoles[0])
                .setDefaultValue("op")
                .setTooltip(Component.literal("Comma-separated roles/tags. Example: op,admin,mod"))
                .setSaveConsumer(value -> adminRoles[0] = value)
                .build());

        general.addEntry(entries.startIntField(Component.literal("Spectate cooldown (seconds)"), cooldownSeconds[0])
                .setDefaultValue(30)
                .setMin(0)
                .setTooltip(Component.literal("Cooldown before an admin can spectate again."))
                .setSaveConsumer(value -> cooldownSeconds[0] = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Prevent combat spectate"), preventCombatSpectate[0])
                .setDefaultValue(true)
                .setTooltip(Component.literal("Block spectating while in combat or near hostile mobs."))
                .setSaveConsumer(value -> preventCombatSpectate[0] = value)
                .build());

        general.addEntry(entries.startDoubleField(Component.literal("Combat check radius"), combatRadius[0])
                .setDefaultValue(16.0)
                .setMin(0.0)
                .setTooltip(Component.literal("Hostile mob scan radius when combat prevention is enabled."))
                .setSaveConsumer(value -> combatRadius[0] = value)
                .build());

        general.addEntry(entries.startBooleanToggle(Component.literal("Save spectate positions"), saveSpectatePositions[0])
                .setDefaultValue(true)
                .setTooltip(Component.literal("Persist active spectate sessions for crash recovery."))
                .setSaveConsumer(value -> saveSpectatePositions[0] = value)
                .build());

        general.addEntry(entries.startDoubleField(Component.literal("Freecam distance limit"), freecamDistance[0])
                .setDefaultValue(30.0)
                .setMin(0.0)
                .setTooltip(Component.literal("Maximum freecam distance from spectated player. Use 0 to disable."))
                .setSaveConsumer(value -> freecamDistance[0] = value)
                .build());

        ConfigManager finalManager = manager;
        ModConfig finalConfig = config;
        builder.setSavingRunnable(() -> {
            finalConfig.setAdminRoles(parseRoles(adminRoles[0]));
            finalConfig.setSpectateCooldown(Math.max(0, cooldownSeconds[0]));
            finalConfig.setPreventCombatSpectate(preventCombatSpectate[0]);
            finalConfig.setCombatCheckRadius(Math.max(0.0, combatRadius[0]));
            finalConfig.setSaveSpectatePositions(saveSpectatePositions[0]);
            finalConfig.setFreecamDistanceLimit(Math.max(0.0, freecamDistance[0]));
            finalManager.saveConfig();
        });

        return builder.build();
    }

    private static List<String> parseRoles(String value) {
        List<String> roles = new ArrayList<>();
        if (value == null || value.isBlank()) {
            roles.add("op");
            return roles;
        }

        String[] split = value.split(",");
        for (String role : split) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }

        if (roles.isEmpty()) {
            roles.add("op");
        }
        return roles;
    }
}
