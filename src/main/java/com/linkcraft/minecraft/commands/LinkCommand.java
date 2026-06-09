package com.linkcraft.minecraft.commands;

import com.linkcraft.minecraft.LinkCraftPlugin;
import com.linkcraft.minecraft.cooldown.CooldownManager;
import com.linkcraft.minecraft.database.MySQLManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LinkCommand implements CommandExecutor {

    private final LinkCraftPlugin plugin;
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();

    public LinkCommand(LinkCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', plugin.getPluginConfig().getString("messages.only-players", "&cThis command can only be used by players!")));
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 1) {
            String code = args[0].toUpperCase().trim();

            if (!code.matches("[A-Z0-9]{6}")) {
                sendMessage(player, "messages.invalid-format",
                        "&cInvalid code format. Code must be 6 alphanumeric characters.");
                return true;
            }

            UUID playerUuid = player.getUniqueId();
            CooldownManager cooldowns = plugin.getCooldownManager();
            long remaining = cooldowns.getLinkRemainingSeconds(playerUuid);
            if (remaining > 0) {
                sendMessage(player, "messages.link-cooldown",
                        "&cPlease wait %seconds% seconds before using /link again.",
                        remaining);
                return true;
            }

            if (!pendingPlayers.add(playerUuid)) {
                sendMessage(player, "messages.processing",
                        "&eA link request is already being processed.");
                return true;
            }

            if (cooldowns.isApplyOnAttempt()) {
                cooldowns.startLinkCooldown(playerUuid);
            }
            sendMessage(player, "messages.processing", "&eChecking your verification code...");
            plugin.executeService(() -> processLink(playerUuid, player.getName(), code));
            return true;
        }

        sendMessage(player, "messages.usage", "&eUsage: /link <code>");
        player.sendMessage(ChatColor.GRAY + "Enter the 6-character code you received from Discord.");
        return true;
    }

    private void processLink(UUID playerUuid, String playerName, String code) {
        LinkResult result;
        String discordId = null;

        try {
            MySQLManager mySQLManager = plugin.getMySQLManager();
            if (mySQLManager.isPlayerLinked(playerUuid)) {
                result = LinkResult.ALREADY_LINKED;
            } else {
                discordId = plugin.getRedisManager().getDiscordId(code);
                if (discordId == null) {
                    result = LinkResult.INVALID_CODE;
                } else {
                    MySQLManager.CreateLinkResult createResult =
                            mySQLManager.createLink(playerUuid, discordId);
                    switch (createResult) {
                        case SUCCESS:
                            plugin.getRedisManager().deleteCode(code);
                            plugin.getRedisManager().cacheIdentity(
                                    playerUuid.toString(), playerName);
                            if (!plugin.getCooldownManager().isApplyOnAttempt()) {
                                plugin.getCooldownManager().startLinkCooldown(playerUuid);
                            }
                            result = LinkResult.SUCCESS;
                            break;
                        case PLAYER_ALREADY_LINKED:
                            result = LinkResult.ALREADY_LINKED;
                            break;
                        case DISCORD_ALREADY_LINKED:
                            result = LinkResult.DISCORD_ALREADY_LINKED;
                            break;
                        default:
                            throw new IllegalStateException(
                                    "Unhandled MySQL result: " + createResult);
                    }
                }
            }
        } catch (SQLException | RuntimeException e) {
            plugin.getLogger().severe("Account linking failed: " + e.getMessage());
            result = LinkResult.SERVICE_ERROR;
        }

        if (!plugin.isEnabled()) {
            pendingPlayers.remove(playerUuid);
            return;
        }

        LinkResult callbackResult = result;
        String callbackDiscordId = discordId;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingPlayers.remove(playerUuid);
            Player player = plugin.getServer().getPlayer(playerUuid);
            if (player == null) {
                return;
            }

            switch (callbackResult) {
                case SUCCESS:
                    sendMessage(player, "messages.success",
                            "&aSuccessfully linked your account to Discord!");
                    plugin.getLogger().info("Player " + playerName
                            + " linked account to Discord ID: " + callbackDiscordId);
                    break;
                case ALREADY_LINKED:
                    sendMessage(player, "messages.already-linked",
                            "&eYour account is already linked. Use /unlink before linking again.");
                    break;
                case DISCORD_ALREADY_LINKED:
                    sendMessage(player, "messages.discord-already-linked",
                            "&eThat Discord account is already linked to a Minecraft account.");
                    break;
                case INVALID_CODE:
                    sendMessage(player, "messages.invalid-code",
                            "&cInvalid or expired code. Please request a new one from Discord.");
                    break;
                case SERVICE_ERROR:
                    sendMessage(player, "messages.service-unavailable",
                            "&cAccount linking is temporarily unavailable. Please try again later.");
                    break;
                default:
                    throw new IllegalStateException(
                            "Unhandled link result: " + callbackResult);
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

    private enum LinkResult {
        SUCCESS,
        ALREADY_LINKED,
        DISCORD_ALREADY_LINKED,
        INVALID_CODE,
        SERVICE_ERROR
    }
}
