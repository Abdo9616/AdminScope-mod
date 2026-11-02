package com.spectatemod.forge.invsee;

import com.spectatemod.SpectateMod;
import com.spectatemod.command.SpectatePermissions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

/** Forge PermissionAPI nodes with the requested OP-level fallbacks. */
public final class InvseePermissions {
    public static final PermissionNode<Boolean> SPECTATE = new PermissionNode<>(
            SpectateMod.MOD_ID, "spectate", PermissionTypes.BOOLEAN,
            (player, uuid, contexts) -> player != null && player.hasPermissions(2));
    public static final PermissionNode<Boolean> VIEW = new PermissionNode<>(
            SpectateMod.MOD_ID, "invsee.view", PermissionTypes.BOOLEAN,
            (player, uuid, contexts) -> player != null && player.hasPermissions(2));
    public static final PermissionNode<Boolean> EDIT = new PermissionNode<>(
            SpectateMod.MOD_ID, "invsee.edit", PermissionTypes.BOOLEAN,
            (player, uuid, contexts) -> player != null && player.hasPermissions(3));
    public static final PermissionNode<Boolean> ENDER_CHEST = new PermissionNode<>(
            SpectateMod.MOD_ID, "invsee.enderchest", PermissionTypes.BOOLEAN,
            (player, uuid, contexts) -> player != null && player.hasPermissions(2));
    public static final PermissionNode<Boolean> BACKPACK = new PermissionNode<>(
            SpectateMod.MOD_ID, "invsee.backpack", PermissionTypes.BOOLEAN,
            (player, uuid, contexts) -> player != null && player.hasPermissions(2));

    private InvseePermissions() {
    }

    public static boolean canView(ServerPlayer player) {
        return hasConfiguredAdminRole(player)
                || player != null && (Boolean.TRUE.equals(PermissionAPI.getPermission(player, VIEW))
                || Boolean.TRUE.equals(PermissionAPI.getPermission(player, EDIT)));
    }

    public static boolean canEdit(ServerPlayer player) {
        return hasConfiguredAdminRole(player)
                || player != null && Boolean.TRUE.equals(PermissionAPI.getPermission(player, EDIT));
    }

    public static boolean canOpenEnderChest(ServerPlayer player) {
        return canView(player) && (hasConfiguredAdminRole(player)
                || Boolean.TRUE.equals(PermissionAPI.getPermission(player, ENDER_CHEST)));
    }

    public static boolean canOpenBackpack(ServerPlayer player) {
        return canView(player) && (hasConfiguredAdminRole(player)
                || Boolean.TRUE.equals(PermissionAPI.getPermission(player, BACKPACK)));
    }

    private static boolean hasConfiguredAdminRole(ServerPlayer player) {
        return SpectatePermissions.hasConfiguredAdminRole(player);
    }
}
