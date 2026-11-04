package com.spectatemod.forge.invsee;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets invsee menus receive ordinary container clicks while the viewer is a spectator, and
 * stops Traveler's Backpack's own action packets from editing a backpack the viewer may only look at.
 * Vanilla rejects spectator clicks before calling the menu, so this is intentionally attached
 * to the player's connection rather than changing the viewer's game mode.
 */
public final class InvseeClickInterceptor {
    private static final String HANDLER_NAME = "adminscope_invsee_clicks";

    private static final String READONLY_BACKPACK_MENU = "com.spectatemod.forge.invsee.ReadonlyBackpackMenu";
    /** Traveler's Backpack registers all of its packets on this one channel (travelersbackpack:network). */
    private static final String TB_CHANNEL_NAMESPACE = "travelersbackpack";
    private static final String TB_CHANNEL_PATH = "network";
    /** Discriminator of ServerboundActionTagPacket in TB 9.1.x's channel registration. */
    private static final int TB_ACTION_TAG_DISCRIMINATOR = 10;
    /** ActionType that opens the backpack settings screen. */
    private static final int TB_ACTION_OPEN_SETTINGS = 10;

    private static final int PASS = 0;
    private static final int DROP = 1;
    private static final int DROP_AND_NOTIFY = 2;

    /**
     * Viewers who currently have an invsee Traveler's Backpack menu open, mapped to whether they
     * may edit. Written on the server thread, read on the Netty thread, so it is concurrent and
     * holds a plain boolean instead of asking the permission system from the network thread.
     */
    private static final Map<UUID, Boolean> BACKPACK_SESSIONS = new ConcurrentHashMap<>();

    private InvseeClickInterceptor() {
    }

    /** Marks {@code viewer} as looking at someone's backpack; call again to refresh the edit flag. */
    static void beginBackpackSession(ServerPlayer viewer, boolean canEdit) {
        BACKPACK_SESSIONS.put(viewer.getUUID(), canEdit);
    }

    static void endBackpackSession(UUID viewerId) {
        BACKPACK_SESSIONS.remove(viewerId);
    }

    public static void install(ServerPlayer player) {
        ChannelPipeline pipeline = player.connection.connection.channel().pipeline();
        if (pipeline.get(HANDLER_NAME) != null) return;

        pipeline.addBefore("packet_handler", HANDLER_NAME, new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
                // Traveler's Backpack's tabs, sort/transfer buttons, upgrade removal and filter
                // settings are not container clicks; they arrive as custom packets and act on the
                // open menu's backpack directly. Without this a view-only staff member could still
                // change (or take upgrades out of) the target's backpack.
                int verdict = travelersPacketVerdict(player, message);
                if (verdict != PASS) {
                    releaseDropped(message);
                    if (verdict == DROP_AND_NOTIFY) {
                        player.getServer().execute(() -> InvseeService.notifyDenied(player));
                    }
                    return;
                }

                // Main inventory viewer and ender chest viewer are both plain vanilla chest
                // menus that only need their clicks routed past vanilla's spectator check.
                if (message instanceof ServerboundContainerClickPacket packet
                        && player.isSpectator()
                        && player.containerMenu != null
                        && packet.getContainerId() == player.containerMenu.containerId
                        && isNativeInvseeMenu(player.containerMenu)) {
                    final AbstractContainerMenu menu = player.containerMenu;
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

    private static boolean isNativeInvseeMenu(AbstractContainerMenu menu) {
        return menu instanceof InvseeMenu || menu instanceof EnderChestMenu;
    }

    /**
     * Decides what to do with a packet while the viewer has an invsee backpack open.
     * View-only viewers lose every Traveler's Backpack packet (and are told why); viewers who may
     * edit lose only the settings screen, which would open the target's settings for them.
     */
    private static int travelersPacketVerdict(ServerPlayer player, Object message) {
        if (!(message instanceof ServerboundCustomPayloadPacket packet)) return PASS;
        Boolean canEdit = BACKPACK_SESSIONS.get(player.getUUID());
        if (canEdit == null) return PASS;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || !READONLY_BACKPACK_MENU.equals(menu.getClass().getName())) return PASS;
        ResourceLocation channel = packet.getIdentifier();
        if (!TB_CHANNEL_NAMESPACE.equals(channel.getNamespace()) || !TB_CHANNEL_PATH.equals(channel.getPath())) return PASS;
        if (!canEdit) return DROP_AND_NOTIFY;
        return isOpenSettingsAction(packet) ? DROP : PASS;
    }

    /** Reads the packet through an independent view of its data, so the real packet is untouched. */
    private static boolean isOpenSettingsAction(ServerboundCustomPayloadPacket packet) {
        try {
            FriendlyByteBuf data = packet.getData();
            if (data.readableBytes() < 1 || data.readUnsignedByte() != TB_ACTION_TAG_DISCRIMINATOR) return false;
            CompoundTag tag = data.readNbt();
            return tag != null && tag.getInt("ActionType") == TB_ACTION_OPEN_SETTINGS;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** A dropped custom payload still owns its buffer; free it since nothing downstream will. */
    private static void releaseDropped(Object message) {
        if (message instanceof ServerboundCustomPayloadPacket packet) {
            try {
                packet.getData().release();
            } catch (RuntimeException ignored) {
                // Already released or not reference counted; nothing more to do.
            }
        }
    }

    /**
     * Vanilla validates the slot index before calling the menu; this path bypasses vanilla,
     * so it must do the same. Valid values are -999 (outside the window), -1, or a real slot.
     */
    private static boolean isValidSlot(AbstractContainerMenu menu, int slot) {
        return slot == -999 || (slot >= -1 && menu.isValidSlotIndex(slot));
    }

    private static void handleClick(ServerPlayer player, AbstractContainerMenu menu,
                                     ServerboundContainerClickPacket packet) {
        if (player.containerMenu != menu || !menu.stillValid(player) || !isValidSlot(menu, packet.getSlotNum())) {
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
        if (player.containerMenu != menu || !menu.stillValid(player) || !isValidSlot(menu, packet.getSlotNum())) {
            menu.sendAllDataToRemote();
            return;
        }

        menu.clicked(packet.getSlotNum(), packet.getButtonNum(), packet.getClickType(), player);
        if (player.containerMenu == menu) {
            menu.broadcastFullState();
        }
    }
}