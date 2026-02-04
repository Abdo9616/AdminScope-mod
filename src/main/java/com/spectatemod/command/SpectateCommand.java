package com.spectatemod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.spectatemod.SpectateMod;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class SpectateCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        removeVanillaSpectate(dispatcher);
        dispatcher.register(CommandManager.literal("spectate")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                        .requires(source -> hasPermission(source))
                        .executes(SpectateCommand::spectatePlayer))
                .then(CommandManager.literal("stop")
                        .requires(source -> hasPermission(source))
                        .executes(SpectateCommand::stopSpectating))
                .then(CommandManager.literal("reload")
                        .requires(source -> hasPermission(source))
                        .executes(SpectateCommand::reloadConfig)));
    }

    @SuppressWarnings("unchecked")
    private static void removeVanillaSpectate(CommandDispatcher<ServerCommandSource> dispatcher) {
        try {
            var root = dispatcher.getRoot();
            Field childrenField = CommandNode.class.getDeclaredField("children");
            Field literalsField = CommandNode.class.getDeclaredField("literals");
            childrenField.setAccessible(true);
            literalsField.setAccessible(true);

            Map<String, CommandNode<ServerCommandSource>> children =
                    (Map<String, CommandNode<ServerCommandSource>>) childrenField.get(root);
            Map<String, CommandNode<ServerCommandSource>> literals =
                    (Map<String, CommandNode<ServerCommandSource>>) literalsField.get(root);

            boolean removed = false;
            if (children != null) {
                removed = children.remove("spectate") != null || removed;
            }
            if (literals != null) {
                removed = literals.remove("spectate") != null || removed;
            }

            if (removed) {
                SpectateMod.LOGGER.info("Replaced vanilla /spectate command with Admin Spectator");
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.warn("Could not remove vanilla /spectate command; conflicts may remain", e);
        }
    }

    private static boolean hasPermission(ServerCommandSource source) {
        if (!source.isExecutedByPlayer()) {
            return true;
        }

        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            return false;
        }

        // Stable OP check using the server's op list
        String playerName = player.getName().getString();
        for (String opName : source.getServer().getPlayerManager().getOpList().getNames()) {
            if (opName.equalsIgnoreCase(playerName)) {
                return true;
            }
        }

        List<String> adminRoles = SpectateMod.getConfigManager().getConfig().getAdminRoles();
        for (String role : adminRoles) {
            String trimmedRole = role.trim();
            if (!trimmedRole.equalsIgnoreCase("op") && player.getCommandTags().contains(trimmedRole)) {
                return true;
            }
        }

        return false;
    }

    private static int spectatePlayer(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            ServerPlayerEntity admin = source.getPlayerOrThrow();
            ServerPlayerEntity target = EntityArgumentType.getPlayer(context, "player");

            if (!hasPermission(source)) {
                admin.sendMessage(Text.literal("§cYou do not have permission to use this command!"), false);
                return 0;
            }

            if (!SpectateMod.getSpectateManager().canSpectate(admin, target)) {
                return 0;
            }

            SpectateMod.getSpectateManager().startSpectating(admin, target);
            return 1;

        } catch (Exception e) {
            SpectateMod.LOGGER.error("Error in spectate command", e);
            context.getSource().sendError(Text.literal("§cAn error occurred while executing the command."));
            return 0;
        }
    }

    private static int stopSpectating(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();
            ServerPlayerEntity admin = source.getPlayerOrThrow();

            if (!hasPermission(source)) {
                admin.sendMessage(Text.literal("§cYou do not have permission to use this command!"), false);
                return 0;
            }

            SpectateMod.getSpectateManager().stopSpectating(admin);
            return 1;

        } catch (Exception e) {
            SpectateMod.LOGGER.error("Error in spectate stop command", e);
            context.getSource().sendError(Text.literal("§cAn error occurred while executing the command."));
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        try {
            ServerCommandSource source = context.getSource();

            if (!hasPermission(source)) {
                source.sendError(Text.literal("§cYou do not have permission to use this command!"));
                return 0;
            }

            SpectateMod.getConfigManager().reloadConfig();
            source.sendFeedback(() -> Text.literal("§aSpectate Mod configuration reloaded successfully!"), true);

            return 1;

        } catch (Exception e) {
            SpectateMod.LOGGER.error("Error in spectate reload command", e);
            context.getSource().sendError(Text.literal("§cAn error occurred while reloading the configuration."));
            return 0;
        }
    }
}
