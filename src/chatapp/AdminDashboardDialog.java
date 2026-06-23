package chatapp;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

final class AdminDashboardDialog {
    interface DbCall<T> {
        T execute() throws Exception;
    }

    interface DbRunner {
        <T> void run(DbCall<T> call, Consumer<T> onSuccess, Consumer<Exception> onFailed);
    }

    private final Stage owner;
    private final CurrentUser currentUser;
    private final AdminDashboardService adminDashboardService;
    private final ChatService chatService;
    private final AuditLogService auditLogService;
    private final DbRunner dbRunner;
    private final Consumer<Conversation> openGroup;
    private final Consumer<String> showInfo;
    private final ErrorHandler showError;

    AdminDashboardDialog(
            Stage owner,
            CurrentUser currentUser,
            AdminDashboardService adminDashboardService,
            ChatService chatService,
            AuditLogService auditLogService,
            DbRunner dbRunner,
            Consumer<Conversation> openGroup,
            Consumer<String> showInfo,
            ErrorHandler showError) {
        this.owner = owner;
        this.currentUser = currentUser;
        this.adminDashboardService = adminDashboardService;
        this.chatService = chatService;
        this.auditLogService = auditLogService;
        this.dbRunner = dbRunner;
        this.openGroup = openGroup;
        this.showInfo = showInfo;
        this.showError = showError;
    }

    void show() {
        if (!currentUser.isAdmin()) {
            showInfo.accept("Chỉ admin được xem dashboard quản trị.");
            return;
        }
        dbRunner.run(() -> adminDashboardService.dashboard(currentUser), this::showDashboard, e -> showError.show("Không tải được dashboard quản trị", e));
    }

    private void showDashboard(AdminDashboard dashboard) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Dashboard quản trị");
        styleDialog(dialog);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        VBox topGroups = new VBox(8);
        topGroups.getStyleClass().add("settings-section");
        Label topTitle = new Label("Nhóm hoạt động nhất");
        topTitle.getStyleClass().add("settings-title");
        topGroups.getChildren().add(topTitle);
        if (dashboard.topConversations.isEmpty()) {
            topGroups.getChildren().add(emptyState("Chưa có dữ liệu hoạt động.", ""));
        } else {
            for (String line : dashboard.topConversations) {
                topGroups.getChildren().add(settingLine("Nhóm", line));
            }
        }

        VBox content = new VBox(12,
                settingsSection("Người dùng",
                        settingLine("Nhân viên đã duyệt", String.valueOf(dashboard.approvedUsers)),
                        settingLine("Tài khoản chờ/khác", String.valueOf(dashboard.pendingUsers))),
                settingsSection("Hoạt động chat",
                        settingLine("Hội thoại", String.valueOf(dashboard.conversations)),
                        settingLine("Tin nhắn 7 ngày", String.valueOf(dashboard.messages7d)),
                        settingLine("Task quá hạn", String.valueOf(dashboard.overdueTasks))),
                topGroups);

        Button manageGroups = headerButton("Quản lý nhóm", this::showAdminGroupManagerDialog);
        Button filterAudit = headerButton("Lọc audit", this::showAuditFilterDialog);
        content.getChildren().add(new HBox(10, manageGroups, filterAudit));

        TabPane detailTabs = new TabPane();
        detailTabs.getTabs().addAll(
                dashboardListTab("User", dashboard.users),
                dashboardListTab("Nhóm", dashboard.conversationLines),
                dashboardListTab("Audit", dashboard.auditLines),
                dashboardListTab("Task", dashboard.taskLines));
        detailTabs.setPrefHeight(320);
        content.getChildren().add(detailTabs);
        content.getStyleClass().add("settings-content");

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(560, 520);
        scroll.getStyleClass().add("settings-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
        auditLogService.log(currentUser.username, "ADMIN_DASHBOARD_VIEWED", "DASHBOARD", currentUser.companyOwner, "");
    }

