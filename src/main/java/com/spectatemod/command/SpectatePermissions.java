package com.spectatemod.command;

import com.spectatemod.SpectateMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.List;
import java.util.Set;

public final class SpectatePermissions {
    private SpectatePermissions() {
    }

    public static boolean hasPermission(CommandSourceStack source) {
        // Console/command blocks always have permission.
        if (!source.isPlayer()) {
            return true;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }

        if (source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }

        List<String> adminRoles = SpectateMod.getConfigManager().getConfig().getAdminRoles();
        Set<String> tags = player.entityTags();
        for (String role : adminRoles) {
            String trimmedRole = role.trim();
            if (trimmedRole.isEmpty()) {
                continue;
            }

            // "op" maps to the gamemaster permission check above.
            if (trimmedRole.equalsIgnoreCase("op")) {
                continue;
            }

            if (hasMatchingTag(tags, trimmedRole)) {
                return true;
            }
        }

        return false;
    }

    public static boolean hasPermission(ServerPlayer player) {
        return player != null && hasPermission(player.createCommandSourceStack());
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
