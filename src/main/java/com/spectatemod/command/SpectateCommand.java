package com.spectatemod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.spectatemod.SpectateMod;
import com.spectatemod.manager.SpectateManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

public class SpectateCommand {
    private static final Component INTERNAL_ERROR_MESSAGE =
            Component.literal("§cAn internal error occurred while executing the command.");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        removeVanillaSpectate(dispatcher);
        dispatcher.register(Commands.literal("spectate")
            .requires(SpectatePermissions::hasPermission)
            .then(Commands.argument("player", EntityArgument.player())
                .executes(SpectateCommand::startSpectating))
            .then(Commands.literal("stop")
                .executes(SpectateCommand::stopSpectating))
            .then(Commands.literal("pov")
                .executes(SpectateCommand::returnToTargetPov))
            .then(Commands.literal("reload")
                .executes(SpectateCommand::reloadConfig)));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeVanillaSpectate(CommandDispatcher<CommandSourceStack> dispatcher) {
        try {
            var root = dispatcher.getRoot();
            Field childrenField = CommandNode.class.getDeclaredField("children");
            Field literalsField = CommandNode.class.getDeclaredField("literals");
            childrenField.setAccessible(true);
            literalsField.setAccessible(true);

            Map children = (Map) childrenField.get(root);
            Map literals = (Map) literalsField.get(root);

            boolean removed = removeSpectateNode(children, "children")
                    || removeSpectateNode(literals, "literals");

            if (removed) {
                SpectateMod.LOGGER.info("Replaced vanilla /spectate command with AdminScope's implementation");
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.warn("Could not remove vanilla /spectate command; conflicts may remain", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean removeSpectateNode(Map nodeMap, String mapName) {
        if (nodeMap == null) {
            SpectateMod.LOGGER.debug("Command root {} map was null while removing /spectate", mapName);
            return false;
        }

        boolean removed = nodeMap.remove("spectate") != null;
        if (removed) {
            return true;
        }

        Iterator iterator = nodeMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Object entryObj = iterator.next();
            if (!(entryObj instanceof Map.Entry<?, ?> entry)) {
                SpectateMod.LOGGER.debug("Unexpected entry type in command {} map: {}", mapName,
                        entryObj == null ? "null" : entryObj.getClass().getName());
                continue;
            }

            Object keyObj = entry.getKey();
            if (keyObj instanceof String key && "spectate".equalsIgnoreCase(key)) {
                iterator.remove();
                return true;
            }

            Object nodeObj = entry.getValue();
            if (nodeObj instanceof CommandNode<?> commandNode
                    && "spectate".equalsIgnoreCase(commandNode.getName())) {
                iterator.remove();
                return true;
            }
        }

        return false;
    }

    private static int startSpectating(CommandContext<CommandSourceStack> context) {
        try {
            if (!SpectatePermissions.hasPermission(context.getSource())) {
                context.getSource().sendFailure(Component.literal("§cYou do not have permission to use this command!"));
                return 0;
            }

            ServerPlayer admin = context.getSource().getPlayerOrException();
            ServerPlayer target = EntityArgument.getPlayer(context, "player");

            SpectateManager manager = SpectateMod.getSpectateManager();
            if (manager.canSpectate(admin, target)) {
                manager.startSpectating(admin, target);
                return 1;
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Command failed", e);
            context.getSource().sendFailure(INTERNAL_ERROR_MESSAGE);
        }
        return 0;
    }

    private static int stopSpectating(CommandContext<CommandSourceStack> context) {
        try {
            if (!SpectatePermissions.hasPermission(context.getSource())) {
                context.getSource().sendFailure(Component.literal("§cYou do not have permission to use this command!"));
                return 0;
            }

            ServerPlayer admin = context.getSource().getPlayerOrException();
            SpectateManager manager = SpectateMod.getSpectateManager();

            if (manager.isSpectating(admin.getUUID())) {
                manager.stopSpectating(admin);
                return 1;
            } else {
                context.getSource().sendFailure(Component.literal("§cYou are not spectating anyone!"));
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Command failed", e);
            context.getSource().sendFailure(INTERNAL_ERROR_MESSAGE);
        }
        return 0;
    }

    private static int returnToTargetPov(CommandContext<CommandSourceStack> context) {
        try {
            ServerPlayer admin = context.getSource().getPlayerOrException();
            return SpectateMod.getSpectateManager().returnToTargetPov(admin) ? 1 : 0;
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Failed to return spectator to target POV", e);
            context.getSource().sendFailure(INTERNAL_ERROR_MESSAGE);
            return 0;
        }
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        if (!SpectatePermissions.hasPermission(context.getSource())) {
            context.getSource().sendFailure(Component.literal("§cYou do not have permission to use this command!"));
            return 0;
        }

        SpectateMod.getConfigManager().reloadConfig();
        context.getSource().sendSuccess(
                () -> Component.literal("§aSpectate Mod configuration reloaded successfully!"), true);
        return 1;
    }
}
