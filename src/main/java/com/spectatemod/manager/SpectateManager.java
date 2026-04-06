package com.spectatemod.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.spectatemod.SpectateMod;
import com.spectatemod.data.SerializableSpectateState;
import com.spectatemod.data.SpectateState;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpectateManager {
    private static final String DATA_DIR = "config/spectatemod";
    private static final String DATA_FILE = "spectate_data.json";
    private static final Component INTERNAL_ERROR_MESSAGE =
            Component.literal("§cAn internal error occurred while executing the command.");
    private static final int INITIAL_CAMERA_LOCK_TICKS = 10;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<UUID, SpectateState> activeSpectators = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> freecamWarnings = new ConcurrentHashMap<>();
    private final Map<UUID, Vec3> lastAllowedPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> freecamExceedCounts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> freecamExceedWindows = new ConcurrentHashMap<>();
    private final Map<UUID, Long> cameraWarnings = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> cameraLockTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Identifier>> advancementSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, SpectateState> pendingReconnectRestores = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingReconnectTargets = new ConcurrentHashMap<>();

    public boolean canSpectate(ServerPlayer admin, ServerPlayer target) {
        if (admin == null || target == null) {
            SpectateMod.LOGGER.warn("canSpectate called with null player(s): admin={}, target={}",
                    admin == null, target == null);
            return false;
        }

        if (JailModCompat.isPlayerJailed(admin)) {
            sendPlayerMessage(admin, Component.literal("§cYou cannot spectate while jailed."), false);
            return false;
        }

        UUID adminUuid = admin.getUUID();
        UUID targetUuid = target.getUUID();

        if (isSpectating(adminUuid)) {
            sendPlayerMessage(admin,
                    Component.literal("§cYou are already spectating someone! Use /spectate stop first."), false);
            return false;
        }

        if (adminUuid.equals(targetUuid)) {
            sendPlayerMessage(admin, Component.literal("§cYou cannot spectate yourself!"), false);
            return false;
        }

        if (isOnCooldown(adminUuid)) {
            long remainingSeconds = getCooldownRemaining(adminUuid);
            sendPlayerMessage(admin,
                    Component.literal("§cYou must wait " + remainingSeconds
                            + " seconds before spectating again."), false);
            return false;
        }

        if (SpectateMod.getConfigManager().getConfig().isPreventCombatSpectate() && isInDanger(admin)) {
            sendPlayerMessage(admin,
                    Component.literal("§cYou cannot spectate while in combat or near hostile mobs!"), false);
            return false;
        }

        return true;
    }

    public void startSpectating(ServerPlayer admin, ServerPlayer target) {
        if (admin == null || target == null) {
            SpectateMod.LOGGER.warn("startSpectating called with null player(s): admin={}, target={}",
                    admin == null, target == null);
            return;
        }

        SpectateState state = new SpectateState(admin, target);
        UUID adminUuid = admin.getUUID();
        advancementSnapshots.put(adminUuid, snapshotCompletedAdvancements(admin));
        activeSpectators.put(adminUuid, state);
        lastAllowedPositions.put(adminUuid, new Vec3(admin.getX(), admin.getY(), admin.getZ()));
        freecamExceedCounts.remove(adminUuid);
        freecamExceedWindows.remove(adminUuid);
        cameraLockTicks.put(adminUuid, INITIAL_CAMERA_LOCK_TICKS);

        admin.setGameMode(GameType.SPECTATOR);

        if (!admin.level().dimension().equals(target.level().dimension())) {
            ServerLevel targetWorld = (ServerLevel) target.level();
            if (!teleportPlayer(admin, targetWorld, target.getX(), target.getY(), target.getZ(),
                    target.getYRot(), target.getXRot(), false)) {
                sendPlayerMessage(admin, INTERNAL_ERROR_MESSAGE, false);
                SpectateMod.LOGGER.warn("Failed to move {} into target dimension while starting spectate",
                        admin.getName().getString());
            }
        }

        admin.setCamera(target);
        rollbackSpectatorAdvancements(admin);

        if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
            saveSpectateData();
        }

        sendPlayerMessage(admin,
                Component.literal("§aYou are now spectating §e" + target.getName().getString() + "§a."), false);
        sendPlayerMessage(admin, Component.literal("§7Use §e/spectate stop §7to stop spectating."), false);

        String adminName = admin.getName().getString();
        String targetName = target.getName().getString();
        SpectateMod.LOGGER.info("{} started spectating {}", adminName, targetName);
    }

    public void stopSpectating(ServerPlayer admin) {
        stopSpectating(admin, null);
    }

    private void stopSpectating(ServerPlayer admin, String reason) {
        if (admin == null) {
            SpectateMod.LOGGER.warn("stopSpectating called with null admin");
            return;
        }

        UUID adminUuid = admin.getUUID();
        rollbackSpectatorAdvancements(admin);
        advancementSnapshots.remove(adminUuid);
        cameraLockTicks.remove(adminUuid);
        SpectateState state = activeSpectators.remove(adminUuid);
        lastAllowedPositions.remove(adminUuid);
        freecamExceedCounts.remove(adminUuid);
        freecamExceedWindows.remove(adminUuid);
        freecamWarnings.remove(adminUuid);
        cameraWarnings.remove(adminUuid);

        if (state == null) {
            sendPlayerMessage(admin, Component.literal("§cYou are not currently spectating anyone!"), false);
            return;
        }

        admin.setCamera(admin);

        MinecraftServer server = admin.level().getServer();
        if (server != null) {
            ServerLevel originalWorld = server.getLevel(state.getDimension());

            if (originalWorld != null) {
                Vec3 pos = state.getPosition();
                if (!teleportPlayer(admin, originalWorld, pos.x, pos.y, pos.z,
                        state.getYaw(), state.getPitch(), false)) {
                    sendPlayerMessage(admin, INTERNAL_ERROR_MESSAGE, false);
                    SpectateMod.LOGGER.warn("Failed to return {} to original location after spectate",
                            admin.getName().getString());
                }
            } else {
                SpectateMod.LOGGER.warn("Original world {} not found for player {} during stopSpectating",
                        state.getDimensionId(), admin.getName().getString());
            }
        }

        admin.setGameMode(state.getGameMode());

        applySpectateCooldown(adminUuid);

        if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
            saveSpectateData();
        }

        long duration = state.getDurationSeconds();
        if (reason != null && !reason.isBlank()) {
            sendPlayerMessage(admin, Component.literal(reason), false);
        } else {
            sendPlayerMessage(admin,
                    Component.literal("§aYou are no longer spectating. §7(Duration: " + duration + "s)"), false);
        }

        String adminName = admin.getName().getString();
        SpectateMod.LOGGER.info("{} stopped spectating after {}s", adminName, duration);
    }

    public boolean isSpectating(UUID adminUuid) {
        return activeSpectators.containsKey(adminUuid);
    }

    public SpectateState getSpectateState(UUID adminUuid) {
        return activeSpectators.get(adminUuid);
    }

    public void handlePlayerDisconnect(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }

        UUID playerId = player.getUUID();
        SpectateState disconnectedState = activeSpectators.remove(playerId);
        if (disconnectedState != null) {
            rollbackSpectatorAdvancements(player);
            advancementSnapshots.remove(playerId);
            cameraLockTicks.remove(playerId);
            lastAllowedPositions.remove(playerId);
            freecamExceedCounts.remove(playerId);
            freecamExceedWindows.remove(playerId);
            freecamWarnings.remove(playerId);
            cameraWarnings.remove(playerId);

            applySpectateCooldown(playerId);
            pendingReconnectRestores.put(playerId, disconnectedState);
            pendingReconnectTargets.put(playerId, resolveTargetName(server, disconnectedState.getTargetUuid()));

            if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
                saveSpectateData();
            }

            String adminName = player.getName().getString();
            String targetName = pendingReconnectTargets.getOrDefault(playerId, "unknown player");
            SpectateMod.LOGGER.info("{} disconnected while spectating {}; session was ended automatically",
                    adminName, targetName);
            return;
        }

        List<UUID> toStop = new ArrayList<>();
        for (Map.Entry<UUID, SpectateState> entry : activeSpectators.entrySet()) {
            if (entry.getValue().getTargetUuid().equals(playerId)) {
                toStop.add(entry.getKey());
            }
        }

        for (UUID adminId : toStop) {
            ServerPlayer admin = server.getPlayerList().getPlayer(adminId);
            if (admin != null) {
                stopSpectating(admin, "§cSpectating ended: player left the server.");
            } else {
                activeSpectators.remove(adminId);
                advancementSnapshots.remove(adminId);
                cameraLockTicks.remove(adminId);
                lastAllowedPositions.remove(adminId);
                freecamExceedCounts.remove(adminId);
                freecamExceedWindows.remove(adminId);
                freecamWarnings.remove(adminId);
                cameraWarnings.remove(adminId);
            }
        }
    }

    public void handlePlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (player == null || server == null) {
            return;
        }

        UUID playerId = player.getUUID();
        SpectateState restoreState = pendingReconnectRestores.remove(playerId);
        String targetName = pendingReconnectTargets.remove(playerId);

        if (restoreState == null) {
            // Safety fallback for stale persisted states loaded from disk.
            restoreState = activeSpectators.remove(playerId);
            if (restoreState != null) {
                rollbackSpectatorAdvancements(player);
                advancementSnapshots.remove(playerId);
                cameraLockTicks.remove(playerId);
                lastAllowedPositions.remove(playerId);
                freecamExceedCounts.remove(playerId);
                freecamExceedWindows.remove(playerId);
                freecamWarnings.remove(playerId);
                cameraWarnings.remove(playerId);
                applySpectateCooldown(playerId);
                targetName = resolveTargetName(server, restoreState.getTargetUuid());
                if (SpectateMod.getConfigManager().getConfig().isSaveSpectatePositions()) {
                    saveSpectateData();
                }
            }
        }

        if (restoreState == null) {
            return;
        }

        player.setCamera(player);

        ServerLevel originalWorld = server.getLevel(restoreState.getDimension());
        if (originalWorld == null) {
            originalWorld = server.getLevel(Level.OVERWORLD);
        }

        if (originalWorld != null) {
            Vec3 pos = restoreState.getPosition();
            if (!teleportPlayer(player, originalWorld, pos.x, pos.y, pos.z,
                    restoreState.getYaw(), restoreState.getPitch(), false)) {
                SpectateMod.LOGGER.warn("Failed to restore {} to saved position after reconnect",
                        player.getName().getString());
            }
        }

        GameType restoreMode = restoreState.getGameMode() == null ? GameType.SURVIVAL : restoreState.getGameMode();
        player.setGameMode(restoreMode);

        String targetLabel = (targetName == null || targetName.isBlank())
                ? resolveTargetName(server, restoreState.getTargetUuid())
                : targetName;

        sendPlayerMessage(player, Component.literal("§eYour spectate session ended when you disconnected."), false);
        sendPlayerMessage(player,
                Component.literal("§7You were spectating §e" + targetLabel
                        + "§7. Use §e/spectate <player> §7if you want to spectate again."),
                false);
    }

    public void enforceFreecamLimits(MinecraftServer server) {
        if (server == null) {
            return;
        }

        double limit = SpectateMod.getConfigManager().getConfig().getFreecamDistanceLimit();
        boolean enforceDistanceLimit = limit > 0;
        double limitSq = limit * limit;
        for (Map.Entry<UUID, SpectateState> entry : activeSpectators.entrySet()) {
            ServerPlayer admin = server.getPlayerList().getPlayer(entry.getKey());
            if (admin == null) {
                continue;
            }

            SpectateState state = entry.getValue();
            ServerPlayer target = server.getPlayerList().getPlayer(state.getTargetUuid());
            if (target == null) {
                stopSpectating(admin, "§cSpectating ended: player left the server.");
                continue;
            }

            UUID adminId = admin.getUUID();
            if (admin.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                cameraLockTicks.remove(adminId);
                continue;
            }

            rollbackSpectatorAdvancements(admin);

            Integer lockTicksRemaining = cameraLockTicks.get(adminId);
            if (lockTicksRemaining != null && lockTicksRemaining > 0) {
                ServerLevel targetWorld = (ServerLevel) target.level();
                if (!admin.level().dimension().equals(targetWorld.dimension())) {
                    if (!teleportPlayer(admin, targetWorld, target.getX(), target.getY(), target.getZ(),
                            admin.getYRot(), admin.getXRot(), false)) {
                        sendPlayerMessage(admin, INTERNAL_ERROR_MESSAGE, false);
                        SpectateMod.LOGGER.warn("Failed to keep {} aligned with target dimension during camera lock",
                                admin.getName().getString());
                    }
                }

                admin.setCamera(target);
                int nextTicks = lockTicksRemaining - 1;
                if (nextTicks > 0) {
                    cameraLockTicks.put(adminId, nextTicks);
                } else {
                    cameraLockTicks.remove(adminId);
                }
                lastAllowedPositions.put(adminId, new Vec3(admin.getX(), admin.getY(), admin.getZ()));
                continue;
            }

            if (!enforceDistanceLimit) {
                continue;
            }

            if (admin.getCamera() != target && admin.getCamera() != admin) {
                admin.setCamera(target);
                warnCameraLimit(admin);
            }

            ServerLevel targetWorld = (ServerLevel) target.level();
            if (!admin.level().dimension().equals(targetWorld.dimension())) {
                if (teleportPlayer(admin, targetWorld, target.getX(), target.getY(), target.getZ(),
                        admin.getYRot(), admin.getXRot(), false)) {
                    lastAllowedPositions.put(admin.getUUID(), new Vec3(admin.getX(), admin.getY(), admin.getZ()));
                }
                warnFreecamLimit(admin, limit);
                continue;
            }

            Vec3 adminPos = new Vec3(admin.getX(), admin.getY(), admin.getZ());
            double dx = adminPos.x - target.getX();
            double dy = adminPos.y - target.getY();
            double dz = adminPos.z - target.getZ();
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq <= limitSq) {
                lastAllowedPositions.put(admin.getUUID(), adminPos);
                freecamExceedCounts.remove(admin.getUUID());
                freecamExceedWindows.remove(admin.getUUID());
                continue;
            }

            if (distSq > limitSq * 9) {
                if (teleportPlayer(admin, targetWorld, target.getX(), target.getY(), target.getZ(),
                        admin.getYRot(), admin.getXRot(), false)) {
                    admin.setCamera(target);
                    lastAllowedPositions.put(admin.getUUID(), new Vec3(admin.getX(), admin.getY(), admin.getZ()));
                    freecamExceedCounts.remove(admin.getUUID());
                    freecamExceedWindows.remove(admin.getUUID());
                }
                warnFreecamReset(admin);
                continue;
            }

            if (registerExceedAttempt(admin)) {
                if (teleportPlayer(admin, targetWorld, target.getX(), target.getY(), target.getZ(),
                        admin.getYRot(), admin.getXRot(), false)) {
                    admin.setCamera(target);
                    lastAllowedPositions.put(admin.getUUID(), new Vec3(admin.getX(), admin.getY(), admin.getZ()));
                    freecamExceedCounts.remove(admin.getUUID());
                    freecamExceedWindows.remove(admin.getUUID());
                }
                warnFreecamReset(admin);
                continue;
            }

            Vec3 lastAllowed = lastAllowedPositions.get(admin.getUUID());
            if (lastAllowed == null) {
                lastAllowed = adminPos;
            }

            double lax = lastAllowed.x - target.getX();
            double lay = lastAllowed.y - target.getY();
            double laz = lastAllowed.z - target.getZ();
            double lastDistSq = lax * lax + lay * lay + laz * laz;
            Vec3 safePos = lastAllowed;
            if (lastDistSq > limitSq) {
                double lastDist = Math.sqrt(lastDistSq);
                if (lastDist > 1e-6) {
                    double scale = limit / lastDist;
                    safePos = new Vec3(
                            target.getX() + lax * scale,
                            target.getY() + lay * scale,
                            target.getZ() + laz * scale);
                } else {
                    safePos = new Vec3(target.getX(), target.getY(), target.getZ());
                }
            }

            if (teleportPlayer(admin, targetWorld, safePos.x, safePos.y, safePos.z,
                    admin.getYRot(), admin.getXRot(), false)) {
                lastAllowedPositions.put(admin.getUUID(), safePos);
            }
            warnFreecamLimit(admin, limit);
        }
    }

    private Set<Identifier> snapshotCompletedAdvancements(ServerPlayer player) {
        Set<Identifier> completed = new HashSet<>();
        if (player == null) {
            return completed;
        }

        try {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return completed;
            }

            PlayerAdvancements playerAdvancements = player.getAdvancements();
            ServerAdvancementManager advancementManager = server.getAdvancements();
            if (playerAdvancements == null || advancementManager == null) {
                return completed;
            }

            for (AdvancementHolder holder : advancementManager.getAllAdvancements()) {
                if (holder == null) {
                    continue;
                }
                AdvancementProgress progress = playerAdvancements.getOrStartProgress(holder);
                if (progress != null && progress.isDone()) {
                    completed.add(holder.id());
                }
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.warn("Failed to snapshot advancement state for {}", player.getName().getString(), e);
        }

        return completed;
    }

    private void rollbackSpectatorAdvancements(ServerPlayer player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUUID();
        Set<Identifier> snapshot = advancementSnapshots.get(playerId);
        if (snapshot == null) {
            advancementSnapshots.put(playerId, snapshotCompletedAdvancements(player));
            return;
        }

        try {
            MinecraftServer server = player.level().getServer();
            if (server == null) {
                return;
            }

            PlayerAdvancements playerAdvancements = player.getAdvancements();
            ServerAdvancementManager advancementManager = server.getAdvancements();
            if (playerAdvancements == null || advancementManager == null) {
                return;
            }

            int revokedCriteria = 0;
            int revokedAdvancements = 0;
            for (AdvancementHolder holder : advancementManager.getAllAdvancements()) {
                if (holder == null || snapshot.contains(holder.id())) {
                    continue;
                }

                AdvancementProgress progress = playerAdvancements.getOrStartProgress(holder);
                if (progress == null || !progress.isDone()) {
                    continue;
                }

                Set<String> completedCriteria = new HashSet<>();
                for (String criterion : progress.getCompletedCriteria()) {
                    completedCriteria.add(criterion);
                }
                if (completedCriteria.isEmpty()) {
                    continue;
                }

                boolean revokedAny = false;
                for (String criterion : completedCriteria) {
                    try {
                        if (playerAdvancements.revoke(holder, criterion)) {
                            revokedAny = true;
                            revokedCriteria++;
                        }
                    } catch (Exception criterionError) {
                        SpectateMod.LOGGER.debug("Failed to revoke criterion '{}' for {}",
                                criterion, player.getName().getString(), criterionError);
                    }
                }

                if (revokedAny) {
                    revokedAdvancements++;
                }
            }

            if (revokedAdvancements > 0) {
                SpectateMod.LOGGER.debug("Revoked {} advancement(s) ({} criterion/criteria) gained while spectating for {}",
                        revokedAdvancements, revokedCriteria, player.getName().getString());
            }
        } catch (Exception e) {
            SpectateMod.LOGGER.warn("Failed to rollback spectator advancements for {}",
                    player.getName().getString(), e);
        }
    }

    private boolean isOnCooldown(UUID adminUuid) {
        Long cooldownExpiry = cooldowns.get(adminUuid);
        if (cooldownExpiry == null) {
            return false;
        }

        if (System.currentTimeMillis() >= cooldownExpiry) {
            cooldowns.remove(adminUuid);
            return false;
        }
        return true;
    }

    private void applySpectateCooldown(UUID adminUuid) {
        if (adminUuid == null) {
            return;
        }

        int cooldownSeconds = SpectateMod.getConfigManager().getConfig().getSpectateCooldown();
        if (cooldownSeconds > 0) {
            cooldowns.put(adminUuid, System.currentTimeMillis() + (cooldownSeconds * 1000L));
        } else {
            cooldowns.remove(adminUuid);
        }
    }

    private long getCooldownRemaining(UUID adminUuid) {
        Long cooldownExpiry = cooldowns.get(adminUuid);
        if (cooldownExpiry == null) {
            return 0;
        }
        return Math.max(0, (cooldownExpiry - System.currentTimeMillis()) / 1000);
    }

    private String resolveTargetName(MinecraftServer server, UUID targetUuid) {
        if (targetUuid == null || server == null) {
            return "unknown player";
        }

        ServerPlayer targetPlayer = server.getPlayerList().getPlayer(targetUuid);
        if (targetPlayer != null) {
            return targetPlayer.getName().getString();
        }

        return targetUuid.toString();
    }

    private boolean isInDanger(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        // Keep the same 100-tick (~5s) recent-combat window used before migration.
        // TODO(AS-207): Re-validate this signal if combat timestamps are remapped again.
        if (player.getLastHurtByMobTimestamp() > player.tickCount - 100) {
            return true;
        }

        double radius = SpectateMod.getConfigManager().getConfig().getCombatCheckRadius();
        AABB searchBox = player.getBoundingBox().inflate(radius);
        List<Entity> nearbyEntities = player.level().getEntities(player, searchBox);

        for (Entity entity : nearbyEntities) {
            if (entity instanceof Monster) {
                return true;
            }
        }
        return false;
    }

    private void warnFreecamLimit(ServerPlayer admin, double limit) {
        long now = System.currentTimeMillis();
        long nextAllowed = freecamWarnings.getOrDefault(admin.getUUID(), 0L);
        if (now < nextAllowed) {
            return;
        }

        freecamWarnings.put(admin.getUUID(), now + 1500);
        int rounded = (int) Math.round(limit);
        sendPlayerMessage(admin,
                Component.literal("§cYou cannot go further than " + rounded
                        + " blocks from the player you're spectating."), true);
    }

    private void warnFreecamReset(ServerPlayer admin) {
        long now = System.currentTimeMillis();
        long nextAllowed = freecamWarnings.getOrDefault(admin.getUUID(), 0L);
        if (now < nextAllowed) {
            return;
        }

        freecamWarnings.put(admin.getUUID(), now + 1500);
        sendPlayerMessage(admin, Component.literal("§cFreecam limit reached. Returning to player POV."), true);
    }

    private boolean registerExceedAttempt(ServerPlayer admin) {
        long now = System.currentTimeMillis();
        long windowStart = freecamExceedWindows.getOrDefault(admin.getUUID(), 0L);
        if (now - windowStart > 2000) {
            freecamExceedWindows.put(admin.getUUID(), now);
            freecamExceedCounts.put(admin.getUUID(), 1);
            return false;
        }

        int count = freecamExceedCounts.getOrDefault(admin.getUUID(), 0) + 1;
        freecamExceedCounts.put(admin.getUUID(), count);
        return count >= 5;
    }

    private void warnCameraLimit(ServerPlayer admin) {
        long now = System.currentTimeMillis();
        long nextAllowed = cameraWarnings.getOrDefault(admin.getUUID(), 0L);
        if (now < nextAllowed) {
            return;
        }

        cameraWarnings.put(admin.getUUID(), now + 1500);
        sendPlayerMessage(admin, Component.literal("§cYou can only spectate the target player's POV."), true);
    }

    public void saveSpectateData() {
        try {
            Path dataPath = Paths.get(DATA_DIR);
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            List<SerializableSpectateState> serializableStates = new ArrayList<>();
            for (SpectateState state : activeSpectators.values()) {
                GameType gameType = state.getGameMode() == null ? GameType.SURVIVAL : state.getGameMode();
                SerializableSpectateState serializable = new SerializableSpectateState(
                        state.getAdminUuid().toString(),
                        state.getTargetUuid().toString(),
                        state.getPosition().x,
                        state.getPosition().y,
                        state.getPosition().z,
                        state.getYaw(),
                        state.getPitch(),
                        gameType.name(),
                        state.getDimensionId(),
                        state.getStartTime());
                serializableStates.add(serializable);
            }

            File dataFile = new File(DATA_DIR, DATA_FILE);
            try (FileWriter writer = new FileWriter(dataFile)) {
                gson.toJson(serializableStates, writer);
            }
        } catch (IOException e) {
            SpectateMod.LOGGER.error("Failed to save spectate data", e);
        }
    }

    public void loadSpectateData() {
        loadSpectateData(null);
    }

    public void loadSpectateData(MinecraftServer server) {
        File dataFile = new File(DATA_DIR, DATA_FILE);
        if (!dataFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(dataFile)) {
            Type listType = new TypeToken<List<SerializableSpectateState>>() { }.getType();
            List<SerializableSpectateState> serializableStates = gson.fromJson(reader, listType);

            if (serializableStates == null || serializableStates.isEmpty()) {
                return;
            }

            for (SerializableSpectateState serializable : serializableStates) {
                try {
                    UUID adminUuid = UUID.fromString(serializable.getAdminUuid());
                    UUID targetUuid = UUID.fromString(serializable.getTargetUuid());
                    Vec3 position = new Vec3(serializable.getPositionX(),
                            serializable.getPositionY(),
                            serializable.getPositionZ());

                    GameType gameMode = parseGameType(serializable.getGameMode());
                    ResourceKey<Level> dimension = parseDimensionKey(serializable.getDimension(), server);

                    SpectateState state = new SpectateState(adminUuid, targetUuid, position,
                            serializable.getYaw(), serializable.getPitch(),
                            gameMode, dimension, serializable.getStartTime());
                    activeSpectators.put(adminUuid, state);
                } catch (Exception e) {
                    SpectateMod.LOGGER.warn("Failed to restore a spectate state entry; skipping", e);
                }
            }

            SpectateMod.LOGGER.info("Loaded {} spectate session(s) from disk", activeSpectators.size());
        } catch (Exception e) {
            SpectateMod.LOGGER.error("Failed to load spectate data", e);
        }
    }

    private GameType parseGameType(String serializedGameMode) {
        if (serializedGameMode == null || serializedGameMode.isBlank()) {
            SpectateMod.LOGGER.warn("Missing game mode in persisted spectate state, defaulting to SURVIVAL");
            return GameType.SURVIVAL;
        }

        String normalized = serializedGameMode.trim();
        try {
            return GameType.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
        }

        try {
            int gameTypeId = Integer.parseInt(normalized);
            GameType byId = GameType.byId(gameTypeId);
            if (byId != null) {
                SpectateMod.LOGGER.warn("Loaded legacy numeric game mode id {} from persisted state", gameTypeId);
                return byId;
            }
        } catch (NumberFormatException ignored) {
        }

        SpectateMod.LOGGER.warn("Unknown game mode '{}' in persisted spectate state; defaulting to SURVIVAL",
                serializedGameMode);
        return GameType.SURVIVAL;
    }

    private ResourceKey<Level> parseDimensionKey(String serializedDimension, MinecraftServer server) {
        if (serializedDimension != null) {
            Identifier dimensionId = Identifier.tryParse(serializedDimension.trim());
            if (dimensionId != null) {
                return ResourceKey.create(Registries.DIMENSION, dimensionId);
            }

            // TODO(AS-208): Keep compatibility shim for older malformed saved dimension formats.
            String sanitized = serializedDimension.trim();
            if (sanitized.contains("[")) {
                sanitized = sanitized.substring(sanitized.indexOf('[') + 1).replace("]", "");
            }
            if (sanitized.contains(" / ")) {
                sanitized = sanitized.substring(sanitized.indexOf(" / ") + 3);
            }
            Identifier legacyId = Identifier.tryParse(sanitized);
            if (legacyId != null) {
                SpectateMod.LOGGER.warn("Recovered legacy dimension format '{}' as '{}'", serializedDimension,
                        legacyId);
                return ResourceKey.create(Registries.DIMENSION, legacyId);
            }

            SpectateMod.LOGGER.warn("Invalid dimension id '{}' in persisted spectate state", serializedDimension);
        }

        if (server != null) {
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                return overworld.dimension();
            }
        }

        return Level.OVERWORLD;
    }

    private boolean teleportPlayer(ServerPlayer player, ServerLevel world,
            double x, double y, double z, float yaw, float pitch, boolean moveCamera) {
        if (player == null || world == null) {
            return false;
        }

        try {
            return player.teleportTo(world, x, y, z, EnumSet.noneOf(Relative.class), yaw, pitch, moveCamera);
        } catch (Throwable primaryFailure) {
            SpectateMod.LOGGER.warn("Primary teleport API failed for {}", player.getName().getString(),
                    primaryFailure);
        }

        // TODO(AS-209): Verify teleportTo signature stability once official mappings settle.
        try {
            for (Method method : player.getClass().getMethods()) {
                if (!"teleportTo".equals(method.getName())) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();
                if (params.length != 8
                        || !params[0].isAssignableFrom(world.getClass())
                        || params[1] != double.class
                        || params[2] != double.class
                        || params[3] != double.class
                        || !Set.class.isAssignableFrom(params[4])
                        || params[5] != float.class
                        || params[6] != float.class
                        || params[7] != boolean.class) {
                    continue;
                }

                Object result = method.invoke(player, world, x, y, z,
                        EnumSet.noneOf(Relative.class), yaw, pitch, moveCamera);
                if (result instanceof Boolean value) {
                    return value;
                }
                return true;
            }
        } catch (Exception reflectiveFailure) {
            SpectateMod.LOGGER.warn("Reflective teleport fallback failed for {}",
                    player.getName().getString(), reflectiveFailure);
        }

        if (player.level() == world) {
            try {
                player.teleportTo(x, y, z);
                return true;
            } catch (Exception localTeleportFailure) {
                SpectateMod.LOGGER.warn("Local teleport fallback failed for {}",
                        player.getName().getString(), localTeleportFailure);
            }
        }

        return false;
    }

    private void sendPlayerMessage(ServerPlayer player, Component message, boolean actionBar) {
        if (player == null || message == null) {
            return;
        }

        try {
            player.sendSystemMessage(message, actionBar);
            return;
        } catch (Throwable ignored) {
        }

        // TODO(AS-210): Re-check message API variants when mappings are updated.
        try {
            Method method = player.getClass().getMethod("sendSystemMessage", Component.class);
            method.invoke(player, message);
            return;
        } catch (Exception ignored) {
        }

        if (actionBar) {
            try {
                Method method = player.getClass().getMethod("sendOverlayMessage", Component.class);
                method.invoke(player, message);
                return;
            } catch (Exception ignored) {
            }
        }

        SpectateMod.LOGGER.warn("Unable to deliver message to {}: {}",
                player.getName().getString(), message.getString());
    }

    private static final class JailModCompat {
        private static final String JAIL_MOD_CLASS = "com.example.jailmod.JailMod";
        private static final String LEGACY_PLAYER_CLASS = "net.minecraft.server.network.ServerPlayerEntity";

        private static boolean checked;
        private static Method isPlayerInJail;

        private static boolean isPlayerJailed(ServerPlayer player) {
            if (player == null || !FabricLoader.getInstance().isModLoaded("jailmod")) {
                return false;
            }

            if (!checked) {
                checked = true;
                isPlayerInJail = resolveJailMethod();
            }

            if (isPlayerInJail == null) {
                return false;
            }

            try {
                Class<?> parameterType = isPlayerInJail.getParameterTypes()[0];
                if (!parameterType.isInstance(player)) {
                    SpectateMod.LOGGER.debug("JailMod hook expects {}, but player is {}",
                            parameterType.getName(), player.getClass().getName());
                    return false;
                }
                return (boolean) isPlayerInJail.invoke(null, player);
            } catch (Exception e) {
                SpectateMod.LOGGER.warn("JailMod compatibility check failed", e);
                return false;
            }
        }

        private static Method resolveJailMethod() {
            try {
                Class<?> jailModClass = Class.forName(JAIL_MOD_CLASS);

                try {
                    Method method = jailModClass.getMethod("isPlayerInJail", ServerPlayer.class);
                    SpectateMod.LOGGER.info("JailMod compatibility enabled using ServerPlayer signature");
                    return method;
                } catch (NoSuchMethodException ignored) {
                }

                try {
                    Class<?> legacyPlayerClass = Class.forName(LEGACY_PLAYER_CLASS);
                    Method method = jailModClass.getMethod("isPlayerInJail", legacyPlayerClass);
                    SpectateMod.LOGGER.info("JailMod compatibility enabled using legacy ServerPlayerEntity signature");
                    return method;
                } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                }

                for (Method method : jailModClass.getMethods()) {
                    if (!"isPlayerInJail".equals(method.getName())
                            || method.getParameterCount() != 1
                            || method.getReturnType() != boolean.class) {
                        continue;
                    }

                    if (method.getParameterTypes()[0].isAssignableFrom(ServerPlayer.class)) {
                        SpectateMod.LOGGER.info("JailMod compatibility enabled using dynamic signature {}",
                                method.getParameterTypes()[0].getName());
                        return method;
                    }
                }

                SpectateMod.LOGGER.warn("JailMod detected but no compatible isPlayerInJail signature found");
            } catch (Exception e) {
                SpectateMod.LOGGER.warn("JailMod detected but compatibility hook failed", e);
            }
            return null;
        }
    }
}
