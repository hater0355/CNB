package chatapp;

import java.io.File;
import java.nio.file.Files;
import java.util.Objects;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

final class SettingsDialog {
    private final Stage owner;
    private final AppConfig config;
    private final UserSettings userSettings;
    private final CurrentUser currentUser;
    private final Runnable openFilesRoot;
    private final Runnable openReportsFolder;
    private final Runnable backupData;
    private final Runnable restoreData;
    private final Runnable adminDashboard;
    private final Runnable archiveOldMessages;
    private final Runnable exportTaskHtml;
    private final Runnable exportEngagementHtml;
    private final Runnable afterSave;

    SettingsDialog(
            Stage owner,
            AppConfig config,
            UserSettings userSettings,
            CurrentUser currentUser,
            Runnable openFilesRoot,
            Runnable openReportsFolder,
            Runnable backupData,
            Runnable restoreData,
            Runnable adminDashboard,
            Runnable archiveOldMessages,
            Runnable exportTaskHtml,
            Runnable exportEngagementHtml,
            Runnable afterSave) {
        this.owner = owner;
        this.config = config;
        this.userSettings = userSettings;
        this.currentUser = currentUser;
        this.openFilesRoot = openFilesRoot;
        this.openReportsFolder = openReportsFolder;
        this.backupData = backupData;
        this.restoreData = restoreData;
        this.adminDashboard = adminDashboard;
        this.archiveOldMessages = archiveOldMessages;
        this.exportTaskHtml = exportTaskHtml;
        this.exportEngagementHtml = exportEngagementHtml;
        this.afterSave = afterSave;
    }

    void show() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Cài đặt");
        styleDialog(dialog);
        ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        CheckBox sound = checkBox("Phát âm báo khi có tin nhắn mới", userSettings.soundEnabled);
        CheckBox toast = checkBox("Hiện thông báo nổi khi có tin nhắn mới", userSettings.toastEnabled);
        CheckBox darkMode = checkBox("Chế độ tối (chuẩn bị cho bản sau)", "dark".equalsIgnoreCase(userSettings.theme));
        darkMode.setDisable(true);
        CheckBox bandwidthSaving = checkBox("Tiết kiệm băng thông: không tự preview ảnh/video lớn", userSettings.bandwidthSaving);

        String[] selectedAccent = {validHex(userSettings.accentColor) ? userSettings.accentColor : "#007aff"};
        VBox accentPalette = colorPalette(selectedAccent);
        ComboBox<String> backgroundPicker = new ComboBox<>(FXCollections.observableArrayList(
                "soft-blue", "clean-white", "mint", "lavender", "peach", "night"));
        backgroundPicker.getStyleClass().add("dialog-search");
        backgroundPicker.setValue(userSettings.chatBackground == null || userSettings.chatBackground.isBlank()
                ? "soft-blue"
                : userSettings.chatBackground);

        String[] selectedAvatar = {userSettings.avatarPath(currentUser.username)};
        Label avatarPath = valueLabel(selectedAvatar[0] == null || selectedAvatar[0].isBlank() ? "Chưa chọn avatar" : selectedAvatar[0]);
        Button chooseAvatar = headerButton("Chọn avatar", () -> chooseAvatar(selectedAvatar, avatarPath));

        Label filePath = valueLabel(config.filesRoot.toAbsolutePath().toString());
        Button openFolder = headerButton("Mở thư mục", openFilesRoot);

        TextField slackWebhook = textField(userSettings.slackWebhookUrl, "Slack webhook URL");
        TextField teamsWebhook = textField(userSettings.teamsWebhookUrl, "Teams webhook URL");

        Button backup = adminButton("Backup dữ liệu chat", backupData);
        Button restore = adminButton("Restore backup chat", restoreData);
        Button dashboard = adminButton("Dashboard quản trị", adminDashboard);
        Button archive = adminButton("Archive tin cũ", archiveOldMessages);
        Button taskHtml = headerButton("Export task HTML", exportTaskHtml);
        Button engagementHtml = adminButton("Export tương tác HTML", exportEngagementHtml);
        Button reportsFolder = headerButton("Mở thư mục báo cáo", openReportsFolder);

