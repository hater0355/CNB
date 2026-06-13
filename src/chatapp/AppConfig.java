package chatapp;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public final class AppConfig {
    public final String dbUrl;
    public final String dbUser;
    public final String dbPassword;
    public final int dbPoolMax;
    public final int dbPoolMin;
    public final Path filesRoot;
    public final int maxImageMb;
    public final int maxVideoMb;
    public final int maxFileMb;
    public final int pollSeconds;
    public final int retentionDays;
    public final String realtimeHost;
    public final int realtimePort;
    public final String realtimeUrl;

    private AppConfig(Properties p) {
        String defaultFiles = Path.of(System.getProperty("user.dir"), "chat-files").toString();
        dbUrl = p.getProperty("db.url", "jdbc:mysql://localhost:3306/quanlyluong?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        dbUser = p.getProperty("db.user", "root");
        dbPassword = p.getProperty("db.password", "");
        dbPoolMax = Math.max(2, intProp(p, "db.pool.max", 10));
        dbPoolMin = Math.max(1, intProp(p, "db.pool.min", 2));
        filesRoot = Path.of(p.getProperty("chat.files.root", defaultFiles));
        maxImageMb = intProp(p, "chat.max.image.mb", 10);
        maxVideoMb = intProp(p, "chat.max.video.mb", 100);
        maxFileMb = intProp(p, "chat.max.file.mb", 50);
        pollSeconds = Math.max(1, intProp(p, "chat.poll.seconds", 3));
        retentionDays = Math.max(1, intProp(p, "chat.retention.days", 90));
        realtimeHost = p.getProperty("chat.realtime.host", "0.0.0.0");
        realtimePort = Math.max(1, intProp(p, "chat.realtime.port", 8787));
        realtimeUrl = p.getProperty("chat.realtime.url", "ws://localhost:" + realtimePort);
    }

    public static AppConfig load() {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("chat.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception e) {
            System.err.println("Cannot read chat.properties, using defaults: " + e.getMessage());
        }
        return new AppConfig(p);
    }

    private static int intProp(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
