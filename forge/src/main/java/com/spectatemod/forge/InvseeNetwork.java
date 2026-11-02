package com.spectatemod.forge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** Small Forge channel used to identify the native Traveler screen opened by invsee. */
public final class InvseeNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("adminscope", "invsee"),
            () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    private static boolean registered;

    private InvseeNetwork() {
    }

    public static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, BackpackScreenMarker.class,
                BackpackScreenMarker::encode,
                BackpackScreenMarker::decode,
                BackpackScreenMarker::handle);
    }

    public static void markBackpackScreen(ServerPlayer player, int containerId) {
        // A vanilla client (or a client without AdminScope) does not have this optional
        // channel. The server-side settings packet guard remains authoritative for it.
        if (!CHANNEL.isRemotePresent(player.connection.connection)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new BackpackScreenMarker(containerId));
    }

    private record BackpackScreenMarker(int containerId) {
        private static void encode(BackpackScreenMarker message, FriendlyByteBuf buffer) {
            buffer.writeVarInt(message.containerId);
        }

        private static BackpackScreenMarker decode(FriendlyByteBuf buffer) {
            return new BackpackScreenMarker(buffer.readVarInt());
        }

        private static void handle(BackpackScreenMarker message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> InvseeBackpackClientGuard.markOpen(message.containerId)));
            context.setPacketHandled(true);
        }
    }
}
