package com.linkcraft.minecraft.commands;

import com.linkcraft.minecraft.LinkCraftPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class LinkCraftCommand implements CommandExecutor {
    private final LinkCraftPlugin plugin;

    public LinkCraftCommand(LinkCraftPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || !"reload".equalsIgnoreCase(args[0])) {
            sendMessage(sender, "messages.admin-usage", "&eUsage: /linkcraft reload");
            return true;
        }

        try {
            plugin.reloadConfig();
            plugin.getCooldownManager().reload(plugin.getPluginConfig());
        } catch (RuntimeException e) {
            plugin.getLogger().severe("Could not reload config.yml: " + e.getMessage());
            sendMessage(sender, "messages.reload-config-error",
                    "&cCould not read config.yml. Check the server log for details.");
            return true;
        }
        sendMessage(sender, "messages.reload-started",
                "&eReloading LinkCraft configuration...");

        plugin.executeService(() -> {
            LinkCraftPlugin.ReloadResult result = plugin.reloadServices();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (result.isSuccess()) {
                    sendMessage(sender, "messages.reload-success",
                            "&aLinkCraft configuration reloaded successfully.");
                } else {
                    String message = plugin.getPluginConfig().getString(
                            "messages.reload-failed",
                            "&cReload failed. Redis: %redis%, MySQL: %mysql%. "
                                    + "Previous connections are still active.");
                    if (message == null) {
                        message = "&cReload failed. Previous connections are still active.";
                    }
                    message = message
                            .replace("%redis%", status(result.isRedisReady()))
                            .replace("%mysql%", status(result.isMysqlReady()));
                    sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                }
            });
        });
        return true;
    }

    private String status(boolean ready) {
        return ready ? "connected" : "failed";
    }

    private void sendMessage(CommandSender sender, String path, String fallback) {
        String message = plugin.getPluginConfig().getString(path, fallback);
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&',
                message == null ? fallback : message));
    }
}
