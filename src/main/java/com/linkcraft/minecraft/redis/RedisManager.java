package com.linkcraft.minecraft.redis;

import com.linkcraft.minecraft.LinkCraftPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/**
 * Reads temporary verification codes created by the Discord bot.
 */
public class RedisManager {
    private final JedisPool jedisPool;
    private final LinkCraftPlugin plugin;
    private final boolean identityCacheEnabled;
    private final int identityCacheTtlSeconds;

    private final String codePrefix;
    private final String uuidCachePrefix;
    private final String usernameCachePrefix;

    public RedisManager(LinkCraftPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getPluginConfig();
        String host = cfg.getString("redis.host", "127.0.0.1");
        int port = cfg.getInt("redis.port", 6379);
        String password = cfg.getString("redis.password", "");
        int database = Math.max(0, cfg.getInt("redis.database", 0));
        int timeout = Math.max(1, cfg.getInt("redis.connection-timeout-ms", 2000));
        this.codePrefix = cfg.getString("redis.keys.code-prefix", "linkcraft:code:");
        this.uuidCachePrefix = cfg.getString(
                "redis.keys.uuid-cache-prefix", "linkcraft:cache:uuid:");
        this.usernameCachePrefix = cfg.getString(
                "redis.keys.username-cache-prefix", "linkcraft:cache:username:");
        this.identityCacheEnabled = cfg.getBoolean("redis.identity-cache.enabled", true);
        this.identityCacheTtlSeconds = Math.max(0,
                cfg.getInt("redis.identity-cache.ttl-seconds", 86400));

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(Math.max(1, cfg.getInt("redis.pool-size", 8)));
        poolConfig.setMaxIdle(Math.max(0, cfg.getInt("redis.max-idle", 8)));
        poolConfig.setMinIdle(Math.max(0, cfg.getInt("redis.min-idle", 0)));
        poolConfig.setTestOnBorrow(true);
        if (password != null && !password.isEmpty()) {
            this.jedisPool = new JedisPool(
                    poolConfig, host, port, timeout, password, database);
        } else {
            this.jedisPool = new JedisPool(
                    poolConfig, host, port, timeout, null, database);
        }
    }

    public String getDiscordId(String code) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(codePrefix + code);
        } catch (Exception e) {
            plugin.getLogger().severe("Redis error while checking code: " + e.getMessage());
            throw new IllegalStateException("Could not read verification code from Redis", e);
        }
    }

    public void deleteCode(String code) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(codePrefix + code);
        } catch (Exception e) {
            plugin.getLogger().warning("Linked account, but could not delete Redis code: "
                    + e.getMessage());
        }
    }

    public void cacheIdentity(String playerUuid, String username) {
        if (!identityCacheEnabled) {
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String normalizedUsername = username.toLowerCase(Locale.ROOT);
            if (identityCacheTtlSeconds > 0) {
                jedis.setex(uuidCachePrefix + playerUuid, identityCacheTtlSeconds, username);
                jedis.setex(usernameCachePrefix + normalizedUsername,
                        identityCacheTtlSeconds, playerUuid);
            } else {
                jedis.set(uuidCachePrefix + playerUuid, username);
                jedis.set(usernameCachePrefix + normalizedUsername, playerUuid);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not cache player identity in Redis: "
                    + e.getMessage());
        }
    }

    public void deleteIdentityCache(String playerUuid, String username) {
        try (Jedis jedis = jedisPool.getResource()) {
            String uuidKey = uuidCachePrefix + playerUuid;
            String cachedUsername = jedis.get(uuidKey);
            String currentUsernameKey =
                    usernameCachePrefix + username.toLowerCase(Locale.ROOT);

            if (cachedUsername == null
                    || cachedUsername.equalsIgnoreCase(username)) {
                jedis.del(uuidKey, currentUsernameKey);
            } else {
                jedis.del(uuidKey, currentUsernameKey,
                        usernameCachePrefix + cachedUsername.toLowerCase(Locale.ROOT));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not delete player identity cache: "
                    + e.getMessage());
        }
    }

    /**
     * Returns true if the Redis connection is alive.
     */
    public boolean isConnected() {
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gracefully shuts down the connection pool.
     */
    public void close() {
        try {
            jedisPool.close();
        } catch (Exception e) {
            plugin.getLogger().severe("Error closing Redis pool: " + e.getMessage());
        }
    }

}
