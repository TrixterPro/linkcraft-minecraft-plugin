package com.linkcraft.minecraft.database;

import com.linkcraft.minecraft.LinkCraftPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

public class MySQLManager {
    private static final String CREATE_TABLE =
            "CREATE TABLE IF NOT EXISTS linkcraft_links ("
                    + "minecraft_uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "discord_user_id VARCHAR(32) NOT NULL UNIQUE,"
                    + "linked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")";

    private final LinkCraftPlugin plugin;
    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MySQLManager(LinkCraftPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration config = plugin.getPluginConfig();
        String host = config.getString("mysql.host", "127.0.0.1");
        int port = config.getInt("mysql.port", 3306);
        String database = config.getString("mysql.database", "linkcraft");
        boolean useSsl = config.getBoolean("mysql.use-ssl", false);
        boolean allowPublicKeyRetrieval = config.getBoolean(
                "mysql.allow-public-key-retrieval", true);
        int connectTimeout = Math.max(1,
                config.getInt("mysql.connection-timeout-ms", 5000));
        int socketTimeout = Math.max(1,
                config.getInt("mysql.socket-timeout-ms", 5000));
        this.username = config.getString("mysql.username", "root");
        this.password = config.getString("mysql.password", "");
        this.jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl
                + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                + "&serverTimezone=UTC"
                + "&connectTimeout=" + connectTimeout
                + "&socketTimeout=" + socketTimeout;
    }

    public boolean initialize() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection connection = getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(CREATE_TABLE);
                return true;
            }
        } catch (ClassNotFoundException | SQLException e) {
            plugin.getLogger().severe("Could not initialize MySQL: " + e.getMessage());
            return false;
        }
    }

    public boolean isPlayerLinked(UUID playerUuid) throws SQLException {
        String sql = "SELECT 1 FROM linkcraft_links WHERE minecraft_uuid = ? LIMIT 1";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public CreateLinkResult createLink(UUID playerUuid, String discordId) throws SQLException {
        String sql = "INSERT INTO linkcraft_links (minecraft_uuid, discord_user_id) VALUES (?, ?)";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, discordId);
            statement.executeUpdate();
            return CreateLinkResult.SUCCESS;
        } catch (SQLException e) {
            if ("23000".equals(e.getSQLState())) {
                return isPlayerLinked(playerUuid)
                        ? CreateLinkResult.PLAYER_ALREADY_LINKED
                        : CreateLinkResult.DISCORD_ALREADY_LINKED;
            }
            throw e;
        }
    }

    public boolean deleteLink(UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM linkcraft_links WHERE minecraft_uuid = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            return statement.executeUpdate() > 0;
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    public enum CreateLinkResult {
        SUCCESS,
        PLAYER_ALREADY_LINKED,
        DISCORD_ALREADY_LINKED
    }
}
