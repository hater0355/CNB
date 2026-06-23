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
    String textColor = "#f8fafc";
    String mutedColor = "#64748b";
    int fontSize = 14;
    String chatBackground = "soft-blue";
    String presenceStatus = "ONLINE";
    boolean sidebarCollapsed = false;
    boolean bandwidthSaving = false;
    String slackWebhookUrl = "";
    String teamsWebhookUrl = "";
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
                settings.textColor = props.getProperty("ui.text.color", "#f8fafc");
                settings.mutedColor = props.getProperty("ui.muted.color", "#64748b");
                settings.fontSize = clampInt(props.getProperty("ui.font.size"), 14, 12, 18);
                settings.chatBackground = props.getProperty("chat.background", "soft-blue");
                settings.presenceStatus = props.getProperty("presence.status", "ONLINE");
                settings.sidebarCollapsed = boolProp(props, "sidebar.collapsed", false);
                settings.bandwidthSaving = boolProp(props, "bandwidth.saving", false);
                settings.slackWebhookUrl = props.getProperty("integration.slack.webhook", "");
                settings.teamsWebhookUrl = props.getProperty("integration.teams.webhook", "");
                for (String key : props.stringPropertyNames()) {
                    if (key.startsWith("avatar.")) {
                        settings.avatarPaths.put(key.substring("avatar.".length()), props.getProperty(key, ""));
                    }
                }
            } catch (IOException e) {
                AppLog.warn("Không đọc được cài đặt người dùng.", e);
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
        props.setProperty("ui.text.color", textColor == null || textColor.isBlank() ? "#f8fafc" : textColor);
        props.setProperty("ui.muted.color", mutedColor == null || mutedColor.isBlank() ? "#64748b" : mutedColor);
        props.setProperty("ui.font.size", String.valueOf(Math.max(12, Math.min(18, fontSize))));
        props.setProperty("chat.background", chatBackground == null || chatBackground.isBlank() ? "soft-blue" : chatBackground);
        props.setProperty("presence.status", presenceStatus == null || presenceStatus.isBlank() ? "ONLINE" : presenceStatus);
        props.setProperty("sidebar.collapsed", String.valueOf(sidebarCollapsed));
        props.setProperty("bandwidth.saving", String.valueOf(bandwidthSaving));
        props.setProperty("integration.slack.webhook", slackWebhookUrl == null ? "" : slackWebhookUrl);
        props.setProperty("integration.teams.webhook", teamsWebhookUrl == null ? "" : teamsWebhookUrl);
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

    private static int clampInt(String value, int fallback, int min, int max) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
