package com.spectatemod.forge.invsee;

import com.mojang.brigadier.CommandDispatcher;
import com.spectatemod.SpectateMod;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import com.mojang.brigadier.tree.CommandNode;

/** Forge command registration for /invsee. */
public final class InvseeCommand {
    private static CommandDispatcher<CommandSourceStack> dispatcher;
    private static InvseeService service;
    private static boolean initialized;
    private static boolean installed;

    private InvseeCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, InvseeService service) {
        InvseeCommand.dispatcher = dispatcher;
        InvseeCommand.service = service;
        refresh();
    }

    /** Rebuilds the root node when invsee.json enables or disables the command. */
    public static boolean refreshIfNeeded() {
        if (dispatcher == null || service == null) return false;
        boolean shouldInstall = service.config().isEnabled();
        if (initialized && installed == shouldInstall) return false;
        refresh();
        return true;
    }

    private static void refresh() {
        removeInvsee(dispatcher);
        if (service.config().isEnabled()) dispatcher.register(createCommand(service));
        installed = service.config().isEnabled();
        initialized = true;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> createCommand(InvseeService service) {
        return Commands.literal("invsee")
                .requires(source -> source.isPlayer() && service.config().isEnabled()
                        && InvseePermissions.canView(source.getPlayer()))
                .then(Commands.literal("reload")
                        .requires(source -> source.isPlayer() && SpectateMod.getConfigManager().getConfig().getAdminRoles().stream().anyMatch("op"::equals)
                                && source.getPlayer().hasPermissions(2))
                        .executes(context -> { service.reload(); context.getSource().sendSuccess(() -> InvseeMessages.RELOADED, true); return 1; }))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            ServerPlayer viewer = context.getSource().getPlayerOrException();
                            ServerPlayer target = EntityArgument.getPlayer(context, "player");
                            service.open(viewer, target); return 1;
                        }))
                .executes(context -> {
                    ServerPlayer viewer = context.getSource().getPlayerOrException();
                    var targetUuid = SpectateMod.getSpectateManager().getSpectatedTargetUuid(viewer.getUUID());
                    if (targetUuid == null) {
                        viewer.sendSystemMessage(InvseeMessages.NOT_SPECTATING);
                        return 1;
                    }
                    ServerPlayer target = viewer.getServer() == null ? null : viewer.getServer().getPlayerList().getPlayer(targetUuid);
                    if (target == null) viewer.sendSystemMessage(InvseeMessages.TARGET_OFFLINE); else service.openSpectated(viewer, target);
                    return 1;
                });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeInvsee(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null) return;
        try {
            Field childrenField = CommandNode.class.getDeclaredField("children");
            Field literalsField = CommandNode.class.getDeclaredField("literals");
            childrenField.setAccessible(true);
            literalsField.setAccessible(true);
            removeNode((Map) childrenField.get(dispatcher.getRoot()));
            removeNode((Map) literalsField.get(dispatcher.getRoot()));
        } catch (ReflectiveOperationException failure) {
            SpectateMod.LOGGER.warn("Could not refresh the Forge /invsee command tree", failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void removeNode(Map nodeMap) {
        if (nodeMap == null) return;
        nodeMap.remove("invsee");
        Iterator iterator = nodeMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Object entryObject = iterator.next();
            if (entryObject instanceof Map.Entry<?, ?> entry) {
                Object node = entry.getValue();
                if (node instanceof CommandNode<?> commandNode && "invsee".equalsIgnoreCase(commandNode.getName())) {
                    iterator.remove();
                }
            }
        }
    }
}
