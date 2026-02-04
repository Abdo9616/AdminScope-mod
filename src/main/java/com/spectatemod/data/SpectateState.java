package com.spectatemod.data;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.UUID;

public class SpectateState {
    private final UUID adminUuid;
    private final UUID targetUuid;
    private final Vec3d position;
    private final float yaw;
    private final float pitch;
    private final GameMode gameMode;
    private final RegistryKey<World> dimension;
    private final long startTime;

    public SpectateState(ServerPlayerEntity admin, ServerPlayerEntity target) {
        this.adminUuid = admin.getUuid();
        this.targetUuid = target.getUuid();
        this.position = new Vec3d(admin.getX(), admin.getY(), admin.getZ());
        this.yaw = admin.getYaw();
        this.pitch = admin.getPitch();
        this.gameMode = admin.interactionManager.getGameMode();
        this.dimension = admin.getEntityWorld().getRegistryKey();
        this.startTime = System.currentTimeMillis();
    }

    public SpectateState(UUID adminUuid, UUID targetUuid, Vec3d position,
            float yaw, float pitch, GameMode gameMode, RegistryKey<World> dimension, long startTime) {
        this.adminUuid = adminUuid;
        this.targetUuid = targetUuid;
        this.position = position;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.dimension = dimension;
        this.startTime = startTime;
    }

    public UUID getAdminUuid() {
        return adminUuid;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public Vec3d getPosition() {
        return position;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public RegistryKey<World> getDimension() {
        return dimension;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDurationSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
