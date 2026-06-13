package chatapp;

import java.sql.Connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class Database {
    private final HikariDataSource dataSource;

    public Database(AppConfig config) {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing MySQL driver", e);
        }
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(config.dbUrl);
        hikari.setUsername(config.dbUser);
        hikari.setPassword(config.dbPassword);
        hikari.setMaximumPoolSize(config.dbPoolMax);
        hikari.setMinimumIdle(config.dbPoolMin);
        hikari.setPoolName("CHAT_NOI_BO_POOL");
        hikari.setInitializationFailTimeout(-1);
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(hikari);
    }

    public Connection getConnection() throws Exception {
        return dataSource.getConnection();
    }

    public void close() {
        dataSource.close();
    }
}
