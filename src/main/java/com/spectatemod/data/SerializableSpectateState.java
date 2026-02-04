package com.spectatemod.data;

import com.google.gson.annotations.SerializedName;

public class SerializableSpectateState {
    @SerializedName("admin_uuid")
    private String adminUuid;
    @SerializedName("target_uuid")
    private String targetUuid;
    @SerializedName("position_x")
    private double positionX;
    @SerializedName("position_y")
    private double positionY;
    @SerializedName("position_z")
    private double positionZ;
    @SerializedName("yaw")
    private float yaw;
    @SerializedName("pitch")
    private float pitch;
    @SerializedName("game_mode")
    private String gameMode;
    @SerializedName("dimension")
    private String dimension;
    @SerializedName("start_time")
    private long startTime;

    public SerializableSpectateState() {
    }

    public SerializableSpectateState(String adminUuid, String targetUuid,
            double positionX, double positionY, double positionZ,
            float yaw, float pitch,
            String gameMode, String dimension, long startTime) {
        this.adminUuid = adminUuid;
        this.targetUuid = targetUuid;
        this.positionX = positionX;
        this.positionY = positionY;
        this.positionZ = positionZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.gameMode = gameMode;
        this.dimension = dimension;
        this.startTime = startTime;
    }

    public String getAdminUuid() {
        return adminUuid;
    }

    public String getTargetUuid() {
        return targetUuid;
    }

    public double getPositionX() {
        return positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public double getPositionZ() {
        return positionZ;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public String getGameMode() {
        return gameMode;
    }

    public String getDimension() {
        return dimension;
    }

    public long getStartTime() {
        return startTime;
    }
}
