package com.spectatemod.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.UUID;

public class SpectateState {
    private final UUID adminUuid;
    private final UUID targetUuid;
    private final Vec3 position;
    private final float yaw;
    private final float pitch;
    private final GameType gameMode;
    private final ResourceKey<Level> dimension;
    private final long startTime;

    public SpectateState(ServerPlayer admin, ServerPlayer target) {
        this.adminUuid = admin.getUUID();
        this.targetUuid = target.getUUID();
        this.position = new Vec3(admin.getX(), admin.getY(), admin.getZ());
        this.yaw = admin.getYRot();
        this.pitch = admin.getXRot();
        this.gameMode = admin.gameMode.getGameModeForPlayer();
        this.dimension = admin.level().dimension();
        this.startTime = System.currentTimeMillis();
    }

    public SpectateState(UUID adminUuid, UUID targetUuid, Vec3 position,
            float yaw, float pitch, GameType gameMode, ResourceKey<Level> dimension, long startTime) {
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

    public Vec3 getPosition() {
        return position;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public GameType getGameMode() {
        return gameMode;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public String getDimensionId() {
        if (dimension == null) {
            return "minecraft:overworld";
        }
        return dimension.identifier().toString();
    }

    public long getStartTime() {
        return startTime;
    }

    public long getDurationSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }
}
