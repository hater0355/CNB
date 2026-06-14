package chatapp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class UserSettings {
    private static final String APP_DIR = "CHAT_NOI_BO";

    boolean soundEnabled = true;
    boolean toastEnabled = true;
    String theme = "light";
    String accentColor = "#0068ff";
    String chatBackground = "soft-blue";
    String presenceStatus = "ONLINE";
    boolean sidebarCollapsed = false;
    private final Map<String, String> avatarPaths = new HashMap<>();

    private final Path path;

    private UserSettings(Path path) {
        this.path = path;
    }

    static UserSettings load() {
        Path path = settingsPath();
        UserSettings settings = new UserSettings(path);
        Properties props = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
                settings.soundEnabled = boolProp(props, "notification.sound", true);
                settings.toastEnabled = boolProp(props, "notification.toast", true);
                settings.theme = props.getProperty("ui.theme", "light");
                settings.accentColor = props.getProperty("ui.accent", "#0068ff");
                settings.chatBackground = props.getProperty("chat.background", "soft-blue");
                settings.presenceStatus = props.getProperty("presence.status", "ONLINE");
                settings.sidebarCollapsed = boolProp(props, "sidebar.collapsed", false);
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith("avatar.")) {
                        settings.avatarPaths.put(key.substring("avatar.".length()), props.getProperty(key, ""));
                    }
                }
            } catch (IOException e) {
                System.err.println("Cannot read user settings: " + e.getMessage());
            }
        }
        return settings;
    }

    void save() {
        Properties props = new Properties();
        props.setProperty("notification.sound", String.valueOf(soundEnabled));
        props.setProperty("notification.toast", String.valueOf(toastEnabled));
        props.setProperty("ui.theme", theme == null || theme.isBlank() ? "light" : theme);
        props.setProperty("ui.accent", accentColor == null || accentColor.isBlank() ? "#0068ff" : accentColor);
        props.setProperty("chat.background", chatBackground == null || chatBackground.isBlank() ? "soft-blue" : chatBackground);
        props.setProperty("presence.status", presenceStatus == null || presenceStatus.isBlank() ? "ONLINE" : presenceStatus);
        props.setProperty("sidebar.collapsed", String.valueOf(sidebarCollapsed));
        for (Map.Entry<String, String> entry : avatarPaths.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isBlank()) {
                props.setProperty("avatar." + entry.getKey(), entry.getValue());
            }
        }
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "CHAT_NOI_BO local user settings");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không lưu được cài đặt: " + e.getMessage(), e);
        }
    }

    Path path() {
        return path;
    }

    String avatarPath(String username) {
        return avatarPaths.getOrDefault(username, "");
    }

    void setAvatarPath(String username, String avatarPath) {
        if (username == null || username.isBlank()) {
            return;
        }
        if (avatarPath == null || avatarPath.isBlank()) {
            avatarPaths.remove(username);
        } else {
            avatarPaths.put(username, avatarPath);
        }
    }

    private static Path settingsPath() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, APP_DIR, "user-settings.properties");
        }
        return Path.of(System.getProperty("user.home"), "." + APP_DIR, "user-settings.properties");
    }

    private static boolean boolProp(Properties props, String key, boolean fallback) {
        String value = props.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }
}
