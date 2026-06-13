package chatapp;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

final class AppConfig {
    final String dbUrl;
    final String dbUser;
    final String dbPassword;
    final Path filesRoot;
    final int maxImageMb;
    final int maxVideoMb;
    final int maxFileMb;
    final int pollSeconds;
    final int retentionDays;

    private AppConfig(Properties p) {
        String defaultFiles = Path.of(System.getProperty("user.dir"), "chat-files").toString();
        dbUrl = p.getProperty("db.url", "jdbc:mysql://localhost:3306/quanlyluong?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true");
        dbUser = p.getProperty("db.user", "root");
        dbPassword = p.getProperty("db.password", "");
        filesRoot = Path.of(p.getProperty("chat.files.root", defaultFiles));
        maxImageMb = intProp(p, "chat.max.image.mb", 10);
        maxVideoMb = intProp(p, "chat.max.video.mb", 100);
        maxFileMb = intProp(p, "chat.max.file.mb", 50);
        pollSeconds = Math.max(1, intProp(p, "chat.poll.seconds", 3));
        retentionDays = Math.max(1, intProp(p, "chat.retention.days", 90));
    }

    static AppConfig load() {
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
