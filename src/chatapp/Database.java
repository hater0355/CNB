package chatapp;

import java.sql.Connection;
import java.sql.DriverManager;

final class Database {
    private final AppConfig config;

    Database(AppConfig config) {
        this.config = config;
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Missing MySQL driver", e);
        }
    }

    Connection getConnection() throws Exception {
        return DriverManager.getConnection(config.dbUrl, config.dbUser, config.dbPassword);
    }
}
