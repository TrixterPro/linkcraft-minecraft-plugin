package com.linkcraft.minecraft;

import com.linkcraft.minecraft.commands.LinkCommand;
import com.linkcraft.minecraft.commands.LinkCraftCommand;
import com.linkcraft.minecraft.commands.UnlinkCommand;
import com.linkcraft.minecraft.cooldown.CooldownManager;
import com.linkcraft.minecraft.database.MySQLManager;
import com.linkcraft.minecraft.redis.RedisManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LinkCraftPlugin extends JavaPlugin {

    private volatile RedisManager redisManager;
    private volatile MySQLManager mySQLManager;
    private final CooldownManager cooldownManager = new CooldownManager();
    private ExecutorService serviceExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.redisManager = new RedisManager(this);
        this.mySQLManager = new MySQLManager(this);
        this.cooldownManager.reload(getConfig());
        this.serviceExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable,
                    "LinkCraft-Services-" + ServiceThreadCounter.NEXT.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        });

        PluginCommand linkCommand = getCommand("link");
        if (linkCommand == null) {
            getLogger().severe("The link command is missing from plugin.yml. Disabling LinkCraft.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        linkCommand.setExecutor(new LinkCommand(this));

        PluginCommand unlinkCommand = getCommand("unlink");
        if (unlinkCommand == null) {
            getLogger().severe("The unlink command is missing from plugin.yml. Disabling LinkCraft.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        unlinkCommand.setExecutor(new UnlinkCommand(this));

        PluginCommand linkCraftCommand = getCommand("linkcraft");
        if (linkCraftCommand == null) {
            getLogger().severe("The linkcraft command is missing from plugin.yml. Disabling LinkCraft.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        linkCraftCommand.setExecutor(new LinkCraftCommand(this));

        executeService(() -> {
            boolean databaseReady = mySQLManager.initialize();
            getLogger().info("Redis connection: " + redisManager.isConnected());
            getLogger().info("MySQL connection: " + databaseReady);
        });
    }

    @Override
    public void onDisable() {
        if (serviceExecutor != null) {
            serviceExecutor.shutdownNow();
            try {
                serviceExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (redisManager != null) {
            redisManager.close();
        }
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public MySQLManager getMySQLManager() {
        return mySQLManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public FileConfiguration getPluginConfig() {
        return getConfig();
    }

    public void executeService(Runnable task) {
        if (serviceExecutor == null || serviceExecutor.isShutdown()) {
            throw new IllegalStateException("LinkCraft service executor is not available");
        }
        serviceExecutor.execute(task);
    }

    public ReloadResult reloadServices() {
        RedisManager newRedis = null;
        try {
            newRedis = new RedisManager(this);
            MySQLManager newMySQL = new MySQLManager(this);

            boolean redisReady = newRedis.isConnected();
            boolean mysqlReady = newMySQL.initialize();
            if (!redisReady || !mysqlReady) {
                newRedis.close();
                return new ReloadResult(false, redisReady, mysqlReady);
            }

            RedisManager oldRedis = redisManager;
            redisManager = newRedis;
            mySQLManager = newMySQL;
            if (oldRedis != null) {
                oldRedis.close();
            }
            return new ReloadResult(true, true, true);
        } catch (RuntimeException e) {
            if (newRedis != null) {
                newRedis.close();
            }
            getLogger().severe("Could not reload services: " + e.getMessage());
            return new ReloadResult(false, false, false);
        }
    }

    public static final class ReloadResult {
        private final boolean success;
        private final boolean redisReady;
        private final boolean mysqlReady;

        public ReloadResult(boolean success, boolean redisReady, boolean mysqlReady) {
            this.success = success;
            this.redisReady = redisReady;
            this.mysqlReady = mysqlReady;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isRedisReady() {
            return redisReady;
        }

        public boolean isMysqlReady() {
            return mysqlReady;
        }
    }

    private static final class ServiceThreadCounter {
        private static final AtomicInteger NEXT = new AtomicInteger(1);

        private ServiceThreadCounter() {
        }
    }
}
