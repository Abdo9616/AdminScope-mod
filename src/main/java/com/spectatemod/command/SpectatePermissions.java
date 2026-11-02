package com.spectatemod.command;

import com.spectatemod.PlatformHooks;
import com.spectatemod.SpectateMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Set;

public final class SpectatePermissions {
    public static final String NODE = "adminscope.spectate";

    private SpectatePermissions() {
    }

    public static boolean hasPermission(CommandSourceStack source) {
        // Console/command blocks always have permission.
        if (!source.isPlayer()) {
            return true;
        }

        ServerPlayer player = source.getPlayer();
        return hasPermission(player);
    }

    public static boolean hasPermission(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        // Forge's permission API (including LuckPerms) can grant this node to a
        // non-operator. The config roles and vanilla OP fallback remain valid too.
        if (PlatformHooks.hasPermission(player, NODE) || player.hasPermissions(2)) {
            return true;
        }

        return hasConfiguredAdminRole(player);
    }

    /** Checks only the configured admin_roles/OP fallback for other AdminScope features. */
    public static boolean hasConfiguredAdminRole(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        List<String> adminRoles = SpectateMod.getConfigManager().getConfig().getAdminRoles();
        Set<String> tags = player.getTags();
        for (String role : adminRoles) {
            String trimmedRole = role.trim();
            if (trimmedRole.isEmpty()) {
                continue;
            }

            // "op" maps to the gamemaster permission check above.
            if (trimmedRole.equalsIgnoreCase("op")) {
                if (player.hasPermissions(2)) {
                    return true;
                }
                continue;
            }

            if (hasMatchingTag(tags, trimmedRole)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasMatchingTag(Set<String> playerTags, String roleTag) {
        for (String tag : playerTags) {
            if (tag.equalsIgnoreCase(roleTag)) {
                return true;
            }
        }
        return false;
    }
}