    private void showAdminGroupManagerDialog() {
        dbRunner.run(() -> chatService.listConversations(currentUser), loaded -> {
            List<Conversation> groups = loaded.stream()
                    .filter(c -> !"DIRECT".equals(c.type))
                    .toList();
            Dialog<Conversation> dialog = new Dialog<>();
            dialog.initOwner(owner);
            dialog.setTitle("Quản lý nhóm/hội thoại");
            styleDialog(dialog);
            ButtonType manage = new ButtonType("Quản lý", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(manage, ButtonType.CANCEL);
            ListView<Conversation> list = new ListView<>(FXCollections.observableArrayList(groups));
            list.getStyleClass().add("dialog-list");
            list.setCellFactory(view -> new SidebarCells.ConversationCell());
            list.setPrefSize(520, 420);
            dialog.getDialogPane().setContent(new VBox(10,
                    label("Chọn nhóm để mở màn quản lý thành viên/quyền.", "dialog-label"),
                    list));
            dialog.setResultConverter(btn -> btn == manage ? list.getSelectionModel().getSelectedItem() : null);
            dialog.showAndWait().ifPresent(group -> {
                if ("GROUP".equals(group.type)) {
                    openGroup.accept(group);
                } else {
                    showInfo.accept("Nhóm hệ thống " + group.title + " chỉ xem trong bản này, chưa chỉnh trực tiếp từ dashboard.");
                }
            });
        }, e -> showError.show("Không tải được danh sách nhóm", e));
    }

    private void showAuditFilterDialog() {
        Dialog<AuditFilter> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Lọc audit log");
        styleDialog(dialog);
        ButtonType search = new ButtonType("Tìm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(search, ButtonType.CANCEL);
        TextField actor = textField("Tài khoản");
        TextField action = textField("Hành động, ví dụ: MESSAGE, BACKUP, LOGIN");
        DatePicker from = new DatePicker(LocalDate.now().minusDays(30));
        DatePicker to = new DatePicker(LocalDate.now());
        VBox content = new VBox(10,
                label("Tài khoản", "dialog-label"), actor,
                label("Hành động", "dialog-label"), action,
                label("Từ ngày", "dialog-label"), from,
                label("Đến ngày", "dialog-label"), to);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == search ? new AuditFilter(actor.getText(), action.getText(), from.getValue(), to.getValue()) : null);
        dialog.showAndWait().ifPresent(filter -> dbRunner.run(
                () -> adminDashboardService.auditLines(currentUser, filter.actor, filter.action, filter.from, filter.to),
                this::showAuditLinesDialog,
                e -> showError.show("Không lọc được audit log", e)));
    }

    private void showAuditLinesDialog(List<String> lines) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Kết quả audit log");
        styleDialog(dialog);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-content");
        if (lines == null || lines.isEmpty()) {
            box.getChildren().add(emptyState("Chưa có dữ liệu", "Không tìm thấy audit log phù hợp bộ lọc."));
        } else {
            for (String line : lines) {
                Label row = valueLabel(line);
                box.getChildren().add(row);
            }
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(640, 460);
        scroll.getStyleClass().add("settings-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.showAndWait();
    }

    private Tab dashboardListTab(String title, List<String> lines) {
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-content");
        if (lines == null || lines.isEmpty()) {
            box.getChildren().add(emptyState("Chưa có dữ liệu", "Dữ liệu sẽ hiển thị khi hệ thống có bản ghi."));
        } else {
            for (String line : lines) {
                box.getChildren().add(valueLabel(line));
            }
        }
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }

    private static Button headerButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("header-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static TextField textField(String prompt) {
        TextField field = new TextField();
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

    private static Node emptyState(String title, String detail) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("empty-detail");
        VBox box = new VBox(8, titleLabel, detailLabel);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        return box;
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

    interface ErrorHandler {
        void show(String title, Exception error);
    }

    private record AuditFilter(String actor, String action, LocalDate from, LocalDate to) {
    }
}
