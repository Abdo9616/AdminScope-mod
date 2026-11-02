package com.spectatemod.forge.invsee;

import net.minecraft.network.chat.Component;

/** Fixed English messages for Invsee; kept together for future localization. */
public final class InvseeMessages {
    public static final Component NO_PERMISSION = Component.literal("§cNo permission");
    public static final Component NO_ACTIVE_EFFECTS = Component.literal("§7No active effects");
    public static final Component BACK = Component.literal("Back");
    public static final Component NEXT_PAGE = Component.literal("Next page");
    public static final Component OPEN_ENDER_CHEST = Component.literal("Open ender chest");
    public static final Component OPEN_BACKPACK = Component.literal("Open backpack");
    public static final Component SELF_VIEW_DISABLED = Component.literal("§cYou cannot view your own inventory.");
    public static final Component NOT_SPECTATING = Component.literal("§cYou are not currently spectating anyone. Try /invsee <player>.");
    public static final Component TARGET_OFFLINE = Component.literal("§cThat player is no longer online.");
    public static final Component TARGET_DEAD = Component.literal("§cThat player is currently dead.");
    public static final Component NO_BACKPACK = Component.literal("§eThat player is not wearing a Traveler's Backpack.");
    public static final Component BACKPACK_OPEN_FAILED = Component.literal("§cCould not open Traveler's Backpack's menu. Make sure it is installed on both the server and client.");
    public static final Component INVSEE_DISABLED = Component.literal("§cThe inventory viewer is disabled.");
    public static final Component VIEW_PERMISSION = Component.literal("§cYou need invsee.view or invsee.edit.");
    public static final Component RELOADED = Component.literal("§aAdminScope configuration reloaded.");

    private InvseeMessages() {
    }
}
