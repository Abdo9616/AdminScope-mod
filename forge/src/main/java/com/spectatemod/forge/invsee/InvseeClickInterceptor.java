package com.spectatemod.forge.invsee;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lets invsee menus receive ordinary container clicks while the viewer is a spectator.
 * Vanilla rejects those packets before calling the menu, so this is intentionally attached
 * to the player's connection rather than changing the viewer's game mode.
 */
public final class InvseeClickInterceptor {
    private static final String HANDLER_NAME = "adminscope_invsee_clicks";

    private InvseeClickInterceptor() {
    }

    public static void install(ServerPlayer player) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get(HANDLER_NAME) != null) return;

        pipeline.addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                // Traveler's settings button sends a custom packet rather than a normal
                // container click. Reject only that action while the invsee native menu is
                // open, so a failed client-side guard can never open the viewer's own settings.
                if (isInvseeSettingsAction(player, message)) return;

                if (message instanceof ServerboundContainerClickPacket packet
                        && player.isSpectator()
                        && player.containerMenu != null
                        && packet.getContainerId() == player.containerMenu.containerId
                        && player.containerMenu instanceof InvseeMenu menu) {
                    // Packet decoding happens on Netty's event loop. Process the click on the
                    // server thread, just as vanilla's packet listener would.
                    player.getServer().execute(() -> handleClick(player, menu, packet));
                    return;
                }
                if (message instanceof ServerboundContainerClickPacket packet
                        && player.isSpectator()
                        && player.containerMenu != null
                        && packet.getContainerId() == player.containerMenu.containerId
                        && player.containerMenu instanceof ReadonlyBackpackMenu menu) {
                    player.getServer().execute(() -> handleBackpackClick(player, menu, packet));
                    return;
                }
                super.channelRead(context, message);
            }
        });
    }

    public static void remove(ServerPlayer player) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get(HANDLER_NAME) != null) pipeline.remove(HANDLER_NAME);
    }

    private static void handleClick(ServerPlayer player, InvseeMenu menu,
                                     ServerboundContainerClickPacket packet) {
        if (player.containerMenu != menu || !menu.stillValid(player)) {
            menu.sendAllDataToRemote();
            return;
        }

        menu.clicked(packet.getSlotNum(), packet.getButtonNum(), packet.getClickType(), player);
        if (player.containerMenu == menu) {
            // Reconcile the complete menu after handling the click. This also covers drag,
            // pickup-all, and carried-item updates without relying on vanilla's spectator path.
            menu.broadcastFullState();
        }
    }

    private static void handleBackpackClick(ServerPlayer player, ReadonlyBackpackMenu menu,
                                             ServerboundContainerClickPacket packet) {
        if (player.containerMenu != menu || !menu.stillValid(player)) {
            menu.sendAllDataToRemote();
            return;
        }

        menu.clicked(packet.getSlotNum(), packet.getButtonNum(), packet.getClickType(), player);
        if (player.containerMenu == menu) {
            menu.broadcastFullState();
        }
    }

    private static boolean isInvseeSettingsAction(ServerPlayer player, Object message) {
        if (player.containerMenu == null
                || !"com.spectatemod.forge.invsee.ReadonlyBackpackMenu".equals(
                player.containerMenu.getClass().getName())
                || !"com.tiviacz.travelersbackpack.network.ServerboundActionTagPacket".equals(
                message.getClass().getName())) {
            return false;
        }

        try {
            Object actionTag = message.getClass().getMethod("actionTag").invoke(message);
            return actionTag instanceof CompoundTag tag && tag.getInt("ActionType") == 10;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }
}
