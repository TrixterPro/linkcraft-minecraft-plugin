package com.linkcraft.minecraft.commands;

import com.linkcraft.minecraft.LinkCraftPlugin;
import com.linkcraft.minecraft.cooldown.CooldownManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class UnlinkCommand implements CommandExecutor {
    private final LinkCraftPlugin plugin;
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    public UnlinkCommand(LinkCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sendMessage(sender, "messages.only-players",
                    "&cThis command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;
        if (args.length != 0) {
            sendMessage(player, "messages.unlink-usage", "&eUsage: /unlink");
            return true;
        }

        UUID playerUuid = player.getUniqueId();
        CooldownManager cooldowns = plugin.getCooldownManager();
        long remaining = cooldowns.getUnlinkRemainingSeconds(playerUuid);
        if (remaining > 0) {
            sendMessage(player, "messages.unlink-cooldown",
                    "&cPlease wait %seconds% seconds before using /unlink again.",
                    remaining);
            return true;
        }

        if (!pendingPlayers.add(playerUuid)) {
            sendMessage(player, "messages.unlink-processing",
                    "&eAn unlink request is already being processed.");
            return true;
        }

        if (cooldowns.isApplyOnAttempt()) {
            cooldowns.startUnlinkCooldown(playerUuid);
        }
        sendMessage(player, "messages.unlink-processing",
                "&eUnlinking your account...");
        plugin.executeService(() -> processUnlink(playerUuid, player.getName()));
        return true;
    }

    private void processUnlink(UUID playerUuid, String playerName) {
        UnlinkResult result;
        try {
            if (plugin.getMySQLManager().deleteLink(playerUuid)) {
                plugin.getRedisManager().deleteIdentityCache(
                        playerUuid.toString(), playerName);
                if (!plugin.getCooldownManager().isApplyOnAttempt()) {
                    plugin.getCooldownManager().startUnlinkCooldown(playerUuid);
                }
                result = UnlinkResult.SUCCESS;
            } else {
                result = UnlinkResult.NOT_LINKED;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Account unlinking failed: " + e.getMessage());
            result = UnlinkResult.SERVICE_ERROR;
        }

        if (!plugin.isEnabled()) {
            pendingPlayers.remove(playerUuid);
            return;
        }

        UnlinkResult callbackResult = result;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingPlayers.remove(playerUuid);
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null) {
                return;
            }

            switch (callbackResult) {
                case SUCCESS:
                    sendMessage(player, "messages.unlink-success",
                            "&aSuccessfully unlinked your Discord account!");
                    plugin.getLogger().info("Player " + playerName
                            + " unlinked their Discord account.");
                    break;
                case NOT_LINKED:
                    sendMessage(player, "messages.not-linked",
                            "&eYour account is not linked.");
                    break;
                case SERVICE_ERROR:
                    sendMessage(player, "messages.service-unavailable",
                            "&cThe account service is temporarily unavailable. Please try again later.");
                    break;
                default:
                    throw new IllegalStateException(
                            "Unhandled unlink result: " + callbackResult);
            }
        });
    }

    private void sendMessage(CommandSender sender, String path, String fallback) {
        sendMessage(sender, path, fallback, -1);
    }

    private void sendMessage(CommandSender sender, String path, String fallback,
                             long seconds) {
        String message = plugin.getPluginConfig().getString(path, fallback);
        if (message == null) {
            message = fallback;
        }
        if (seconds >= 0) {
            message = message.replace("%seconds%", Long.toString(seconds));
        }
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                message));
    }

    private enum UnlinkResult {
        SUCCESS,
        NOT_LINKED,
        SERVICE_ERROR
    }
}
