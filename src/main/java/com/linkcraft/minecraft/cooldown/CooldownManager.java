package com.linkcraft.minecraft.cooldown;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
    private final Map<UUID, Long> linkCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> unlinkCooldowns = new ConcurrentHashMap<>();

    private volatile boolean enabled;
    private volatile boolean applyOnAttempt;
    private volatile long linkCooldownMillis;
    private volatile long unlinkCooldownMillis;

    public void reload(FileConfiguration config) {
        enabled = config.getBoolean("cooldowns.enabled", true);
        applyOnAttempt = config.getBoolean("cooldowns.apply-on-attempt", true);
        linkCooldownMillis = secondsToMillis(
                config.getLong("cooldowns.link-seconds", 30));
        unlinkCooldownMillis = secondsToMillis(
                config.getLong("cooldowns.unlink-seconds", 30));
    }

    public long getLinkRemainingSeconds(UUID playerUuid) {
        return getRemainingSeconds(linkCooldowns, playerUuid);
    }

    public long getUnlinkRemainingSeconds(UUID playerUuid) {
        return getRemainingSeconds(unlinkCooldowns, playerUuid);
    }

    public void startLinkCooldown(UUID playerUuid) {
        start(linkCooldowns, playerUuid, linkCooldownMillis);
    }

    public void startUnlinkCooldown(UUID playerUuid) {
        start(unlinkCooldowns, playerUuid, unlinkCooldownMillis);
    }

    public boolean isApplyOnAttempt() {
        return enabled && applyOnAttempt;
    }

    private long getRemainingSeconds(Map<UUID, Long> cooldowns, UUID playerUuid) {
        if (!enabled) {
            return 0;
        }

        Long expiresAt = cooldowns.get(playerUuid);
        if (expiresAt == null) {
            return 0;
        }

        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            cooldowns.remove(playerUuid, expiresAt);
            return 0;
        }
        return (remainingMillis + 999) / 1000;
    }

    private void start(Map<UUID, Long> cooldowns, UUID playerUuid, long durationMillis) {
        if (!enabled || durationMillis <= 0) {
            return;
        }
        cooldowns.put(playerUuid, System.currentTimeMillis() + durationMillis);
    }

    private long secondsToMillis(long seconds) {
        return Math.max(0, seconds) * 1000L;
    }
}
