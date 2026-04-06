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
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SpectateCommand {
    private static final Component INTERNAL_ERROR_MESSAGE =
            Component.literal("§cAn internal error occurred while executing the command.");

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        removeVanillaSpectate(dispatcher);
        dispatcher.register(Commands.literal("spectate")
            .requires(SpectateCommand::hasPermission)
            .then(Commands.argument("player", EntityArgument.player())
                .executes(SpectateCommand::startSpectating))
            .then(Commands.literal("stop")
                .executes(SpectateCommand::stopSpectating))
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
            if (!hasPermission(context.getSource())) {
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
            if (!hasPermission(context.getSource())) {
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

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        if (!hasPermission(context.getSource())) {
            context.getSource().sendFailure(Component.literal("§cYou do not have permission to use this command!"));
            return 0;
        }

        SpectateMod.getConfigManager().reloadConfig();
        context.getSource().sendSuccess(
                () -> Component.literal("§aSpectate Mod configuration reloaded successfully!"), true);
        return 1;
    }

    private static boolean hasPermission(CommandSourceStack source) {
        if (!source.isPlayer()) {
            return true;
        }

        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return false;
        }

        if (isOperator(source, player)) {
            return true;
        }

        List<String> adminRoles = SpectateMod.getConfigManager().getConfig().getAdminRoles();
        Set<String> tags = player.entityTags();
        for (String role : adminRoles) {
            String trimmedRole = role.trim();
            if (trimmedRole.isEmpty()) {
                continue;
            }

            if (trimmedRole.equalsIgnoreCase("op")) {
                if (isOperator(source, player)) {
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

    private static boolean isOperator(CommandSourceStack source, ServerPlayer player) {
        try {
            var server = source.getServer();
            if (server == null || server.getPlayerList() == null) {
                return false;
            }

            Object playerList = server.getPlayerList();

            // Prefer stable API call for this mapping.
            try {
                return (boolean) playerList.getClass()
                        .getMethod("isOp", player.nameAndId().getClass())
                        .invoke(playerList, player.nameAndId());
            } catch (NoSuchMethodException ignored) {
                // TODO(AS-206): Revisit direct API call once mapping signatures are finalized.
            }

            // Fallback across mapping variants.
            Object[] candidates = {
                player.nameAndId(),
                player.getGameProfile(),
                player.getUUID()
            };

            for (Method method : playerList.getClass().getMethods()) {
                if (!"isOp".equals(method.getName())
                        || method.getParameterCount() != 1
                        || method.getReturnType() != boolean.class) {
                    continue;
                }

                Class<?> parameterType = method.getParameterTypes()[0];
                for (Object candidate : candidates) {
                    if (candidate != null && parameterType.isAssignableFrom(candidate.getClass())) {
                        return (boolean) method.invoke(playerList, candidate);
                    }
                }
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.debug("Unable to resolve OP status for {}", player.getName().getString(), e);
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
