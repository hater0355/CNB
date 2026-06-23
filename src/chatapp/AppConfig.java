package chatapp;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

public final class AppConfig {
    public static final String DEFAULT_AUTH_TOKEN_SECRET = "CHAT_NOI_BO_CHANGE_ME_LOCAL_SECRET";

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
    public final String authTokenSecret;
    public final int authSessionHours;
    public final String slackWebhookUrl;
    public final String teamsWebhookUrl;
    public final boolean redisEnabled;
    public final String redisHost;
    public final int redisPort;
    public final String redisPassword;
    public final int redisDatabase;
    public final String redisChannel;
    public final int redisPresenceTtlSeconds;
    public final boolean fileEncryptionEnabled;
    public final String fileEncryptionKey;
    public final boolean closeToTray;
    public final boolean autoBackupEnabled;
    public final String autoBackupTime;
    public final Path autoBackupDir;
    public final int autoBackupKeepDays;

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
        authTokenSecret = p.getProperty("auth.token.secret", DEFAULT_AUTH_TOKEN_SECRET);
        authSessionHours = Math.max(1, intProp(p, "auth.session.hours", 8));
        slackWebhookUrl = p.getProperty("integration.slack.webhook", "");
        teamsWebhookUrl = p.getProperty("integration.teams.webhook", "");
        redisEnabled = Boolean.parseBoolean(p.getProperty("redis.enabled", "false"));
        redisHost = p.getProperty("redis.host", "localhost");
        redisPort = Math.max(1, intProp(p, "redis.port", 6379));
        redisPassword = p.getProperty("redis.password", "");
        redisDatabase = Math.max(0, intProp(p, "redis.database", 0));
        redisChannel = p.getProperty("redis.channel", "chat_events");
        redisPresenceTtlSeconds = Math.max(10, intProp(p, "redis.presence.ttl.seconds", 45));
        fileEncryptionEnabled = Boolean.parseBoolean(p.getProperty("chat.file.encryption.enabled", "false"));
        fileEncryptionKey = p.getProperty("chat.file.encryption.key", "");
        closeToTray = Boolean.parseBoolean(p.getProperty("ui.close.to.tray", "true"));
        autoBackupEnabled = Boolean.parseBoolean(p.getProperty("backup.auto.enabled", "true"));
        autoBackupTime = p.getProperty("backup.auto.time", "02:00");
        autoBackupDir = Path.of(p.getProperty("backup.auto.dir", "backups"));
        autoBackupKeepDays = Math.max(1, intProp(p, "backup.auto.keep.days", 14));
    }

    public static AppConfig load() {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("chat.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception e) {
            AppLog.warn("Không đọc được chat.properties, đang dùng cấu hình mặc định.", e);
        }
        AppConfig config = new AppConfig(p);
        if (config.isDefaultAuthTokenSecret()) {
            AppLog.warn("auth.token.secret vẫn là giá trị mặc định. Hãy đổi secret trước khi dùng thật trong mạng công ty.");
        }
        if (config.fileEncryptionEnabled && config.fileEncryptionKey.isBlank()) {
            AppLog.warn("chat.file.encryption.enabled=true nhưng chat.file.encryption.key đang trống. Gửi file sẽ bị chặn cho tới khi cấu hình khóa.");
        }
        if (config.realtimeUrl.startsWith("ws://") && !config.realtimeUrl.contains("localhost") && !config.realtimeUrl.contains("127.0.0.1")) {
            AppLog.warn("Realtime đang dùng ws:// không mã hóa. Khi triển khai ngoài máy local, hãy đặt sau VPN/TLS hoặc cấu hình WSS.");
        }
        return config;
    }

    public boolean isDefaultAuthTokenSecret() {
        return DEFAULT_AUTH_TOKEN_SECRET.equals(authTokenSecret);
    }

    private static int intProp(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(fallback)).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
