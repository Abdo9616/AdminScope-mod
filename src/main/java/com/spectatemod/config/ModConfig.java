package com.spectatemod.config;

import com.google.gson.annotations.SerializedName;
import java.util.Arrays;
import java.util.List;

public class ModConfig {
    @SerializedName("_config_guide")
    private String configGuide = "SpectateMod Configuration Guide:\n" +
        "- admin_roles: Comma-separated list of roles or tags that grant /spectate access. Use 'op' to include server operators.\n" +
        "- spectate_cooldown: Cooldown time in seconds before an admin can spectate again after stopping.\n" +
        "- prevent_combat_spectate: If true, prevents spectating when the admin is in combat or near hostile mobs.\n" +
        "- combat_check_radius: Radius in blocks to check for hostile mobs when prevent_combat_spectate is enabled.\n" +
        "- save_spectate_positions: If true, saves spectate positions to disk to prevent data loss on crashes.";
    
    @SerializedName("admin_roles")
    private String adminRoles = "op";
    
    @SerializedName("spectate_cooldown")
    private int spectateCooldown = 10;
    
    @SerializedName("prevent_combat_spectate")
    private boolean preventCombatSpectate = true;
    
    @SerializedName("combat_check_radius")
    private double combatCheckRadius = 16.0;
    
    @SerializedName("save_spectate_positions")
    private boolean saveSpectatePositions = true;
    
    public String getConfigGuide() { return configGuide; }
    
    public List<String> getAdminRoles() {
        return Arrays.asList(adminRoles.split(","));
    }
    
    public int getSpectateCooldown() { return spectateCooldown; }
    public boolean isPreventCombatSpectate() { return preventCombatSpectate; }
    public double getCombatCheckRadius() { return combatCheckRadius; }
    public boolean isSaveSpectatePositions() { return saveSpectatePositions; }
    
    public void setAdminRoles(String adminRoles) { this.adminRoles = adminRoles; }
    public void setSpectateCooldown(int spectateCooldown) { this.spectateCooldown = spectateCooldown; }
    public void setPreventCombatSpectate(boolean preventCombatSpectate) { this.preventCombatSpectate = preventCombatSpectate; }
    public void setCombatCheckRadius(double combatCheckRadius) { this.combatCheckRadius = combatCheckRadius; }
    public void setSaveSpectatePositions(boolean saveSpectatePositions) { this.saveSpectatePositions = saveSpectatePositions; }
}