        VBox notificationSection = settingsSection("Thông báo", sound, toast);
        VBox appearanceSection = settingsSection("Giao diện",
                settingLine("Avatar", "Ảnh đại diện lưu riêng trên máy này"),
                avatarPath,
                chooseAvatar,
                settingLine("Màu chủ đạo", "Chọn màu bạn thích cho nút, avatar và điểm nhấn"),
                accentPalette,
                settingLine("Nền khung chat", "soft-blue, clean-white, mint, lavender, peach, night"),
                backgroundPicker,
                bandwidthSaving,
                darkMode,
                label("Màu và nền được lưu riêng trên máy của từng nhân viên.", "settings-note"));
        VBox fileSection = settingsSection("File", filePath, openFolder,
                label(Files.exists(config.filesRoot)
                        ? "Thư mục đang tồn tại."
                        : "Thư mục chưa tồn tại, app sẽ tự tạo khi gửi file.", "settings-note"));
        VBox integrationSection = settingsSection("Tích hợp", slackWebhook, teamsWebhook,
                label("URL để trống sẽ dùng cấu hình trong chat.properties nếu có.", "settings-note"));
        VBox systemSection = settingsSection("Hệ thống",
                settingLine("Database", config.dbUrl),
                settingLine("Giới hạn ảnh", config.maxImageMb + " MB"),
                settingLine("Giới hạn video", config.maxVideoMb + " MB"),
                settingLine("Giới hạn file", config.maxFileMb + " MB"),
                settingLine("Làm mới tin nhắn", config.pollSeconds + " giây"),
                settingLine("Tự xóa tin cũ", config.retentionDays + " ngày"),
                settingLine("File cài đặt", userSettings.path().toString()),
                dashboard,
                backup,
                restore,
                archive,
                taskHtml,
                engagementHtml,
                reportsFolder,
                label(currentUser.isAdmin()
                        ? "Backup xuất ZIP chứa CSV các bảng chat chính."
                        : "Chỉ admin được backup dữ liệu chat.", "settings-note"));

        VBox content = new VBox(14, notificationSection, appearanceSection, fileSection, integrationSection, systemSection);
        content.getStyleClass().add("settings-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(620, 560);
        scroll.getStyleClass().add("settings-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(button -> {
            if (button == save) {
                userSettings.soundEnabled = sound.isSelected();
                userSettings.toastEnabled = toast.isSelected();
                userSettings.theme = darkMode.isSelected() ? "dark" : "light";
                userSettings.accentColor = selectedAccent[0];
                userSettings.chatBackground = backgroundPicker.getValue();
                userSettings.bandwidthSaving = bandwidthSaving.isSelected();
                userSettings.slackWebhookUrl = slackWebhook.getText().trim();
                userSettings.teamsWebhookUrl = teamsWebhook.getText().trim();
                userSettings.setAvatarPath(currentUser.username, selectedAvatar[0]);
                userSettings.save();
                afterSave.run();
            }
            return null;
        });
        dialog.showAndWait();
    }

    private void chooseAvatar(String[] selectedAvatar, Label avatarPath) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh đại diện");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        File file = chooser.showOpenDialog(owner);
        if (file != null) {
            selectedAvatar[0] = file.getAbsolutePath();
            avatarPath.setText(selectedAvatar[0]);
        }
    }

    private Button adminButton(String text, Runnable action) {
        Button button = headerButton(text, action);
        button.setDisable(!currentUser.isAdmin());
        return button;
    }

    private static CheckBox checkBox(String text, boolean selected) {
        CheckBox box = new CheckBox(text);
        box.getStyleClass().add("settings-check");
        box.setSelected(selected);
        return box;
    }

    private static Button headerButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("header-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static TextField textField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        field.setPromptText(prompt);
        field.getStyleClass().add("dialog-search");
        return field;
    }

    private static VBox settingsSection(String title, Node... children) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-title");
        VBox box = new VBox(10, titleLabel);
        box.getChildren().addAll(children);
        box.getStyleClass().add("settings-section");
        return box;
    }

    private static HBox settingLine(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("settings-key");
        Label valueLabel = valueLabel(value == null || value.isBlank() ? "Chưa cập nhật" : value);
        HBox line = new HBox(12, keyLabel, valueLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyLabel, Priority.ALWAYS);
        return line;
    }

    private static Label valueLabel(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("settings-value");
        label.setWrapText(true);
        return label;
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static VBox colorPalette(String[] selectedAccent) {
        HBox row = new HBox(8);
        row.getStyleClass().add("color-palette");
        for (String color : new String[]{"#007aff", "#2f65ff", "#8b5cf6", "#14b8a6", "#ef4444", "#f59e0b"}) {
            Button swatch = new Button();
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color: " + color + ";");
            swatch.setOnAction(e -> selectedAccent[0] = color);
            row.getChildren().add(swatch);
        }
        Label selected = valueLabel(selectedAccent[0]);
        selected.setMinWidth(90);
        row.getChildren().add(selected);
        for (Node node : row.getChildren()) {
            if (node instanceof Button button) {
                button.setOnMouseClicked(e -> selected.setText(selectedAccent[0]));
            }
        }
        return new VBox(8, row);
    }

    private static boolean validHex(String value) {
        if (value == null || !value.matches("#[0-9a-fA-F]{6}")) {
            return false;
        }
        try {
            Color.web(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void styleDialog(Dialog<?> dialog) {
        String css = ChatApp.class.getResource("style.css") == null
                ? null
                : Objects.requireNonNull(ChatApp.class.getResource("style.css")).toExternalForm();
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        dialog.getDialogPane().getStyleClass().add("custom-dialog");
    }
}
