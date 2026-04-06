package com.spectatemod.config;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class ModConfig {
    @SerializedName("_config_guide")
    private String configGuide = "SpectateMod Configuration Guide:\n" +
        "- admin_roles: Roles/tags that grant /spectate access. Supports either 'op,admin,mod' or ['op','admin','mod'].\n" +
        "- spectate_cooldown: Cooldown time in seconds before an admin can spectate again after stopping.\n" +
        "- prevent_combat_spectate: If true, prevents spectating when the admin is in combat or near hostile mobs.\n" +
        "- combat_check_radius: Radius in blocks to check for hostile mobs when prevent_combat_spectate is enabled.\n" +
        "- save_spectate_positions: If true, saves spectate positions to disk to prevent data loss on crashes.\n" +
        "- freecam_distance_limit: Max distance (in blocks) you can move away from the spectated player while in freecam.";
    
    @SerializedName("admin_roles")
    private Object adminRoles = "op";
    
    @SerializedName("spectate_cooldown")
    private int spectateCooldown = 30;
    
    @SerializedName("prevent_combat_spectate")
    private boolean preventCombatSpectate = true;
    
    @SerializedName("combat_check_radius")
    private double combatCheckRadius = 16.0;
    
    @SerializedName("save_spectate_positions")
    private boolean saveSpectatePositions = true;

    @SerializedName("freecam_distance_limit")
    private double freecamDistanceLimit = 30.0;
    
    public String getConfigGuide() { return configGuide; }
    
    public List<String> getAdminRoles() {
        return normalizeRoles(adminRoles);
    }
    
    public int getSpectateCooldown() { return spectateCooldown; }
    public boolean isPreventCombatSpectate() { return preventCombatSpectate; }
    public double getCombatCheckRadius() { return combatCheckRadius; }
    public boolean isSaveSpectatePositions() { return saveSpectatePositions; }
    public double getFreecamDistanceLimit() { return freecamDistanceLimit; }
    
    public void setAdminRoles(String adminRoles) {
        setAdminRoles((Object) adminRoles);
    }

    public void setAdminRoles(List<?> adminRoles) {
        setAdminRoles((Object) adminRoles);
    }

    /**
     * Accepts either:
     * - comma-separated String, e.g. "op,admin,mod"
     * - List of role/tag values, e.g. ["op", "admin", "mod"]
     * Any null/invalid input falls back to "op".
     */
    public void setAdminRoles(Object adminRoles) {
        if (adminRoles == null) {
            this.adminRoles = "op";
            return;
        }

        if (adminRoles instanceof List<?> roleList) {
            this.adminRoles = new ArrayList<>(roleList);
            return;
        }

        this.adminRoles = Objects.toString(adminRoles, "op");
    }
    public void setSpectateCooldown(int spectateCooldown) { this.spectateCooldown = spectateCooldown; }
    public void setPreventCombatSpectate(boolean preventCombatSpectate) { this.preventCombatSpectate = preventCombatSpectate; }
    public void setCombatCheckRadius(double combatCheckRadius) { this.combatCheckRadius = combatCheckRadius; }
    public void setSaveSpectatePositions(boolean saveSpectatePositions) { this.saveSpectatePositions = saveSpectatePositions; }
    public void setFreecamDistanceLimit(double freecamDistanceLimit) { this.freecamDistanceLimit = freecamDistanceLimit; }

    private List<String> parseRoleString(String rolesString) {
        if (rolesString == null || rolesString.isBlank()) {
            List<String> fallback = new ArrayList<>();
            fallback.add("op");
            return fallback;
        }

        List<String> roles = new ArrayList<>();
        for (String role : Arrays.asList(rolesString.split(","))) {
            String trimmed = role.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }

        if (!roles.isEmpty()) {
            return roles;
        }

        List<String> fallback = new ArrayList<>();
        fallback.add("op");
        return fallback;
    }

    private List<String> normalizeRoles(Object rolesValue) {
        if (rolesValue == null) {
            List<String> fallback = new ArrayList<>();
            fallback.add("op");
            return fallback;
        }

        if (rolesValue instanceof String rolesString) {
            return parseRoleString(rolesString);
        }

        if (rolesValue instanceof List<?> roleList) {
            List<String> normalized = new ArrayList<>();
            for (Object role : roleList) {
                if (role == null) {
                    continue;
                }
                String roleName = role.toString().trim();
                if (!roleName.isEmpty()) {
                    normalized.add(roleName);
                }
            }
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }

        return parseRoleString(Objects.toString(rolesValue, "op"));
    }
}
