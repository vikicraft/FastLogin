/*
 * SPDX-License-Identifier: MIT
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015-2021 <Your name and contributors>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.github.games647.fastlogin.core;

import com.github.games647.craftapi.UUIDAdapter;
import com.github.games647.fastlogin.core.shared.FastLoginCore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;

import static java.sql.Statement.RETURN_GENERATED_KEYS;

public class AuthStorage {

    private static final String PREMIUM_TABLE = "premium";

    private static final String LOAD_BY_NAME = "SELECT * FROM `" + PREMIUM_TABLE + "` WHERE `Name`=? LIMIT 1";
    private static final String LOAD_BY_UUID = "SELECT * FROM `" + PREMIUM_TABLE + "` WHERE `UUID`=? LIMIT 1";
    private static final String INSERT_PROFILE = "INSERT INTO `" + PREMIUM_TABLE
            + "` (`UUID`, `Name`, `Premium`, `LastIp`) " + "VALUES (?, ?, ?, ?) ";
    // limit not necessary here, because it's unique
    private static final String UPDATE_PROFILE = "UPDATE `" + PREMIUM_TABLE
            + "` SET `UUID`=?, `Name`=?, `Premium`=?, `LastIp`=?, `LastLogin`=CURRENT_TIMESTAMP WHERE `UserID`=?";

    private final FastLoginCore<?, ?, ?> core;
    private final HikariDataSource dataSource;

    public AuthStorage(FastLoginCore<?, ?, ?> core, String host, int port, String databasePath,
                       HikariConfig config, boolean useSSL) {
        this.core = core;
        config.setPoolName(core.getPlugin().getName());

        ThreadFactory platformThreadFactory = core.getPlugin().getThreadFactory();
        if (platformThreadFactory != null) {
            config.setThreadFactory(platformThreadFactory);
        }

        String jdbcUrl = "jdbc:";
        if (config.getDriverClassName().contains("sqlite")) {
            String pluginFolder = core.getPlugin().getPluginFolder().toAbsolutePath().toString();
            databasePath = databasePath.replace("{pluginDir}", pluginFolder);

            jdbcUrl += "sqlite://" + databasePath;
            config.setConnectionTestQuery("SELECT 1");
            config.setMaximumPoolSize(1);

            //a try to fix https://www.spigotmc.org/threads/fastlogin.101192/page-26#post-1874647
            // format strings retrieved by the timestamp column to match them from MySQL
            config.addDataSourceProperty("date_string_format", "yyyy-MM-dd HH:mm:ss");

            // TODO: test first for compatibility
            // config.addDataSourceProperty("date_precision", "seconds");
        } else {
            jdbcUrl += "mysql://" + host + ':' + port + '/' + databasePath;

            // Require SSL on the server if requested in config - this will also verify certificate
            // Those values are deprecated in favor of sslMode
            config.addDataSourceProperty("useSSL", useSSL);
            config.addDataSourceProperty("requireSSL", useSSL);

            // prefer encrypted if possible
            config.addDataSourceProperty("sslMode", "PREFERRED");

            // adding paranoid hides hostname, username, version and so
            // could be useful for hiding server details
            config.addDataSourceProperty("paranoid", true);

            // enable MySQL specific optimizations
            // disabled by default - will return the same prepared statement instance
            config.addDataSourceProperty("cachePrepStmts", true);
            // default prepStmtCacheSize 25 - amount of cached statements
            config.addDataSourceProperty("prepStmtCacheSize", 250);
            // default prepStmtCacheSqlLimit 256 - length of SQL
            config.addDataSourceProperty("prepStmtCacheSqlLimit", 2048);
            // default false - available in newer versions caches the statements server-side
            config.addDataSourceProperty("useServerPrepStmts", true);
            // default false - prefer use of local values for autocommit and
            // transaction isolation (alwaysSendSetIsolation) should only be enabled if always use the set* methods
            // instead of raw SQL
            // https://forums.mysql.com/read.php?39,626495,626512
            config.addDataSourceProperty("useLocalSessionState", true);
            // rewrite batched statements to a single statement, adding them behind each other
            // only useful for addBatch statements and inserts
            config.addDataSourceProperty("rewriteBatchedStatements", true);
            // cache result metadata
            config.addDataSourceProperty("cacheResultSetMetadata", true);
            // cache results of show variables and collation per URL
            config.addDataSourceProperty("cacheServerConfiguration", true);
            // default false - set auto commit only if not matching
            config.addDataSourceProperty("elideSetAutoCommits", true);

            // default true - internal timers for idle calculation -> removes System.getCurrentTimeMillis call per query
            // Some platforms are slow on this and it could affect the throughput about 3% according to MySQL
            // performance gems presentation
            // In our case it can be useful to see the time in error messages
            // config.addDataSourceProperty("maintainTimeStats", false);
        }

        config.setJdbcUrl(jdbcUrl);
        this.dataSource = new HikariDataSource(config);
    }

    public void createTables() throws SQLException {
        // choose surrogate PK(ID), because UUID can be null for offline players
        // if UUID is always Premium UUID we would have to update offline player entries on insert
        // name cannot be PK, because it can be changed for premium players
        String createDataStmt = "CREATE TABLE IF NOT EXISTS `" + PREMIUM_TABLE + "` ("
                + "`UserID` INTEGER PRIMARY KEY AUTO_INCREMENT, "
                + "`UUID` CHAR(36), "
                + "`Name` VARCHAR(16) NOT NULL, "
                + "`Premium` BOOLEAN NOT NULL, "
                + "`LastIp` VARCHAR(255) NOT NULL, "
                + "`LastLogin` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                //the premium shouldn't steal the cracked account by changing the name
                + "UNIQUE (`Name`) "
                + ')';

        if (dataSource.getJdbcUrl().contains("sqlite")) {
            createDataStmt = createDataStmt.replace("AUTO_INCREMENT", "AUTOINCREMENT");
        }

        //todo: add unique uuid index usage
        try (Connection con = dataSource.getConnection();
             Statement createStmt = con.createStatement()) {
            createStmt.executeUpdate(createDataStmt);
        }
    }

    public StoredProfile loadProfile(String name) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement loadStmt = con.prepareStatement(LOAD_BY_NAME)
        ) {
            loadStmt.setString(1, name);

            try (ResultSet resultSet = loadStmt.executeQuery()) {
                return parseResult(resultSet).orElseGet(() -> new StoredProfile(null, name, false, ""));
            }
        } catch (SQLException sqlEx) {
            core.getPlugin().getLog().error("Failed to query profile: {}", name, sqlEx);
        }

        return null;
    }

    public StoredProfile loadProfile(UUID uuid) {
        try (Connection con = dataSource.getConnection();
             PreparedStatement loadStmt = con.prepareStatement(LOAD_BY_UUID)) {
            loadStmt.setString(1, UUIDAdapter.toMojangId(uuid));

            try (ResultSet resultSet = loadStmt.executeQuery()) {
                return parseResult(resultSet).orElse(null);
            }
        } catch (SQLException sqlEx) {
            core.getPlugin().getLog().error("Failed to query profile: {}", uuid, sqlEx);
        }

        return null;
    }

    private Optional<StoredProfile> parseResult(ResultSet resultSet) throws SQLException {
        if (resultSet.next()) {
            long userId = resultSet.getInt(1);

            UUID uuid = Optional.ofNullable(resultSet.getString(2)).map(UUIDAdapter::parseId).orElse(null);

            String name = resultSet.getString(3);
            boolean premium = resultSet.getBoolean(4);
            String lastIp = resultSet.getString(5);
            Instant lastLogin = resultSet.getTimestamp(6).toInstant();
            return Optional.of(new StoredProfile(userId, uuid, name, premium, lastIp, lastLogin));
        }

        return Optional.empty();
    }

    public void save(StoredProfile playerProfile) {
        try (Connection con = dataSource.getConnection()) {
            String uuid = playerProfile.getOptId().map(UUIDAdapter::toMojangId).orElse(null);

            playerProfile.getSaveLock().lock();
            try {
                if (playerProfile.isSaved()) {
                    try (PreparedStatement saveStmt = con.prepareStatement(UPDATE_PROFILE)) {
                        saveStmt.setString(1, uuid);
                        saveStmt.setString(2, playerProfile.getName());
                        saveStmt.setBoolean(3, playerProfile.isPremium());
                        saveStmt.setString(4, playerProfile.getLastIp());

                        saveStmt.setLong(5, playerProfile.getRowId());
                        saveStmt.execute();
                    }
                } else {
                    try (PreparedStatement saveStmt = con.prepareStatement(INSERT_PROFILE, RETURN_GENERATED_KEYS)) {
                        saveStmt.setString(1, uuid);

                        saveStmt.setString(2, playerProfile.getName());
                        saveStmt.setBoolean(3, playerProfile.isPremium());
                        saveStmt.setString(4, playerProfile.getLastIp());

                        saveStmt.execute();
                        try (ResultSet generatedKeys = saveStmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                playerProfile.setRowId(generatedKeys.getInt(1));
                            }
                        }
                    }
                }
            } finally {
                playerProfile.getSaveLock().unlock();
            }
        } catch (SQLException ex) {
            core.getPlugin().getLog().error("Failed to save playerProfile {}", playerProfile, ex);
        }
    }

    public void close() {
        dataSource.close();
    }
}
