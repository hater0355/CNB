package chatapp;

import java.awt.Desktop;
import java.awt.Graphics2D;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.TargetDataLine;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.paint.Color;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class ChatApp extends Application {
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final AppConfig config = AppConfig.load();
    private final Database database = new Database(config);
    private final ChatAuthService authService = new ChatAuthService(database);
    private final FileStorageService storageService = new FileStorageService(config);
    private final ChatService chatService = new ChatService(database, storageService);
    private final TaskService taskService = new TaskService(database);
    private final SecurityService securityService = new SecurityService(database, config);
    private final AuditLogService auditLogService = new AuditLogService(database);
    private final ReportService reportService = new ReportService(database);
    private final BackupService backupService = new BackupService(database);
    private final AdminDashboardService adminDashboardService = new AdminDashboardService(database);
    private final WebhookService webhookService = new WebhookService(config);
    private final PendingMessageService pendingMessageService = new PendingMessageService();
    private final UserSettings userSettings = UserSettings.load();
    private MessageBubbleFactory messageBubbleFactory;
    private SidebarPanel sidebarPanel;

    private Stage stage;
    private CurrentUser currentUser;
    private Conversation currentConversation;
    private final ObservableList<Conversation> conversations = FXCollections.observableArrayList();
    private final ObservableList<Conversation> visibleConversations = FXCollections.observableArrayList();
    private final List<File> selectedFiles = new ArrayList<>();
    private List<ChatUser> companyUsers = new ArrayList<>();
    private List<ChatMessage> currentMessages = new ArrayList<>();
    private List<ChatTask> currentTasks = new ArrayList<>();
    private ListView<Conversation> conversationList;
    private VBox messageBox;
    private TextArea input;
    private BorderPane appRoot;
    private ScrollPane messageScroll;
    private TextField conversationSearch;
    private TextField messageSearch;
    private Label headerAvatar;
    private Label headerTitle;
    private Label headerDetail;
    private Label attachmentLabel;
    private Label replyLabel;
    private Button manageGroupButton;
    private Button pinConversationButton;
    private Button exportConversationButton;
    private Button advancedSearchButton;
    private Button taskDrawerButton;
    private TaskDrawerPanel taskDrawer;
    private String activeNav = "CHAT";
    private final Set<Long> conversationIdsWithTasks = new HashSet<>();
    private RealtimeClient realtimeClient;
    private String sessionToken = "";
    private final ExecutorService dbExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "chat-db-worker");
        thread.setDaemon(true);
        return thread;
    });
    private Long replyToId;
    private int lastUnreadTotal = -1;
    private boolean loadingConversations;
    private long lastRenderedConversationId = -1;
    private String lastRenderedMessageKey = "";
    private boolean forceScrollToBottom;
    private Stage chatHeadStage;
    private final Set<Long> animatedMessageIds = new HashSet<>();
    private long lastTypingSentAt;
    private PauseTransition typingClearDelay;
    private ContextMenu mentionMenu;
    private TrayIcon trayIcon;
    private Conversation lastNativeNotificationConversation;
    private TargetDataLine recordingLine;
    private File recordingFile;
    private Button voiceButton;
    private Clip activeAudioClip;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        stage = primaryStage;
        stage.setTitle("Chat nội bộ công ty");
        showLogin();
    }

    @Override
    public void stop() {
        if (realtimeClient != null) {
            realtimeClient.close();
        }
        dbExecutor.shutdownNow();
        database.close();
        hideChatHead();
        removeTrayIcon();
    }

    private void showLogin() {
        Label logo = new Label("C");
        logo.getStyleClass().add("login-logo");
        Label title = new Label("Chat nội bộ");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Đăng nhập bằng tài khoản công ty");
        subtitle.getStyleClass().add("login-subtitle");

        TextField username = new TextField();
        username.setPromptText("Tên tài khoản");
        username.getStyleClass().add("login-input");
        PasswordField password = new PasswordField();
        password.setPromptText("Mật khẩu");
        password.getStyleClass().add("login-input");

        Label status = new Label();
        status.getStyleClass().add("error-text");
        status.setWrapText(true);

        Button login = new Button("Đăng nhập");
        login.getStyleClass().add("primary-button");
        login.setMaxWidth(Double.MAX_VALUE);
        Button forgotPassword = new Button("Quên mật khẩu?");
        forgotPassword.getStyleClass().add("header-button");
        forgotPassword.setMaxWidth(Double.MAX_VALUE);
        forgotPassword.setOnAction(e -> showForgotPasswordDialog(username.getText().trim(), status));

        Runnable doLogin = () -> {
            status.setText("");
            login.setDisable(true);
            runDb(() -> {
                new SchemaManager(database).init();
                currentUser = authService.login(username.getText().trim(), password.getText());
                if (currentUser == null) {
                    auditLogService.log(username.getText().trim(), "LOGIN_FAILED", "USER", username.getText().trim(), "Invalid credentials or employee not approved");
                    return false;
                }
                return true;
            }, ok -> {
                login.setDisable(false);
                if (!ok) {
                    status.setText("Sai tài khoản/mật khẩu hoặc tài khoản nhân viên chưa được duyệt.");
                    return;
                }
                handlePostPasswordLogin(status);
            }, e -> {
                login.setDisable(false);
                status.setText("Không thể kết nối: " + e.getMessage());
            });
        };

        login.setOnAction(e -> doLogin.run());
        password.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                doLogin.run();
            }
        });

        VBox card = new VBox(16, logo, title, subtitle, username, password, login, forgotPassword, status);
        card.getStyleClass().add("login-card");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(420);

        StackPane root = new StackPane(card);
        root.getStyleClass().add("login-root");
        Scene scene = new Scene(root, 980, 680);
        applyCss(scene);
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.show();
    }

    private void handlePostPasswordLogin(Label status) {
        if (currentUser != null && currentUser.isAdmin()) {
            runDb(() -> authService.twoFactorEnabled(currentUser.username), enabled -> {
                if (enabled) {
                    verifyAdminTwoFactor(status);
                } else {
                    setupAdminTwoFactor(status);
                }
            }, e -> status.setText("Không kiểm tra được 2FA: " + e.getMessage()));
            return;
        }
        finishLogin(status);
    }

    private void setupAdminTwoFactor(Label status) {
        String secret = securityService.generateTotpSecret();
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Thiết lập 2FA Admin");
        styleDialog(dialog);
        ButtonType verify = new ButtonType("Xác nhận", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(verify, ButtonType.CANCEL);
        TextField code = new TextField();
        code.getStyleClass().add("login-input");
        String currentCode = securityService.currentTotp(secret);
        VBox content = new VBox(10,
                label("Lưu secret này vào ứng dụng OTP:", "dialog-label"),
                settingLine("Secret", secret),
                settingLine("Mã hiện tại", currentCode),
                label("Nhập mã OTP để bật 2FA:", "dialog-label"),
                code);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == verify ? code.getText() : null);
        dialog.showAndWait().ifPresentOrElse(value -> {
            if (!securityService.verifyTotp(secret, value)) {
                status.setText("Mã 2FA không đúng.");
                auditLogService.log(currentUser.username, "2FA_SETUP_FAILED", "USER", currentUser.username, "");
                return;
            }
            runDb(() -> {
                authService.saveTwoFactorSecret(currentUser.username, secret);
                auditLogService.log(currentUser.username, "2FA_ENABLED", "USER", currentUser.username, "");
                return true;
            }, ok -> finishLogin(status), e -> status.setText("Không lưu được 2FA: " + e.getMessage()));
        }, () -> status.setText("Admin cần bật 2FA để đăng nhập."));
    }

    private void verifyAdminTwoFactor(Label status) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Xác thực 2FA");
        styleDialog(dialog);
        ButtonType verify = new ButtonType("Xác nhận", ButtonBar.ButtonData.OK_DONE);
        ButtonType forgot = new ButtonType("Quên mã 2FA?", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(forgot, verify, ButtonType.CANCEL);
        TextField code = new TextField();
        code.getStyleClass().add("login-input");
        dialog.getDialogPane().setContent(new VBox(10, label("Nhập mã OTP admin", "dialog-label"), code));
        Node forgotButton = dialog.getDialogPane().lookupButton(forgot);
        forgotButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            showTwoFactorResetDialog(status);
        });
        dialog.setResultConverter(btn -> btn == verify ? code.getText() : null);
        dialog.showAndWait().ifPresentOrElse(value -> runDb(() -> {
            String secret = authService.twoFactorSecret(currentUser.username);
            return securityService.verifyTotp(secret, value);
        }, ok -> {
            if (ok) {
                auditLogService.log(currentUser.username, "2FA_VERIFIED", "USER", currentUser.username, "");
                finishLogin(status);
            } else {
                auditLogService.log(currentUser.username, "2FA_FAILED", "USER", currentUser.username, "");
                status.setText("Mã 2FA không đúng.");
            }
        }, e -> status.setText("Không xác thực được 2FA: " + e.getMessage())), () -> status.setText("Bạn đã hủy xác thực 2FA."));
    }

    private void showTwoFactorResetDialog(Label status) {
        Dialog<TwoFactorResetRequest> dialog = new Dialog<>();
        dialog.setTitle("Khôi phục 2FA Admin");
        styleDialog(dialog);
        ButtonType reset = new ButtonType("Reset 2FA", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL);

        TextField target = new TextField(currentUser == null ? "" : currentUser.username);
        target.setPromptText("Admin cần reset 2FA");
        target.getStyleClass().add("login-input");
        TextField confirmer = new TextField();
        confirmer.setPromptText("Admin xác nhận");
        confirmer.getStyleClass().add("login-input");
        PasswordField confirmerPassword = new PasswordField();
        confirmerPassword.setPromptText("Mật khẩu admin xác nhận");
        confirmerPassword.getStyleClass().add("login-input");

        VBox content = new VBox(10,
                label("Yêu cầu một admin khác xác nhận để reset 2FA.", "dialog-label"),
                target,
                confirmer,
                confirmerPassword,
                label("Sau khi reset, admin này phải thiết lập lại 2FA ở lần đăng nhập tiếp theo.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == reset
                ? new TwoFactorResetRequest(target.getText().trim(), confirmer.getText().trim(), confirmerPassword.getText())
                : null);
        dialog.showAndWait().ifPresent(request -> {
            if (request.targetUsername.isBlank() || request.confirmerUsername.isBlank() || request.confirmerPassword.isBlank()) {
                status.setText("Vui lòng nhập đủ tài khoản cần reset và admin xác nhận.");
                return;
            }
            runDb(() -> {
                if (!authService.isAdminUser(request.targetUsername)) {
                    throw new IllegalArgumentException("Tài khoản cần reset không phải admin.");
                }
                if (request.targetUsername.equalsIgnoreCase(request.confirmerUsername)) {
                    throw new IllegalArgumentException("Không thể tự reset 2FA của chính mình.");
                }
                if (!authService.hasOtherAdmin(request.targetUsername)) {
                    throw new IllegalArgumentException("Không có admin khác để xác nhận. Vui lòng liên hệ IT/quản trị database.");
                }
                if (!authService.verifyAdminCredentials(request.confirmerUsername, request.confirmerPassword)) {
                    throw new IllegalArgumentException("Admin xác nhận không hợp lệ hoặc sai mật khẩu.");
                }
                authService.resetTwoFactor(request.targetUsername);
                auditLogService.log(request.confirmerUsername, "2FA_RESET", "USER", request.targetUsername, "Reset by another admin");
                return true;
            }, ok -> status.setText("Đã reset 2FA. Admin cần thiết lập lại ở lần đăng nhập tiếp theo."),
                    e -> status.setText(e.getMessage()));
        });
    }

    private void showForgotPasswordDialog(String suggestedUsername, Label status) {
        Dialog<PasswordResetRequest> dialog = new Dialog<>();
        dialog.setTitle("Khôi phục mật khẩu");
        styleDialog(dialog);
        ButtonType reset = new ButtonType("Đặt lại mật khẩu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL);

        TextField target = new TextField(suggestedUsername == null ? "" : suggestedUsername);
        target.setPromptText("Tài khoản cần reset mật khẩu");
        target.getStyleClass().add("login-input");
        PasswordField newPassword = new PasswordField();
        newPassword.setPromptText("Mật khẩu mới");
        newPassword.getStyleClass().add("login-input");
        PasswordField confirmPassword = new PasswordField();
        confirmPassword.setPromptText("Nhập lại mật khẩu mới");
        confirmPassword.getStyleClass().add("login-input");
        TextField confirmer = new TextField();
        confirmer.setPromptText("Tài khoản admin xác nhận");
        confirmer.getStyleClass().add("login-input");
        PasswordField confirmerPassword = new PasswordField();
        confirmerPassword.setPromptText("Mật khẩu admin xác nhận");
        confirmerPassword.getStyleClass().add("login-input");

        VBox content = new VBox(10,
                label("Vì đây là app nội bộ, mật khẩu chỉ được reset khi có admin xác nhận.", "dialog-label"),
                target,
                newPassword,
                confirmPassword,
                confirmer,
                confirmerPassword,
                label("Nếu tài khoản cần reset là admin, phải dùng một admin khác để xác nhận.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == reset
                ? new PasswordResetRequest(
                        target.getText().trim(),
                        newPassword.getText(),
                        confirmPassword.getText(),
                        confirmer.getText().trim(),
                        confirmerPassword.getText())
                : null);
        dialog.showAndWait().ifPresent(request -> {
            if (request.targetUsername.isBlank()
                    || request.newPassword.isBlank()
                    || request.confirmPassword.isBlank()
                    || request.confirmerUsername.isBlank()
                    || request.confirmerPassword.isBlank()) {
                status.setText("Vui lòng nhập đủ tài khoản, mật khẩu mới và admin xác nhận.");
                return;
            }
            if (!request.newPassword.equals(request.confirmPassword)) {
                status.setText("Mật khẩu mới nhập lại không khớp.");
                return;
            }
            runDb(() -> {
                if (!authService.userExists(request.targetUsername)) {
                    throw new IllegalArgumentException("Không tìm thấy tài khoản cần reset.");
                }
                if (!authService.verifyAdminCredentials(request.confirmerUsername, request.confirmerPassword)) {
                    throw new IllegalArgumentException("Admin xác nhận không hợp lệ hoặc sai mật khẩu.");
                }
                if (authService.isAdminUser(request.targetUsername)
                        && request.targetUsername.equalsIgnoreCase(request.confirmerUsername)) {
                    throw new IllegalArgumentException("Không thể tự reset mật khẩu admin của chính mình.");
                }
                if (authService.isAdminUser(request.targetUsername)
                        && !authService.hasOtherAdmin(request.targetUsername)) {
                    throw new IllegalArgumentException("Không có admin khác để xác nhận. Vui lòng liên hệ IT/quản trị database.");
                }
                authService.resetPasswordByAdmin(request.targetUsername, request.newPassword);
                auditLogService.log(request.confirmerUsername, "PASSWORD_RESET", "USER", request.targetUsername, "Reset password from login screen");
                return true;
            }, ok -> status.setText("Đã đặt lại mật khẩu. Bạn có thể đăng nhập bằng mật khẩu mới."),
                    e -> status.setText(e.getMessage()));
        });
    }

    private void finishLogin(Label status) {
        runDb(() -> {
            chatService.cleanupOldMessages(config.retentionDays);
            chatService.ensureCompanyConversation(currentUser);
            companyUsers = chatService.listCompanyUsers(currentUser);
            sessionToken = securityService.createSession(currentUser);
            auditLogService.log(currentUser.username, "LOGIN_SUCCESS", "USER", currentUser.username, "");
            return true;
        }, ok -> showChat(), e -> status.setText("Không thể khởi tạo phiên chat: " + e.getMessage()));
    }

    private void showChat() {
        BorderPane root = new BorderPane();
        appRoot = root;
        root.getStyleClass().add("root");
        root.setCenter(buildWorkspace());

        Scene scene = new Scene(root, 1220, 780);
        applyCss(scene);
        applyPersonalization();
        installShortcuts(scene);
        stage.setScene(scene);
        stage.setMaximized(true);
        connectRealtime();
        loadConversationsAsync();
    }

    private Node buildWorkspace() {
        Node sidebar = buildSidebar();
        Node chatPane = buildChatPane();
        HBox workspace = new HBox(10, sidebar, chatPane);
        workspace.getStyleClass().add("workspace");
        HBox.setHgrow(chatPane, Priority.ALWAYS);
        return workspace;
    }

    private Node buildSidebar() {
        sidebarPanel = new SidebarPanel(currentUser, userSettings, conversations, visibleConversations, conversationIdsWithTasks, new SidebarPanel.Callbacks() {
            @Override
            public Node avatarNode(String displayName, String styleClass, String username) {
                return ChatApp.this.avatarNode(displayName, styleClass, username);
            }

            @Override
            public Node svgIcon(String iconName, double size) {
                return ChatApp.this.svgIcon(iconName, size);
            }

            @Override
            public void showAccountMenu(Button owner) {
                ChatApp.this.showAccountMenu(owner);
            }

            @Override
            public void showPresenceMenu(Button owner) {
                ChatApp.this.showPresenceMenu(owner);
            }

            @Override
            public String presenceText() {
                return ChatApp.this.presenceText();
            }

            @Override
            public void openDirectDialog() {
                ChatApp.this.openDirectDialog();
            }

            @Override
            public void openGroupDialog() {
                ChatApp.this.openGroupDialog(null);
            }

            @Override
            public void conversationSelected(Conversation conversation) {
                if (conversation != null && !loadingConversations) {
                    currentConversation = conversation;
                    hideChatHead();
                    forceScrollToBottom = true;
                    refreshMessages(true);
                    loadTasksAsync();
                }
            }

            @Override
            public void showHomePanel() {
                ChatApp.this.showHomePanel();
            }

            @Override
            public void showContactPanel() {
                ChatApp.this.showContactPanel();
            }

            @Override
            public void showMyTasksPanel() {
                ChatApp.this.showMyTasksPanel();
            }

            @Override
            public void showNotificationsPanel() {
                ChatApp.this.showNotificationsPanel();
            }

            @Override
            public void showCalendarPanel() {
                ChatApp.this.showCalendarPanel();
            }

            @Override
            public void showSettingsDialog() {
                ChatApp.this.showSettingsDialog();
            }

            @Override
            public void logoutAndExit() {
                ChatApp.this.logoutAndExit();
            }

            @Override
            public void showToast(String message) {
                ChatApp.this.showToast(message);
            }
        });
        conversationList = sidebarPanel.conversationList();
        conversationSearch = sidebarPanel.conversationSearch();
        return sidebarPanel.node();
    }

    private void refreshNavActiveStyles() {
        if (sidebarPanel != null) {
            sidebarPanel.setActive(activeNav);
        }
    }

    private void showSidebarPage(String title, String subtitle, Node content) {
        if (sidebarPanel != null) {
            sidebarPanel.showPage(title, subtitle, content);
        }
    }

    private void rebuildSidebarOnly() {
        activeNav = "CHAT";
        if (sidebarPanel != null) {
            sidebarPanel.showConversations();
            return;
        }
        refreshNavActiveStyles();
    }

    private void showHomePanel() {
        activeNav = "HOME";
        refreshNavActiveStyles();
        int unread = conversations.stream().mapToInt(c -> c.unreadCount).sum();
        runDb(() -> {
            List<ChatTask> tasks = taskService.listTasksForUser(currentUser);
            LocalDate today = LocalDate.now();
            int dueToday = (int) tasks.stream()
                    .filter(t -> t.deadline != null && !"DONE".equals(t.status))
                    .filter(t -> t.deadline.toLocalDate().equals(today))
                    .count();
            return new HomeSummary(
                    unread,
                    taskService.countOverdueTasks(currentUser),
                    chatService.listMentionsForUser(currentUser).size(),
                    chatService.countPendingWorkflows(currentUser),
                    dueToday);
        }, summary -> {
            if (summary != null) {
                Node content = HomePanel.build(summary, this::openFirstUnreadConversation, this::showMyTasksPanel, this::showNotificationsPanel, this::showCalendarPanel);
                showSidebarPage("Tổng quan hôm nay", "Các việc cần chú ý ngay", content);
                return;
            }
            VBox metrics = new VBox(10,
                    settingLine("Tin chưa đọc", String.valueOf(summary.unread())),
                    settingLine("Task quá hạn", String.valueOf(summary.overdueTasks())),
                    settingLine("Workflow chờ duyệt", String.valueOf(summary.pendingWorkflows())),
                    settingLine("Deadline hôm nay", String.valueOf(summary.dueToday())),
                    settingLine("Nhắc đến tôi", String.valueOf(summary.mentions())));
            metrics.getStyleClass().add("settings-section");

            Button openUnread = new Button("Mở chat chưa đọc");
            openUnread.getStyleClass().add("sidebar-action");
            openUnread.setGraphic(svgIcon("chat", 15));
            openUnread.setOnAction(e -> openFirstUnreadConversation());

            Button myTasks = new Button("Việc của tôi");
            myTasks.getStyleClass().add("sidebar-action");
            myTasks.setGraphic(svgIcon("tasks", 15));
            myTasks.setOnAction(e -> showMyTasksPanel());

            Button workflows = new Button("Workflow chờ duyệt");
            workflows.getStyleClass().add("sidebar-action");
            workflows.setGraphic(svgIcon("bell", 15));
            workflows.setOnAction(e -> showNotificationsPanel());

            Button calendar = new Button("Lịch tuần này");
            calendar.getStyleClass().add("sidebar-action");
            calendar.setGraphic(svgIcon("calendar", 15));
            calendar.setOnAction(e -> showCalendarPanel());

            for (Button button : List.of(openUnread, myTasks, workflows, calendar)) {
                button.setMaxWidth(Double.MAX_VALUE);
                installHoverScale(button, 1.02);
            }

            VBox quickActions = new VBox(10, openUnread, myTasks, workflows, calendar);
            quickActions.getStyleClass().add("settings-section");

            VBox content = new VBox(12, metrics, quickActions);
            content.getStyleClass().add("sidebar-page-list");
            if (summary.unread() == 0 && summary.overdueTasks() == 0 && summary.pendingWorkflows() == 0 && summary.dueToday() == 0 && summary.mentions() == 0) {
                content.getChildren().add(emptyState("Hôm nay khá yên tĩnh", "Không có tin chưa đọc, task quá hạn hoặc deadline cần xử lý ngay."));
            }
            showSidebarPage("Tổng quan hôm nay", "Các việc cần chú ý ngay", content);
        }, e -> showError("Không tải được tổng quan", e));
    }

    private void openFirstUnreadConversation() {
        conversations.stream()
                .filter(c -> c.unreadCount > 0)
                .findFirst()
                .ifPresentOrElse(conversation -> selectConversation(conversation.id), () -> showInfo("Không có hội thoại chưa đọc."));
    }

    private void showContactPanel() {
        activeNav = "CONTACT";
        refreshNavActiveStyles();
        runDb(() -> chatService.listCompanyUsers(currentUser), users -> {
            ObservableList<ChatUser> visibleUsers = FXCollections.observableArrayList(users);
            TextField search = new TextField();
            search.setPromptText("Tìm tên, tài khoản, chức vụ");
            search.getStyleClass().add("dialog-search");
            ListView<ChatUser> list = new ListView<>(visibleUsers);
            list.getStyleClass().add("dialog-list");
            list.setCellFactory(view -> new SidebarCells.UserCell(this::presenceText));
            search.textProperty().addListener((obs, old, value) -> {
                String q = Texts.normalize(value);
                visibleUsers.setAll(users.stream()
                        .filter(u -> q.isBlank()
                                || Texts.normalize(u.displayName).contains(q)
                                || Texts.normalize(u.username).contains(q)
                                || Texts.normalize(u.position).contains(q))
                        .collect(Collectors.toList()));
            });
            list.setOnMouseClicked(e -> {
                ChatUser selected = list.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    runDb(() -> chatService.openDirectConversation(currentUser, selected.username), id -> {
                        loadConversations();
                        selectConversation(id);
                    }, ex -> showError("Không mở được chat", ex));
                }
            });
            VBox content = new VBox(10, search, list);
            VBox.setVgrow(list, Priority.ALWAYS);
            showSidebarPage("Danh bạ", "Nhân viên trong công ty", content);
        }, e -> showError("Không tải được danh bạ", e));
    }

    private void showMyTasksPanel() {
        activeNav = "TASKS";
        refreshNavActiveStyles();
        runDb(() -> taskService.listTasksForUser(currentUser), tasks -> {
            TextField search = new TextField();
            search.setPromptText("Tìm task, người thực hiện");
            search.getStyleClass().add("dialog-search");
            VBox box = new VBox(10);
            box.getStyleClass().add("sidebar-page-list");
            Runnable render = () -> {
                String q = Texts.normalize(search.getText());
                List<ChatTask> filtered = tasks.stream()
                        .filter(t -> q.isBlank()
                                || Texts.normalize(t.title).contains(q)
                                || Texts.normalize(t.description).contains(q)
                                || Texts.normalize(t.assigneeName).contains(q)
                                || Texts.normalize(t.assigneeUsername).contains(q))
                        .collect(Collectors.toList());
                box.getChildren().clear();
                if (filtered.isEmpty()) {
                    box.getChildren().add(emptyState("Chưa có việc", "Các công việc được giao sẽ hiển thị tại đây."));
                } else {
                    for (ChatTask task : filtered) box.getChildren().add(taskCard(task));
                }
            };
            search.textProperty().addListener((obs, old, value) -> render.run());
            render.run();
            ScrollPane scroll = new ScrollPane(box);
            scroll.getStyleClass().add("task-scroll");
            scroll.setFitToWidth(true);
            VBox content = new VBox(10, search, scroll);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            showSidebarPage("Việc của tôi", "Tất cả công việc liên quan", content);
        }, e -> showError("Không tải được công việc", e));
    }

    private void showNotificationsPanel() {
        activeNav = "NOTIFICATIONS";
        refreshNavActiveStyles();
        runDb(() -> new NotificationSummary(
                taskService.countOverdueTasks(currentUser),
                chatService.listMentionsForUser(currentUser).size(),
                chatService.countPendingWorkflows(currentUser)), summary -> {
            VBox box = new VBox(10);
            box.getStyleClass().add("sidebar-page-list");
            int unread = conversations.stream().mapToInt(c -> c.unreadCount).sum();
            box.getChildren().add(settingLine("Tin chưa đọc", String.valueOf(unread)));
            box.getChildren().add(settingLine("Nhắc đến tôi", String.valueOf(summary.mentions)));
            box.getChildren().add(settingLine("Task quá hạn", String.valueOf(summary.overdueTasks)));
            box.getChildren().add(settingLine("Workflow chờ duyệt", String.valueOf(summary.pendingWorkflows)));
            if (unread == 0 && summary.mentions == 0 && summary.overdueTasks == 0 && summary.pendingWorkflows == 0) {
                box.getChildren().add(emptyState("Không có thông báo mới", "Các cập nhật quan trọng sẽ xuất hiện tại đây."));
            }
            showSidebarPage("Thông báo", "Tổng hợp cập nhật mới", box);
        }, e -> showError("Không tải được thông báo", e));
    }

    private void showCalendarPanel() {
        activeNav = "CALENDAR";
        refreshNavActiveStyles();
        runDb(() -> taskService.listTasksForUser(currentUser), tasks -> {
            VBox box = new VBox(10);
            box.getStyleClass().add("sidebar-page-list");
            LocalDate today = LocalDate.now();
            List<ChatTask> due = tasks.stream()
                    .filter(t -> t.deadline != null && !"DONE".equals(t.status))
                    .filter(t -> !t.deadline.toLocalDate().isAfter(today.plusDays(7)))
                    .collect(Collectors.toList());
            if (due.isEmpty()) {
                box.getChildren().add(emptyState("Lịch trống", "Không có deadline trong 7 ngày tới."));
            } else {
                for (ChatTask task : due) box.getChildren().add(taskCard(task));
            }
            ScrollPane scroll = new ScrollPane(box);
            scroll.getStyleClass().add("task-scroll");
            scroll.setFitToWidth(true);
            showSidebarPage("Lịch", "Deadline hôm nay và tuần này", scroll);
        }, e -> showError("Không tải được lịch", e));
    }

    private String presenceText() {
        return switch (userSettings.presenceStatus == null ? "ONLINE" : userSettings.presenceStatus) {
            case "BUSY" -> "Đang bận";
            case "DO_NOT_DISTURB" -> "Không làm phiền";
            case "AWAY" -> "Vắng mặt";
            default -> "Online";
        };
    }

    private void showPresenceMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        addPresenceItem(menu, "ONLINE", "Online");
        addPresenceItem(menu, "BUSY", "Đang bận");
        addPresenceItem(menu, "DO_NOT_DISTURB", "Không làm phiền");
        addPresenceItem(menu, "AWAY", "Vắng mặt");
        menu.show(owner, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private void addPresenceItem(ContextMenu menu, String status, String text) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> {
            userSettings.presenceStatus = status;
            userSettings.save();
            rebuildSidebarOnly();
        });
        menu.getItems().add(item);
    }

    private Node buildChatPane() {
        BorderPane pane = new BorderPane();
        pane.getStyleClass().add("chat-pane");

        headerAvatar = avatar("?", "header-avatar");
        headerTitle = new Label("Chọn hội thoại");
        headerTitle.getStyleClass().add("chat-title");
        headerDetail = new Label("Tin nhắn nội bộ công ty");
        headerDetail.getStyleClass().add("chat-subtitle");
        VBox titleBlock = new VBox(3, headerTitle, headerDetail);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        pinConversationButton = iconButton("pin", "Ghim hội thoại", "header-button");
        pinConversationButton.getStyleClass().add("header-button");
        pinConversationButton.setOnAction(e -> toggleConversationPin());
        manageGroupButton = new Button("Quản lý nhóm");
        manageGroupButton.getStyleClass().add("header-button");
        manageGroupButton.setText("");
        manageGroupButton.setGraphic(svgIcon("users", 17));
        manageGroupButton.setTooltip(new Tooltip("Quản lý nhóm"));
        installHoverScale(manageGroupButton, 1.05);
        manageGroupButton.setOnAction(e -> openGroupDialog(currentConversation));
        taskDrawerButton = iconButton("tasks", "Bảng công việc", "header-button");
        taskDrawerButton.setOnAction(e -> toggleTaskDrawer());
        exportConversationButton = iconButton("download", "Xuất lịch sử chat CSV", "header-button");
        exportConversationButton.setOnAction(e -> exportCurrentConversationCsv());

        HBox headerTop = new HBox(12, headerAvatar, titleBlock, pinConversationButton, manageGroupButton, taskDrawerButton, exportConversationButton);
        headerTop.setAlignment(Pos.CENTER_LEFT);

        messageSearch = new TextField();
        messageSearch.setPromptText("Tìm trong cuộc trò chuyện");
        messageSearch.getStyleClass().add("message-search");
        installFocusScale(messageSearch);
        messageSearch.textProperty().addListener((obs, old, value) -> refreshMessages(true));
        advancedSearchButton = iconButton("filter", "Tìm kiếm nâng cao", "header-button");
        advancedSearchButton.setOnAction(e -> showAdvancedMessageSearchDialog());
        HBox searchBar = new HBox(10, messageSearch, advancedSearchButton);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(messageSearch, Priority.ALWAYS);

        VBox header = new VBox(12, headerTop, searchBar);
        header.getStyleClass().add("chat-header");
        pane.setTop(header);

        messageBox = new VBox(12);
        messageBox.getStyleClass().add("message-box");
        messageScroll = new ScrollPane(messageBox);
        messageScroll.getStyleClass().add("message-scroll");
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setCenter(messageScroll);
        taskDrawer = new TaskDrawerPanel(this::hideTaskDrawer, this::updateTaskStatus,
                (name, username) -> avatarNode(name, "task-avatar", username));
        taskDrawer.setVisible(false);
        taskDrawer.setManaged(false);
        pane.setRight(taskDrawer);

        ComposerBar composerBar = new ComposerBar(new ComposerBar.Callbacks() {
            @Override
            public void applyIcon(Button button, String iconName) {
                ChatApp.this.applyIcon(button, iconName);
            }

            @Override
            public void showPlusMenu(Button owner) {
                ChatApp.this.showPlusMenu(owner);
            }

            @Override
            public void clearFiles() {
                selectedFiles.clear();
                updateAttachmentLabel();
            }

            @Override
            public void showEmojiMenu(Button owner) {
                ChatApp.this.showEmojiMenu(owner);
            }

            @Override
            public void toggleVoiceRecording() {
                ChatApp.this.toggleVoiceRecording();
            }

            @Override
            public void sendCurrentMessage() {
                ChatApp.this.sendCurrentMessage();
            }

            @Override
            public void publishTypingIfNeeded() {
                ChatApp.this.publishTypingIfNeeded();
            }

            @Override
            public void updateMentionSuggestions() {
                ChatApp.this.updateMentionSuggestions();
            }
        });
        input = composerBar.input();
        replyLabel = composerBar.replyLabel();
        attachmentLabel = composerBar.attachmentLabel();
        voiceButton = composerBar.voiceButton();
        pane.setBottom(composerBar.node());
        installDragDrop(pane);
        return pane;
    }

    private void toggleTaskDrawer() {
        if (taskDrawer == null) return;
        boolean show = !taskDrawer.isVisible();
        taskDrawer.setVisible(show);
        taskDrawer.setManaged(show);
        if (show) {
            loadTasksAsync();
        }
    }

    private void hideTaskDrawer() {
        if (taskDrawer == null) return;
        taskDrawer.setVisible(false);
        taskDrawer.setManaged(false);
    }

    private void loadTasksAsync() {
        if (currentConversation == null || taskDrawer == null) {
            currentTasks = new ArrayList<>();
            renderTasks();
            return;
        }
        long conversationId = currentConversation.id;
        runDb(() -> taskService.listTasksByConversation(currentUser, conversationId), tasks -> {
            if (currentConversation == null || currentConversation.id != conversationId) return;
            currentTasks = tasks;
            renderTasks();
        }, e -> showError("Không tải được bảng công việc", e));
    }

    private void renderTasks() {
        if (taskDrawer == null) return;
        taskDrawer.render(currentTasks);
    }

    private Node taskCard(ChatTask task) {
        Label title = new Label(task.title);
        title.getStyleClass().add("task-card-title");
        title.setWrapText(true);
        Node avatar = avatarNode(task.assigneeName, "task-avatar", task.assigneeUsername);
        Label assignee = new Label(task.assigneeName == null || task.assigneeName.isBlank() ? task.assigneeUsername : task.assigneeName);
        assignee.getStyleClass().add("task-assignee");
        HBox assigneeRow = new HBox(8, avatar, assignee);
        assigneeRow.setAlignment(Pos.CENTER_LEFT);
        Label priority = new Label(task.priority);
        priority.getStyleClass().addAll("task-priority", "task-priority-" + task.priority.toLowerCase());
        Label status = new Label(taskStatusText(task.status));
        status.getStyleClass().add("task-kpi");
        Label deadline = new Label(taskDeadlineText(task));
        deadline.getStyleClass().add(isTaskOverdue(task) ? "task-deadline-danger" : "task-deadline");
        VBox card = new VBox(9, title, assigneeRow, new HBox(8, priority, status), deadline);
        card.getStyleClass().add("task-card");
        return card;
    }

    private void updateTaskStatus(ChatTask task, String status) {
        long conversationId = task.conversationId;
        runDb(() -> taskService.updateTaskStatus(currentUser, task.id, status), updated -> {
            loadTasksAsync();
            if ("DONE".equals(status)) {
                webhookService.notifyIntegrations("Task hoàn thành", task.title + " - " + task.assigneeUsername + " (+" + task.kpiPoints + " KPI)",
                        userSettings.slackWebhookUrl, userSettings.teamsWebhookUrl);
                refreshMessages(true);
                publishRealtime("MESSAGE_CREATED", conversationId);
            }
            publishRealtime("TASK_UPDATED", conversationId);
        }, e -> showError("Không cập nhật được công việc", e));
    }

    private boolean isTaskOverdue(ChatTask task) {
        return task.deadline != null && !"DONE".equals(task.status) && task.deadline.isBefore(LocalDateTime.now());
    }

    private String taskDeadlineText(ChatTask task) {
        if (task.deadline == null) return "Không có hạn chót";
        String prefix = isTaskOverdue(task) ? "Quá hạn: " : "Hạn: ";
        return prefix + task.deadline.format(TASK_TIME);
    }

    private static String taskStatusText(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "Đang làm";
            case "REVIEW" -> "Chờ duyệt";
            case "DONE" -> "Hoàn thành";
            default -> "Cần làm";
        };
    }

    private void loadConversations() {
        runDb(() -> chatService.listConversations(currentUser), loaded -> {
            long selectedId = currentConversation == null ? 0 : currentConversation.id;
            try {
                loadingConversations = true;
                conversations.setAll(loaded);
                loadConversationTaskMarkers();
                refreshConversationFilter();
                Conversation selected = loaded.stream()
                        .filter(c -> c.id == selectedId)
                        .findFirst()
                        .orElse(loaded.isEmpty() ? null : loaded.get(0));
                if (selected != null) {
                    if (conversationList != null) {
                        conversationList.getSelectionModel().select(selected);
                    }
                    currentConversation = selected;
                    loadTasksAsync();
                }
            } finally {
                loadingConversations = false;
            }
            int unread = loaded.stream().mapToInt(c -> c.unreadCount).sum();
            updateNavBadges(unread);
            if (lastUnreadTotal >= 0 && unread > lastUnreadTotal) {
                Conversation notifyConversation = loaded.stream()
                        .filter(c -> c.unreadCount > 0 && (currentConversation == null || c.id != currentConversation.id))
                        .findFirst()
                        .orElse(null);
                notifyNewMessage(notifyConversation);
            }
            lastUnreadTotal = unread;
            if (currentConversation != null) {
                refreshMessages(true);
            } else {
                showEmptyConversation();
            }
        }, e -> {
            showError("Lỗi tải hội thoại", e);
        });
    }

    private void loadConversationsAsync() {
        loadConversations();
    }

    private void refreshConversationFilter() {
        if (sidebarPanel != null) {
            sidebarPanel.refreshConversationFilter();
        }
    }

    private void loadConversationTaskMarkers() {
        runDb(() -> taskService.conversationIdsWithTasks(currentUser), ids -> {
            conversationIdsWithTasks.clear();
            conversationIdsWithTasks.addAll(ids);
            refreshConversationFilter();
        }, e -> {
        });
    }

    private void updateNavBadges(int unread) {
        runDb(() -> taskService.countOverdueTasks(currentUser), overdue -> {
            if (sidebarPanel != null) {
                sidebarPanel.refreshBadges(unread, overdue);
            }
        }, e -> {
            if (sidebarPanel != null) {
                sidebarPanel.refreshBadges(unread, 0);
            }
        });
    }

    private void refreshMessages() {
        refreshMessages(false);
    }

    private void refreshMessages(boolean forceRender) {
        if (currentConversation == null) {
            showEmptyConversation();
            return;
        }
        try {
            headerAvatar.setText(initials(currentConversation.title));
            headerTitle.setText(currentConversation.title);
            headerDetail.setText(conversationDescription(currentConversation));
            updateDirectPresenceHeader(currentConversation);
            manageGroupButton.setVisible(currentUser.canManageGroups() && "GROUP".equals(currentConversation.type));
            manageGroupButton.setManaged(manageGroupButton.isVisible());
            pinConversationButton.setText(currentConversation.pinned ? "Bỏ ghim" : "Ghim");
            pinConversationButton.setText("");
            pinConversationButton.setTooltip(new Tooltip(currentConversation.pinned ? "Bỏ ghim hội thoại" : "Ghim hội thoại"));
            pinConversationButton.setDisable(false);
            String search = messageSearch == null ? "" : Texts.safe(messageSearch.getText());
            long conversationId = currentConversation.id;
            boolean conversationChanged = lastRenderedConversationId != -1 && lastRenderedConversationId != conversationId;
            if (conversationChanged) {
                animateMessagePaneOut();
            }
            boolean shouldScrollToBottom = forceScrollToBottom || forceRender || isNearBottom();
            runDb(() -> chatService.listMessages(currentUser, conversationId, search), messages -> {
                if (currentConversation == null || currentConversation.id != conversationId) {
                    return;
                }
                currentMessages = messages;
                if (lastRenderedConversationId != conversationId) {
                    animatedMessageIds.clear();
                }
                if (currentMessages.isEmpty()) {
                    messageBox.getChildren().setAll(emptyState("Chưa có tin nhắn", "Hãy gửi lời chào để bắt đầu cuộc trò chuyện."));
                } else {
                    messageBox.getChildren().setAll(currentMessages.stream().map(this::messageNode).collect(Collectors.toList()));
                }
                lastRenderedConversationId = conversationId;
                lastRenderedMessageKey = search;
                if (conversationChanged) {
                    animateMessagePaneIn();
                }
                if (shouldScrollToBottom) {
                    Platform.runLater(() -> messageScroll.setVvalue(1.0));
                }
                forceScrollToBottom = false;
            }, e -> showError("Lỗi tải tin nhắn", e));
        } catch (Exception e) {
            showError("Lỗi tải tin nhắn", e);
        }
    }

    private boolean isNearBottom() {
        return messageScroll == null || messageScroll.getVvalue() > 0.92;
    }

    private void showEmptyConversation() {
        if (headerAvatar != null) {
            headerAvatar.setText("C");
            headerTitle.setText("Chọn hội thoại");
            headerDetail.setText("Tin nhắn, nhóm và file nội bộ sẽ hiển thị tại đây");
            pinConversationButton.setDisable(true);
            manageGroupButton.setVisible(false);
            manageGroupButton.setManaged(false);
            messageBox.getChildren().setAll(emptyState("Chưa chọn hội thoại", "Chọn một cuộc trò chuyện bên trái hoặc tạo chat mới."));
            currentTasks = new ArrayList<>();
            renderTasks();
        }
    }

    private Node messageNode(ChatMessage msg) {
        if (messageBubbleFactory == null) {
            messageBubbleFactory = createMessageBubbleFactory();
        }
        return messageBubbleFactory.create(msg);
    }

    private MessageBubbleFactory createMessageBubbleFactory() {
        return new MessageBubbleFactory(currentUser, userSettings, MESSAGE_TIME, new MessageBubbleFactory.Callbacks() {
            @Override
            public Node avatarNode(String displayName, String styleClass, String username) {
                return ChatApp.this.avatarNode(displayName, styleClass, username);
            }

            @Override
            public void applyIcon(Button button, String iconName) {
                ChatApp.this.applyIcon(button, iconName);
            }

            @Override
            public void animateMessageIfNew(Node node, long messageId) {
                ChatApp.this.animateMessageIfNew(node, messageId);
            }

            @Override
            public void showReactionDetails(ChatMessage msg) {
                ChatApp.this.showReactionDetails(msg);
            }

            @Override
            public void reply(ChatMessage msg) {
                replyToId = msg.id;
                replyLabel.setText("Đang trả lời: " + Texts.shortText(msg.recalled ? "Tin đã thu hồi" : msg.body, 80));
                replyLabel.setVisible(true);
                replyLabel.setManaged(true);
                input.requestFocus();
            }

            @Override
            public void edit(ChatMessage msg) {
                editMessage(msg);
            }

            @Override
            public void recall(ChatMessage msg) {
                recallMessage(msg);
            }

            @Override
            public void pin(ChatMessage msg) {
                pinMessage(msg);
            }

            @Override
            public void forward(ChatMessage msg) {
                forwardMessage(msg);
            }

            @Override
            public void react(ChatMessage msg, String emoji) {
                reactToMessage(msg, emoji);
            }

            @Override
            public void removeReaction(ChatMessage msg) {
                ChatApp.this.removeReaction(msg);
            }

            @Override
            public void createTask(ChatMessage msg) {
                createChatTaskFromMessage(msg);
            }

            @Override
            public void loadPollOptions(ChatMessage msg, Consumer<List<PollOption>> onSuccess, Consumer<Exception> onError) {
                runDb(() -> chatService.listPollOptions(currentUser, msg.id), onSuccess, onError);
            }

            @Override
            public void castPollVote(ChatMessage msg, PollOption option) {
                ChatApp.this.castPollVote(msg, option);
            }

            @Override
            public void showToast(String message) {
                ChatApp.this.showToast(message);
            }

            @Override
            public void openFile(File file) {
                ChatApp.this.openFile(file);
            }

            @Override
            public void playAudioFile(File file, Button playButton) {
                ChatApp.this.playAudioFile(file, playButton);
            }

            @Override
            public void openSalaryCard(ChatMessage msg) {
                ChatApp.this.openSalaryCard(msg);
            }

            @Override
            public void decideWorkflow(ChatMessage msg, boolean approved) {
                ChatApp.this.decideWorkflow(msg, approved);
            }
        });
    }

    private void showReactionDetails(ChatMessage msg) {
        runDb(() -> chatService.listReactionDetails(currentUser, msg.id), details -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Cảm xúc tin nhắn");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox box = new VBox(10);
            box.getStyleClass().add("settings-content");
            if (details.isEmpty()) {
                box.getChildren().add(emptyState("Chưa có cảm xúc", "Khi có người thả cảm xúc, danh sách sẽ hiển thị tại đây."));
            } else {
                for (ReactionDetail detail : details) {
                    box.getChildren().add(settingLine(detail.emoji + " " + detail.displayName, "@" + detail.username));
                }
            }
            ScrollPane scroll = new ScrollPane(box);
            scroll.setFitToWidth(true);
            scroll.setPrefSize(460, 420);
            scroll.getStyleClass().add("settings-scroll");
            dialog.getDialogPane().setContent(scroll);
            dialog.showAndWait();
        }, e -> showError("Không tải được danh sách cảm xúc", e));
    }

    private void reactToMessage(ChatMessage msg, String emoji) {
        runDb(() -> {
            chatService.addReaction(currentUser, msg.id, emoji);
            auditLogService.log(currentUser.username, "MESSAGE_REACTION_ADDED", "MESSAGE", msg.id, emoji);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("Không thêm được cảm xúc", e));
    }

    private void removeReaction(ChatMessage msg) {
        runDb(() -> {
            chatService.removeReaction(currentUser, msg.id);
            auditLogService.log(currentUser.username, "MESSAGE_REACTION_REMOVED", "MESSAGE", msg.id, "");
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("Không bỏ được cảm xúc", e));
    }

    private void createChatTaskFromMessage(ChatMessage msg) {
        long conversationId = msg.conversationId;
        runDb(() -> chatService.listMemberUsernames(conversationId), members -> {
            if (members.isEmpty()) {
                showInfo("Chưa có thành viên để giao việc.");
                return;
            }
            Dialog<TaskDraft> dialog = new Dialog<>();
            dialog.setTitle("Giao thành công việc (KPI Task)");
            styleDialog(dialog);
            ButtonType save = new ButtonType("Lưu task", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            String body = Texts.safe(msg.body);
            String defaultTitle = body.isBlank() ? "Công việc từ file/tin nhắn" : Texts.shortText(body, 70);
            TextField title = new TextField(defaultTitle);
            title.getStyleClass().add("dialog-search");
            TextArea description = new TextArea(body.isBlank() ? "Công việc từ file/tin nhắn" : body);
            description.getStyleClass().add("dialog-text-area");
            description.setWrapText(true);
            ComboBox<String> assignee = new ComboBox<>(FXCollections.observableArrayList(members));
            assignee.getStyleClass().add("dialog-search");
            assignee.setValue(members.contains(msg.senderUsername) ? msg.senderUsername : members.get(0));
            ComboBox<String> priority = new ComboBox<>(FXCollections.observableArrayList("LOW", "MEDIUM", "HIGH"));
            priority.getStyleClass().add("dialog-search");
            priority.setValue("MEDIUM");
            DatePicker deadline = new DatePicker(LocalDate.now().plusDays(1));
            TextField kpi = new TextField("0");
            kpi.getStyleClass().add("dialog-search");

            VBox content = new VBox(10,
                    label("Tên công việc", "dialog-label"), title,
                    label("Người thực hiện", "dialog-label"), assignee,
                    label("Mô tả công việc", "dialog-label"), description,
                    label("Độ ưu tiên", "dialog-label"), priority,
                    label("Deadline", "dialog-label"), deadline,
                    label("Điểm KPI", "dialog-label"), kpi);
            content.getStyleClass().add("dialog-content");
            dialog.getDialogPane().setContent(content);
            dialog.setResultConverter(btn -> {
                if (btn != save) return null;
                int points;
                try {
                    points = Integer.parseInt(Texts.safe(kpi.getText()).trim());
                } catch (NumberFormatException e) {
                    points = -1;
                }
                return new TaskDraft(title.getText(), description.getText(), assignee.getValue(), priority.getValue(), deadline.getValue(), points);
            });
            dialog.showAndWait().ifPresent(draft -> runDb(() -> taskService.createTask(
                    currentUser,
                    conversationId,
                    draft.title,
                    draft.description,
                    draft.assignee,
                    draft.priority,
                    draft.deadline == null ? null : draft.deadline.atTime(17, 0),
                    draft.kpiPoints), taskId -> {
                auditLogService.log(currentUser.username, "TASK_CREATED", "CHAT_TASK", taskId, "conversation=" + conversationId);
                loadTasksAsync();
                showInfo("Đã tạo KPI Task.");
                publishRealtime("TASK_UPDATED", conversationId);
            }, e -> showError("Không tạo được KPI Task", e)));
        }, e -> showError("Không tải được danh sách thành viên", e));
    }

    private record TaskDraft(String title, String description, String assignee, String priority, LocalDate deadline, int kpiPoints) {
    }

    private void createTaskFromMessage(ChatMessage msg) {
        runDb(() -> chatService.listTaskTargets(currentUser), targets -> {
            if (targets.isEmpty()) {
                showInfo("Chưa có nhân viên APPROVED để giao việc.");
                return;
            }
            Dialog<TaskTarget> dialog = new Dialog<>();
            dialog.setTitle("Giao KPI Task từ tin nhắn");
            styleDialog(dialog);
            ButtonType save = new ButtonType("Lưu task", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            ComboBox<TaskTarget> employee = new ComboBox<>(FXCollections.observableArrayList(targets));
            employee.getStyleClass().add("dialog-search");
            targets.stream()
                    .filter(t -> t.username != null && t.username.equals(msg.senderUsername))
                    .findFirst()
                    .ifPresent(employee::setValue);
            TextArea description = new TextArea(Texts.safe(msg.body).isBlank() ? "Công việc từ file/tin nhắn" : msg.body);
            description.getStyleClass().add("dialog-text-area");
            description.setWrapText(true);
            DatePicker deadline = new DatePicker(LocalDate.now().plusDays(1));
            VBox content = new VBox(10,
                    label("Người thực hiện", "dialog-label"), employee,
                    label("Mô tả công việc", "dialog-label"), description,
                    label("Deadline", "dialog-label"), deadline);
            content.getStyleClass().add("dialog-content");
            dialog.getDialogPane().setContent(content);
            dialog.setResultConverter(btn -> btn == save ? employee.getValue() : null);
            dialog.showAndWait().ifPresent(target -> runDb(() -> chatService.createTask(
                    currentUser,
                    msg.id,
                    target.employeeId,
                    description.getText(),
                    deadline.getValue()), taskId -> {
                showInfo("Đã tạo KPI Task.");
                publishRealtime("TASK_UPDATED", msg.conversationId);
            }, e -> showError("Không tạo được KPI Task", e)));
        }, e -> showError("Không tải được danh sách nhân viên", e));
    }

    private void forwardMessage(ChatMessage msg) {
        List<Conversation> targets = conversations.stream()
                .filter(c -> c.id != msg.conversationId)
                .collect(Collectors.toList());
        if (targets.isEmpty()) {
            showInfo("Chưa có hội thoại khác để chuyển tiếp.");
            return;
        }
        Dialog<Conversation> dialog = new Dialog<>();
        dialog.setTitle("Chuyển tiếp tin nhắn");
        styleDialog(dialog);
        ButtonType send = new ButtonType("Chuyển tiếp", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        ListView<Conversation> list = new ListView<>(FXCollections.observableArrayList(targets));
        list.getStyleClass().add("dialog-list");
        list.setCellFactory(view -> new SidebarCells.ConversationCell());
        list.setPrefSize(460, 360);
        VBox content = new VBox(10, label("Chọn hội thoại nhận tin:", "dialog-label"), list);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == send ? list.getSelectionModel().getSelectedItem() : null);
        dialog.showAndWait().ifPresent(target -> {
            String text = Texts.safe(msg.body).isBlank()
                    ? "Chuyển tiếp file từ " + msg.senderName
                    : "Chuyển tiếp: " + msg.body;
            runDb(() -> chatService.sendMessage(currentUser, target.id, text, null, List.of()), messageId -> {
                loadConversations();
                publishRealtime("MESSAGE_CREATED", target.id);
                showInfo("Đã chuyển tiếp tin nhắn.");
            }, e -> {
                showError("Không chuyển tiếp được tin", e);
            });
        });
    }

    private void castPollVote(ChatMessage msg, PollOption option) {
        runDb(() -> {
            chatService.castPollVote(currentUser, option.pollId, option.optionId);
            auditLogService.log(currentUser.username, "POLL_VOTED", "POLL", option.pollId, "message=" + msg.id);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("Không lưu được lựa chọn vote", e));
    }

    private void playAudioFile(File file, Button playButton) {
        try {
            if (activeAudioClip != null && activeAudioClip.isRunning()) {
                activeAudioClip.stop();
                activeAudioClip.close();
                activeAudioClip = null;
                playButton.setText("Phát");
                return;
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(file)) {
                Clip clip = AudioSystem.getClip();
                clip.open(stream);
                activeAudioClip = clip;
                playButton.setText("Dừng");
                clip.addLineListener(event -> {
                    if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                        Platform.runLater(() -> playButton.setText("Phát"));
                    }
                });
                clip.start();
            }
        } catch (Exception ex) {
            showError("Không phát được voice message", ex);
        }
    }

    private void decideWorkflow(ChatMessage msg, boolean approved) {
        runDb(() -> {
            return chatService.decideBusinessWorkflow(currentUser, msg.id, approved);
        }, synced -> {
            refreshMessages(true);
            publishRealtime("WORKFLOW_UPDATED", msg.conversationId);
            if (approved && !synced) {
                showInfo("Workflow đã được duyệt trong chat, nhưng chưa tìm thấy bảng schedules/timekeeping để đồng bộ chấm công.");
            }
        }, e -> showError("Không cập nhật được workflow", e));
    }

    private void openSalaryCard(ChatMessage msg) {
        PasswordField password = new PasswordField();
        password.setPromptText("Nhập lại mật khẩu");
        password.getStyleClass().add("login-input");
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Xác thực phiếu lương");
        styleDialog(dialog);
        ButtonType open = new ButtonType("Xem", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(open, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(10, label("Vui lòng nhập lại mật khẩu để xem phiếu lương.", "dialog-label"), password));
        dialog.setResultConverter(btn -> btn == open ? password.getText() : null);
        dialog.showAndWait().ifPresent(pass -> runDb(() -> {
            CurrentUser verified = authService.login(currentUser.username, pass);
            if (verified == null) {
                throw new IllegalArgumentException("Mật khẩu không đúng.");
            }
            return chatService.salaryDetail(currentUser, msg.metadataJson);
        }, detail -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, detail);
            styleDialog(alert);
            alert.setTitle("Chi tiết phiếu lương");
            alert.setHeaderText("Phiếu lương");
            alert.showAndWait();
        }, e -> showError("Không mở được phiếu lương", e)));
    }

    private void showWorkflowDialog(String type) {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước.");
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("OT".equals(type) ? "Xin tăng ca" : "Xin đổi ca");
        styleDialog(dialog);
        ButtonType send = new ButtonType("Gửi yêu cầu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        if ("LEAVE".equals(type)) {
            dialog.setTitle("Xin nghỉ phép");
        }
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        TextField shift = new TextField("OT".equals(type) ? "Tăng ca" : "Ca mới");
        if ("LEAVE".equals(type)) {
            shift.setText("Nghỉ phép");
        }
        shift.getStyleClass().add("dialog-search");
        VBox content = new VBox(10, label("Ngày làm việc", "dialog-label"), date, label("Ca/Nội dung", "dialog-label"), shift);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == send ? shift.getText() : null);
        dialog.showAndWait().ifPresent(value -> {
            long conversationId = currentConversation.id;
            runDb(() -> chatService.createBusinessWorkflow(currentUser, conversationId, type, date.getValue(), value), messageId -> {
                forceScrollToBottom = true;
                refreshMessages(true);
                loadConversations();
                publishRealtime("MESSAGE_CREATED", conversationId);
            }, e -> showError("Không gửi được workflow", e));
        });
    }

    private void sendCurrentMessage() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước.");
            return;
        }
        long conversationId = currentConversation.id;
        String body = input.getText();
        Long replyId = replyToId;
        List<File> files = new ArrayList<>(selectedFiles);
        runDb(() -> chatService.sendMessage(currentUser, conversationId, body, replyId, files), messageId -> {
            auditLogService.log(currentUser.username, files.isEmpty() ? "MESSAGE_SENT" : "MESSAGE_WITH_FILE_SENT", "MESSAGE", messageId, "conversation=" + conversationId);
            input.clear();
            selectedFiles.clear();
            replyToId = null;
            replyLabel.setText("");
            replyLabel.setVisible(false);
            replyLabel.setManaged(false);
            updateAttachmentLabel();
            forceScrollToBottom = true;
            refreshMessages(true);
            loadConversations();
            publishRealtime("MESSAGE_CREATED", conversationId);
        }, e -> {
            if (body != null && !body.isBlank() && canQueueFiles(files)) {
                pendingMessageService.add(currentUser.username, conversationId, body, files);
                input.clear();
                selectedFiles.clear();
                updateAttachmentLabel();
                showInfo("Không gửi được tin. App đã lưu vào hàng đợi offline và sẽ thử gửi lại sau.");
                return;
            }
            showError("Không gửi được tin", e);
        });
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file gửi kèm");
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            selectedFiles.addAll(files);
            updateAttachmentLabel();
        }
    }

    private void toggleVoiceRecording() {
        if (recordingLine != null && recordingLine.isOpen()) {
            stopVoiceRecording();
        } else {
            startVoiceRecording();
        }
    }

    private void startVoiceRecording() {
        try {
            AudioFormat format = new AudioFormat(16000.0f, 16, 1, true, false);
            TargetDataLine line = AudioSystem.getTargetDataLine(format);
            line.open(format);
            File dir = Files.createDirectories(config.filesRoot.resolve("voice-temp")).toFile();
            recordingFile = new File(dir, "voice-" + System.currentTimeMillis() + ".wav");
            recordingLine = line;
            line.start();
            if (voiceButton != null) {
                voiceButton.getStyleClass().add("recording-button");
                voiceButton.setTooltip(new Tooltip("Bấm để dừng ghi âm"));
            }
            Thread writer = new Thread(() -> {
                try (AudioInputStream stream = new AudioInputStream(line)) {
                    AudioSystem.write(stream, AudioFileFormat.Type.WAVE, recordingFile);
                } catch (Exception e) {
                    AppLog.warn("Không ghi được voice message.", e);
                }
            }, "chat-voice-recorder");
            writer.setDaemon(true);
            writer.start();
            showToast("Đang ghi âm...");
        } catch (Exception e) {
            recordingLine = null;
            recordingFile = null;
            showError("Không bắt đầu ghi âm được", e);
        }
    }

    private void stopVoiceRecording() {
        try {
            if (recordingLine != null) {
                recordingLine.stop();
                recordingLine.close();
            }
            if (recordingFile != null && recordingFile.exists() && recordingFile.length() > 0) {
                selectedFiles.add(recordingFile);
                updateAttachmentLabel();
                showToast("Đã thêm voice message vào tin nhắn.");
            }
        } catch (Exception e) {
            showError("Không dừng ghi âm được", e);
        } finally {
            recordingLine = null;
            recordingFile = null;
            if (voiceButton != null) {
                voiceButton.getStyleClass().remove("recording-button");
                voiceButton.setTooltip(new Tooltip("Ghi âm voice message"));
            }
        }
    }

    private void showPlusMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        MenuItem files = new MenuItem("Gửi file");
        files.setOnAction(e -> chooseFiles());
        MenuItem media = new MenuItem("Gửi ảnh/video");
        media.setOnAction(e -> chooseMediaFiles());
        MenuItem vote = new MenuItem("Tạo vote");
        vote.setOnAction(e -> showVoteDialog());
        MenuItem scheduled = new MenuItem("Hẹn giờ gửi");
        scheduled.setOnAction(e -> showScheduledMessageDialog());
        MenuItem scheduledCenter = new MenuItem("Quản lý tin hẹn giờ");
        scheduledCenter.setOnAction(e -> showScheduledCenterDialog());
        MenuItem reminder = new MenuItem("Tạo nhắc việc");
        reminder.setOnAction(e -> showReminderDialog(null));
        MenuItem reminderCenter = new MenuItem("Quản lý nhắc việc");
        reminderCenter.setOnAction(e -> showReminderCenterDialog());
        MenuItem mentions = new MenuItem("Tin nhắc đến tôi");
        mentions.setOnAction(e -> showMentionsDialog());
        MenuItem exportHtml = new MenuItem("Xuất hội thoại HTML");
        exportHtml.setOnAction(e -> exportCurrentConversationHtml());
        MenuItem ot = new MenuItem("Xin tăng ca");
        ot.setOnAction(e -> showWorkflowDialog("OT"));
        MenuItem shift = new MenuItem("Xin đổi ca");
        shift.setOnAction(e -> showWorkflowDialog("SHIFT_CHANGE"));
        MenuItem leave = new MenuItem("Xin nghỉ phép");
        leave.setOnAction(e -> showWorkflowDialog("LEAVE"));
        menu.getItems().addAll(files, media, vote, scheduled, scheduledCenter, reminder, reminderCenter, mentions, exportHtml, new SeparatorMenuItem(), ot, shift, leave);
        menu.show(owner, javafx.geometry.Side.TOP, 0, -8);
    }

    private void chooseMediaFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh hoặc video");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Ảnh và video", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.mp4", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"),
                new FileChooser.ExtensionFilter("Video", "*.mp4", "*.mov", "*.avi", "*.mkv"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            selectedFiles.addAll(files);
            updateAttachmentLabel();
        }
    }

    private void showScheduledMessageDialog() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước khi hẹn giờ gửi.");
            return;
        }
        Dialog<ScheduledDraft> dialog = new Dialog<>();
        dialog.setTitle("Hẹn giờ gửi tin");
        styleDialog(dialog);
        ButtonType schedule = new ButtonType("Hẹn giờ", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(schedule, ButtonType.CANCEL);

        TextArea body = new TextArea(input.getText());
        body.setPromptText("Nội dung tin nhắn");
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        TextField time = new TextField("08:00");
        time.setPromptText("HH:mm");
        time.getStyleClass().add("dialog-search");

        VBox content = new VBox(10,
                label("Nội dung", "dialog-label"), body,
                label("Ngày gửi", "dialog-label"), date,
                label("Giờ gửi", "dialog-label"), time,
                label("Server realtime sẽ tự gửi khi đến hạn.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != schedule) {
                return null;
            }
            try {
                return new ScheduledDraft(body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception ex) {
                showInfo("Giờ gửi phải đúng định dạng HH:mm.");
                return null;
            }
        });
        dialog.showAndWait().ifPresent(draft -> {
            long conversationId = currentConversation.id;
            runDb(() -> {
                long id = chatService.scheduleMessage(currentUser, conversationId, draft.body, draft.scheduledAt);
                auditLogService.log(currentUser.username, "SCHEDULED_MESSAGE_CREATED", "SCHEDULED_MESSAGE", id, "conversation=" + conversationId);
                return id;
            }, id -> {
                input.clear();
                showInfo("Đã hẹn giờ gửi tin.");
                publishRealtime("CONVERSATION_UPDATED", conversationId);
            }, e -> showError("Không hẹn giờ gửi được", e));
        });
    }

    private void showScheduledCenterDialog() {
        runDb(() -> chatService.listScheduledMessages(currentUser), items -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Quản lý tin hẹn giờ");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox list = new VBox(10);
            list.getStyleClass().add("sidebar-page-list");
            if (items.isEmpty()) {
                list.getChildren().add(emptyState("Chưa có tin hẹn giờ", "Các tin đã hẹn sẽ hiển thị tại đây."));
            } else {
                for (ScheduledMessage item : items) {
                    list.getChildren().add(scheduledMessageCard(item, dialog));
                }
            }
            ScrollPane scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.setPrefSize(620, 520);
            scroll.getStyleClass().add("settings-scroll");
            dialog.getDialogPane().setContent(scroll);
            dialog.showAndWait();
        }, e -> showError("Không tải được tin hẹn giờ", e));
    }

    private Node scheduledMessageCard(ScheduledMessage item, Dialog<?> owner) {
        Label title = new Label((item.conversationTitle == null ? "Hội thoại" : item.conversationTitle) + " - " + item.status);
        title.getStyleClass().add("task-card-title");
        Label time = new Label("Gửi lúc: " + (item.scheduledAt == null ? "" : item.scheduledAt.format(TASK_TIME)));
        time.getStyleClass().add("task-deadline");
        Label body = new Label(Texts.shortText(item.body, 140));
        body.getStyleClass().add("conversation-last");
        body.setWrapText(true);
        Button edit = new Button("Sửa");
        edit.getStyleClass().add("header-button");
        edit.setDisable(!"PENDING".equalsIgnoreCase(item.status));
        edit.setOnAction(e -> {
            owner.close();
            showEditScheduledDialog(item);
        });
        Button cancel = new Button("Hủy");
        cancel.getStyleClass().add("header-button");
        cancel.setDisable(!"PENDING".equalsIgnoreCase(item.status));
        cancel.setOnAction(e -> runDb(() -> {
            chatService.cancelScheduledMessage(currentUser, item.id);
            auditLogService.log(currentUser.username, "SCHEDULED_MESSAGE_CANCELLED", "SCHEDULED_MESSAGE", item.id, "");
            return true;
        }, ok -> showScheduledCenterDialog(), ex -> showError("Không hủy được tin hẹn giờ", ex)));
        HBox actions = new HBox(8, edit, cancel);
        VBox card = new VBox(8, title, time, body, actions);
        card.getStyleClass().add("task-card");
        return card;
    }

    private void showEditScheduledDialog(ScheduledMessage item) {
        Dialog<ScheduledDraft> dialog = new Dialog<>();
        dialog.setTitle("Sửa tin hẹn giờ");
        styleDialog(dialog);
        ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextArea body = new TextArea(item.body);
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(item.scheduledAt == null ? LocalDate.now().plusDays(1) : item.scheduledAt.toLocalDate());
        TextField time = new TextField(item.scheduledAt == null ? "08:00" : item.scheduledAt.toLocalTime().withSecond(0).withNano(0).toString());
        time.getStyleClass().add("dialog-search");
        VBox content = new VBox(10, label("Nội dung", "dialog-label"), body, label("Ngày gửi", "dialog-label"), date, label("Giờ gửi", "dialog-label"), time);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != save) return null;
            try {
                return new ScheduledDraft(body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception e) {
                showInfo("Giờ gửi phải đúng định dạng HH:mm.");
                return null;
            }
        });
        dialog.showAndWait().ifPresent(draft -> runDb(() -> {
            chatService.updateScheduledMessage(currentUser, item.id, draft.body, draft.scheduledAt);
            auditLogService.log(currentUser.username, "SCHEDULED_MESSAGE_UPDATED", "SCHEDULED_MESSAGE", item.id, "");
            return true;
        }, ok -> showScheduledCenterDialog(), e -> showError("Không sửa được tin hẹn giờ", e)));
    }

    private void showReminderDialog(ChatReminder edit) {
        Dialog<ReminderDraft> dialog = new Dialog<>();
        dialog.setTitle(edit == null ? "Tạo nhắc việc" : "Sửa nhắc việc");
        styleDialog(dialog);
        ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField title = new TextField(edit == null ? "" : edit.title);
        title.setPromptText("Tiêu đề");
        title.getStyleClass().add("dialog-search");
        TextArea body = new TextArea(edit == null ? "" : Texts.safe(edit.body));
        body.setPromptText("Nội dung nhắc");
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(edit == null || edit.remindAt == null ? LocalDate.now().plusDays(1) : edit.remindAt.toLocalDate());
        TextField time = new TextField(edit == null || edit.remindAt == null ? "08:00" : edit.remindAt.toLocalTime().withSecond(0).withNano(0).toString());
        time.getStyleClass().add("dialog-search");
        CheckBox conversationReminder = new CheckBox("Gửi nhắc vào hội thoại hiện tại");
        conversationReminder.getStyleClass().add("settings-check");
        conversationReminder.setSelected(currentConversation != null && (edit == null || edit.conversationId != null));
        VBox content = new VBox(10, label("Tiêu đề", "dialog-label"), title, label("Nội dung", "dialog-label"), body,
                label("Ngày nhắc", "dialog-label"), date, label("Giờ nhắc", "dialog-label"), time, conversationReminder);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != save) return null;
            try {
                Long conversationId = conversationReminder.isSelected() && currentConversation != null ? currentConversation.id : null;
                return new ReminderDraft(conversationId, title.getText(), body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception e) {
                showInfo("Giờ nhắc phải đúng định dạng HH:mm.");
                return null;
            }
        });
        dialog.showAndWait().ifPresent(draft -> runDb(() -> {
            if (edit == null) {
                long id = chatService.createReminder(currentUser, draft.conversationId, draft.title, draft.body, draft.remindAt);
                auditLogService.log(currentUser.username, "REMINDER_CREATED", "REMINDER", id, "");
            } else {
                chatService.updateReminder(currentUser, edit.id, draft.title, draft.body, draft.remindAt);
                auditLogService.log(currentUser.username, "REMINDER_UPDATED", "REMINDER", edit.id, "");
            }
            return true;
        }, ok -> showReminderCenterDialog(), e -> showError("Không lưu được nhắc việc", e)));
    }

    private void showReminderCenterDialog() {
        runDb(() -> chatService.listReminders(currentUser), reminders -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Quản lý nhắc việc");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Button add = new Button("Tạo nhắc việc");
            add.getStyleClass().add("header-button");
            add.setOnAction(e -> {
                dialog.close();
                showReminderDialog(null);
            });
            VBox list = new VBox(10, add);
            list.getStyleClass().add("sidebar-page-list");
            if (reminders.isEmpty()) {
                list.getChildren().add(emptyState("Chưa có nhắc việc", "Các nhắc việc sẽ hiển thị tại đây."));
            } else {
                for (ChatReminder reminder : reminders) {
                    list.getChildren().add(reminderCard(reminder, dialog));
                }
            }
            ScrollPane scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.setPrefSize(620, 520);
            scroll.getStyleClass().add("settings-scroll");
            dialog.getDialogPane().setContent(scroll);
            dialog.showAndWait();
        }, e -> showError("Không tải được nhắc việc", e));
    }

    private Node reminderCard(ChatReminder reminder, Dialog<?> owner) {
        Label title = new Label(reminder.title + " - " + reminder.status);
        title.getStyleClass().add("task-card-title");
        Label target = new Label(reminder.conversationTitle == null ? "Nhắc cá nhân" : "Hội thoại: " + reminder.conversationTitle);
        target.getStyleClass().add("conversation-last");
        Label time = new Label("Nhắc lúc: " + (reminder.remindAt == null ? "" : reminder.remindAt.format(TASK_TIME)));
        time.getStyleClass().add("task-deadline");
        Label body = new Label(Texts.shortText(reminder.body, 140));
        body.setWrapText(true);
        body.getStyleClass().add("conversation-last");
        Button edit = new Button("Sửa");
        edit.getStyleClass().add("header-button");
        edit.setDisable(!"PENDING".equalsIgnoreCase(reminder.status));
        edit.setOnAction(e -> {
            owner.close();
            showReminderDialog(reminder);
        });
        Button cancel = new Button("Hủy");
        cancel.getStyleClass().add("header-button");
        cancel.setDisable(!"PENDING".equalsIgnoreCase(reminder.status));
        cancel.setOnAction(e -> runDb(() -> {
            chatService.cancelReminder(currentUser, reminder.id);
            auditLogService.log(currentUser.username, "REMINDER_CANCELLED", "REMINDER", reminder.id, "");
            return true;
        }, ok -> showReminderCenterDialog(), ex -> showError("Không hủy được nhắc việc", ex)));
        VBox card = new VBox(8, title, target, time, body, new HBox(8, edit, cancel));
        card.getStyleClass().add("task-card");
        return card;
    }

    private void showMentionsDialog() {
        runDb(() -> chatService.listMentionsForUser(currentUser), mentions -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Tin nhắc đến tôi");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox list = new VBox(10);
            list.getStyleClass().add("sidebar-page-list");
            if (mentions.isEmpty()) {
                list.getChildren().add(emptyState("Chưa có mention", "Khi ai đó @ bạn, tin sẽ hiện ở đây."));
            } else {
                for (MentionItem mention : mentions) {
                    list.getChildren().add(mentionCard(mention, dialog));
                }
            }
            ScrollPane scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.setPrefSize(620, 520);
            scroll.getStyleClass().add("settings-scroll");
            dialog.getDialogPane().setContent(scroll);
            dialog.showAndWait();
        }, e -> showError("Không tải được danh sách mention", e));
    }

    private Node mentionCard(MentionItem mention, Dialog<?> owner) {
        Label title = new Label((mention.conversationTitle == null ? "Hội thoại" : mention.conversationTitle) + " - " + mention.senderName);
        title.getStyleClass().add("task-card-title");
        Label body = new Label(Texts.shortText(mention.body, 140));
        body.getStyleClass().add("conversation-last");
        body.setWrapText(true);
        Label time = new Label(mention.createdAt == null ? "" : mention.createdAt.format(TASK_TIME));
        time.getStyleClass().add("task-deadline");
        VBox card = new VBox(8, title, body, time);
        card.getStyleClass().add("task-card");
        card.setOnMouseClicked(e -> {
            owner.close();
            loadConversations();
            selectConversation(mention.conversationId);
        });
        return card;
    }

    private void showVoteDialog() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước khi tạo vote.");
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Tạo vote");
        styleDialog(dialog);
        ButtonType create = new ButtonType("Tạo vote", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        TextField question = new TextField();
        question.setPromptText("Câu hỏi bình chọn");
        question.getStyleClass().add("dialog-search");
        TextArea options = new TextArea("Đồng ý\nKhông đồng ý");
        options.setPromptText("Mỗi lựa chọn trên một dòng");
        options.getStyleClass().add("dialog-text-area");
        options.setWrapText(true);
        VBox content = new VBox(10,
                label("Câu hỏi", "dialog-label"),
                question,
                label("Lựa chọn", "dialog-label"),
                options,
                label("Vote V1 chỉ hiển thị lựa chọn trong chat, chưa lưu kết quả lâu dài.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != create) {
                return null;
            }
            List<String> choices = options.getText().lines()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .collect(Collectors.toList());
            if (question.getText().trim().isBlank() || choices.size() < 2) {
                return null;
            }
            return question.getText().trim() + "\n" + String.join("\n", choices);
        });
        dialog.showAndWait().ifPresent(vote -> {
            long conversationId = currentConversation.id;
            List<String> lines = vote.lines().collect(Collectors.toList());
            String pollQuestion = lines.isEmpty() ? "" : lines.get(0);
            List<String> pollOptions = lines.size() <= 1 ? List.of() : lines.subList(1, lines.size());
            runDb(() -> chatService.createPollMessage(currentUser, conversationId, pollQuestion, pollOptions), messageId -> {
                auditLogService.log(currentUser.username, "POLL_CREATED", "MESSAGE", messageId, "conversation=" + conversationId);
                forceScrollToBottom = true;
                refreshMessages(true);
                loadConversations();
                publishRealtime("MESSAGE_CREATED", conversationId);
            }, e -> {
                showError("Không tạo được vote", e);
            });
        });
    }

    private void updateAttachmentLabel() {
        if (selectedFiles.isEmpty()) {
            attachmentLabel.setText("");
            attachmentLabel.setVisible(false);
            attachmentLabel.setManaged(false);
            return;
        }
        attachmentLabel.setText("Đính kèm: " + selectedFiles.stream().map(File::getName).collect(Collectors.joining(", ")));
        attachmentLabel.setVisible(true);
        attachmentLabel.setManaged(true);
    }

    private void openDirectDialog() {
        try {
            companyUsers = chatService.listCompanyUsers(currentUser);
            ObservableList<ChatUser> visibleUsers = FXCollections.observableArrayList();
            List<ChatUser> candidates = companyUsers.stream()
                    .filter(u -> !u.username.equals(currentUser.username))
                    .collect(Collectors.toList());

            Dialog<ChatUser> dialog = new Dialog<>();
            dialog.setTitle("Chat 1-1");
            styleDialog(dialog);
            ButtonType open = new ButtonType("Mở chat", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(open, ButtonType.CANCEL);

            TextField search = new TextField();
            search.setPromptText("Tìm tên hoặc tài khoản");
            search.getStyleClass().add("dialog-search");
            ListView<ChatUser> users = new ListView<>(visibleUsers);
            users.getStyleClass().add("dialog-list");
            users.setCellFactory(list -> new SidebarCells.UserCell(this::presenceText));
            users.setPrefSize(460, 420);
            Runnable filter = () -> {
                String q = Texts.normalize(search.getText());
                visibleUsers.setAll(candidates.stream()
                        .filter(u -> q.isBlank()
                                || Texts.normalize(u.displayName).contains(q)
                                || Texts.normalize(u.username).contains(q)
                                || Texts.normalize(u.position).contains(q))
                        .collect(Collectors.toList()));
            };
            search.textProperty().addListener((obs, old, value) -> filter.run());
            filter.run();

            VBox content = new VBox(12, search, users);
            content.getStyleClass().add("dialog-content");
            dialog.getDialogPane().setContent(content);
            dialog.setResultConverter(btn -> btn == open ? users.getSelectionModel().getSelectedItem() : null);
            dialog.showAndWait().ifPresent(u -> {
                try {
                    long id = chatService.openDirectConversation(currentUser, u.username);
                    loadConversations();
                    selectConversation(id);
                } catch (Exception e) {
                    showError("Không tạo được chat 1-1", e);
                }
            });
        } catch (Exception e) {
            showError("Không tải danh sách nhân viên", e);
        }
    }

    private void openGroupDialog(Conversation editConversation) {
        if (!currentUser.canManageGroups()) {
            showInfo("Chỉ Admin hoặc Trưởng phòng được tạo/quản lý nhóm.");
            return;
        }
        try {
            companyUsers = chatService.listCompanyUsers(currentUser);
            List<String> currentMembers = editConversation == null ? List.of() : chatService.listMemberUsernames(editConversation.id);
            Dialog<GroupForm> dialog = new Dialog<>();
            dialog.setTitle(editConversation == null ? "Tạo nhóm" : "Quản lý nhóm");
            styleDialog(dialog);
            ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            TextField title = new TextField(editConversation == null ? "" : editConversation.title);
            title.setPromptText("Tên nhóm");
            title.getStyleClass().add("dialog-search");
            TextField search = new TextField();
            search.setPromptText("Tìm thành viên");
            search.getStyleClass().add("dialog-search");

            VBox checks = new VBox(6);
            List<CheckBox> boxes = new ArrayList<>();
            for (ChatUser u : companyUsers) {
                CheckBox cb = new CheckBox(u.displayName + " - " + u.username + roleSuffix(u));
                cb.getStyleClass().add("member-check");
                cb.setUserData(u.username);
                cb.setSelected(currentMembers.contains(u.username) || u.username.equals(currentUser.username));
                cb.setDisable(u.username.equals(currentUser.username));
                boxes.add(cb);
                checks.getChildren().add(cb);
            }
            search.textProperty().addListener((obs, old, value) -> {
                String q = Texts.normalize(value);
                for (CheckBox cb : boxes) {
                    boolean show = q.isBlank() || Texts.normalize(cb.getText()).contains(q);
                    cb.setVisible(show);
                    cb.setManaged(show);
                }
            });

            ScrollPane scroll = new ScrollPane(checks);
            scroll.getStyleClass().add("dialog-scroll");
            scroll.setFitToWidth(true);
            scroll.setPrefHeight(430);
            VBox content = new VBox(12, label("Tên nhóm", "dialog-label"), title, search, scroll);
            content.getStyleClass().add("dialog-content");
            dialog.getDialogPane().setContent(content);
            dialog.setResultConverter(btn -> {
                if (btn != save) return null;
                List<String> members = boxes.stream().filter(CheckBox::isSelected).map(cb -> (String) cb.getUserData()).toList();
                return new GroupForm(title.getText(), members);
            });
            dialog.showAndWait().ifPresent(form -> {
                try {
                    long id;
                    if (editConversation == null) {
                        id = chatService.createGroup(currentUser, form.title, form.members);
                        auditLogService.log(currentUser.username, "GROUP_CREATED", "CONVERSATION", id, "members=" + form.members.size());
                    } else {
                        id = editConversation.id;
                        chatService.updateGroup(currentUser, id, form.title, form.members);
                        auditLogService.log(currentUser.username, "GROUP_UPDATED", "CONVERSATION", id, "members=" + form.members.size());
                    }
                    loadConversations();
                    selectConversation(id);
                } catch (Exception e) {
                    showError("Không lưu được nhóm", e);
                }
            });
        } catch (Exception e) {
            showError("Không tải dữ liệu nhóm", e);
        }
    }

    private void editMessage(ChatMessage msg) {
        TextArea area = new TextArea(Texts.safe(msg.body));
        area.getStyleClass().add("dialog-text-area");
        area.setWrapText(true);
        Dialog<String> dialog = simpleDialog("Sửa tin nhắn", area);
        dialog.showAndWait().ifPresent(text -> {
            runDb(() -> {
                chatService.editMessage(currentUser, msg.id, text);
                auditLogService.log(currentUser.username, "MESSAGE_EDITED", "MESSAGE", msg.id, "conversation=" + msg.conversationId);
                return true;
            }, ok -> {
                refreshMessages(true);
                publishRealtime("MESSAGE_UPDATED", msg.conversationId);
            }, e -> {
                showError("Không sửa được tin", e);
            });
        });
    }

    private void recallMessage(ChatMessage msg) {
        runDb(() -> {
            chatService.recallMessage(currentUser, msg.id);
            auditLogService.log(currentUser.username, "MESSAGE_RECALLED", "MESSAGE", msg.id, "conversation=" + msg.conversationId);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> {
            showError("Không thu hồi được tin", e);
        });
    }

    private void pinMessage(ChatMessage msg) {
        runDb(() -> {
            chatService.togglePinMessage(currentUser, msg.id, !msg.pinned);
            auditLogService.log(currentUser.username, !msg.pinned ? "MESSAGE_PINNED" : "MESSAGE_UNPINNED", "MESSAGE", msg.id, "conversation=" + msg.conversationId);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> {
            showError("Không ghim được tin", e);
        });
    }

    private void toggleConversationPin() {
        if (currentConversation == null) return;
        long conversationId = currentConversation.id;
        runDb(() -> {
            chatService.togglePinConversation(currentUser, conversationId, !currentConversation.pinned);
            auditLogService.log(currentUser.username, !currentConversation.pinned ? "CONVERSATION_PINNED" : "CONVERSATION_UNPINNED", "CONVERSATION", conversationId, "");
            return true;
        }, ok -> {
            loadConversations();
            publishRealtime("CONVERSATION_UPDATED", conversationId);
        }, e -> {
            showError("Không ghim được hội thoại", e);
        });
    }

    private void exportCurrentConversationCsv() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước khi export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Xuất lịch sử chat");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        chooser.setInitialFileName("chat-" + currentConversation.id + ".csv");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        long conversationId = currentConversation.id;
        Path target = file.toPath();
        runDb(() -> {
            Path exported = reportService.exportChatHistoryCsv(currentUser, conversationId, target);
            auditLogService.log(currentUser.username, "CHAT_HISTORY_EXPORTED", "CONVERSATION", conversationId, exported.toString());
            return exported;
        }, exported -> showInfo("Đã export lịch sử chat:\n" + exported), e -> showError("Không export được lịch sử chat", e));
    }

    private void exportCurrentConversationHtml() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước khi export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Xuất lịch sử chat HTML");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML", "*.html"));
        chooser.setInitialDirectory(reportsDirectory().toFile());
        chooser.setInitialFileName("chat-" + currentConversation.id + ".html");
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        long conversationId = currentConversation.id;
        Path target = file.toPath();
        runDb(() -> {
            Path exported = reportService.exportChatHistoryHtml(currentUser, conversationId, target);
            auditLogService.log(currentUser.username, "CHAT_HISTORY_HTML_EXPORTED", "CONVERSATION", conversationId, exported.toString());
            return exported;
        }, exported -> {
            showInfo("Đã export lịch sử chat HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("Không export được HTML", e));
    }

    private void showAdvancedMessageSearchDialog() {
        if (currentConversation == null) {
            showInfo("Hãy chọn hội thoại trước khi tìm kiếm.");
            return;
        }
        Dialog<MessageSearchCriteria> dialog = new Dialog<>();
        dialog.setTitle("Tìm kiếm nâng cao");
        styleDialog(dialog);
        ButtonType search = new ButtonType("Tìm", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(search, ButtonType.CANCEL);

        TextField keyword = new TextField(messageSearch == null ? "" : messageSearch.getText());
        keyword.setPromptText("Từ khóa, tên file");
        keyword.getStyleClass().add("dialog-search");
        ComboBox<String> sender = new ComboBox<>();
        sender.getItems().add("");
        for (ChatUser user : companyUsers) {
            sender.getItems().add(user.username);
        }
        sender.setPromptText("Người gửi");
        sender.getStyleClass().add("dialog-search");
        DatePicker from = new DatePicker();
        DatePicker to = new DatePicker();
        CheckBox onlyFiles = new CheckBox("Chỉ tin có file");
        CheckBox onlyPinned = new CheckBox("Chỉ tin đã ghim");
        CheckBox onlyMentions = new CheckBox("Chỉ tin nhắc đến tôi");
        CheckBox onlyTasks = new CheckBox("Chỉ task/workflow");
        CheckBox includeArchive = new CheckBox("Tìm trong archive");
        for (CheckBox cb : List.of(onlyFiles, onlyPinned, onlyMentions, onlyTasks, includeArchive)) {
            cb.getStyleClass().add("settings-check");
        }

        VBox content = new VBox(10,
                label("Từ khóa", "dialog-label"), keyword,
                label("Người gửi", "dialog-label"), sender,
                label("Từ ngày", "dialog-label"), from,
                label("Đến ngày", "dialog-label"), to,
                onlyFiles, onlyPinned, onlyMentions, onlyTasks, includeArchive);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != search) {
                return null;
            }
            MessageSearchCriteria criteria = new MessageSearchCriteria();
            criteria.keyword = keyword.getText();
            criteria.senderUsername = sender.getValue() == null ? "" : sender.getValue();
            criteria.from = from.getValue() == null ? null : from.getValue().atStartOfDay();
            criteria.to = to.getValue() == null ? null : to.getValue().atTime(23, 59, 59);
            criteria.onlyFiles = onlyFiles.isSelected();
            criteria.onlyPinned = onlyPinned.isSelected();
            criteria.onlyMentions = onlyMentions.isSelected();
            criteria.onlyTasks = onlyTasks.isSelected();
            criteria.includeArchive = includeArchive.isSelected();
            return criteria;
        });
        dialog.showAndWait().ifPresent(this::runAdvancedMessageSearch);
    }

    private void runAdvancedMessageSearch(MessageSearchCriteria criteria) {
        long conversationId = currentConversation.id;
        runDb(() -> chatService.searchMessages(currentUser, conversationId, criteria), messages -> {
            if (currentConversation == null || currentConversation.id != conversationId) {
                return;
            }
            currentMessages = messages;
            if (messages.isEmpty()) {
                messageBox.getChildren().setAll(emptyState("Không có kết quả", "Thử đổi từ khóa hoặc bộ lọc tìm kiếm."));
            } else {
                messageBox.getChildren().setAll(messages.stream().map(this::messageNode).collect(Collectors.toList()));
            }
            headerDetail.setText("Kết quả tìm kiếm nâng cao: " + messages.size() + " tin");
        }, e -> showError("Không tìm kiếm được tin nhắn", e));
    }

    private void connectRealtime() {
        if (realtimeClient != null) {
            realtimeClient.close();
        }
        realtimeClient = new RealtimeClient(config, event -> Platform.runLater(() -> handleRealtimeEvent(event)));
        realtimeClient.connect(currentUser.username, sessionToken);
        flushPendingMessages();
    }

    private void flushPendingMessages() {
        List<PendingMessage> pending = pendingMessageService.list(currentUser.username);
        if (pending.isEmpty()) {
            return;
        }
        for (PendingMessage msg : pending) {
            List<File> files = msg.filePaths.stream().map(File::new).filter(File::isFile).collect(Collectors.toList());
            runDb(() -> chatService.sendMessage(currentUser, msg.conversationId, msg.body, null, files), id -> {
                pendingMessageService.remove(currentUser.username, msg.conversationId, msg.body);
                publishRealtime("MESSAGE_CREATED", msg.conversationId);
                loadConversations();
                if (currentConversation != null && currentConversation.id == msg.conversationId) {
                    refreshMessages(true);
                }
            }, e -> {
            });
        }
    }

    private boolean canQueueFiles(List<File> files) {
        if (files == null || files.isEmpty()) return true;
        long total = 0;
        for (File file : files) {
            if (file == null || !file.isFile()) return false;
            total += file.length();
            if (total > 10L * 1024L * 1024L) return false;
        }
        return true;
    }

    private void handleRealtimeEvent(Map<String, String> event) {
        String type = event.getOrDefault("type", "");
        long conversationId = parseLong(event.get("conversationId"));
        String actor = event.getOrDefault("username", event.getOrDefault("actor", ""));
        if ("TYPING".equals(type)) {
            showTypingIndicator(conversationId, actor);
            return;
        }
        loadConversations();
        if (currentConversation != null) {
            refreshMessages(true);
            loadTasksAsync();
        }
    }

    private void publishRealtime(String type, long conversationId) {
        if (realtimeClient != null) {
            realtimeClient.publish(type, conversationId);
        }
    }

    private void publishTypingIfNeeded() {
        if (currentConversation == null || realtimeClient == null || input == null || input.getText().isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTypingSentAt < 1500) {
            return;
        }
        lastTypingSentAt = now;
        realtimeClient.publishTyping(currentConversation.id);
    }

    private void updateMentionSuggestions() {
        if (input == null || companyUsers == null || companyUsers.isEmpty()) {
            hideMentionMenu();
            return;
        }
        int caret = input.getCaretPosition();
        String text = input.getText();
        if (caret <= 0 || caret > text.length()) {
            hideMentionMenu();
            return;
        }
        int start = text.lastIndexOf('@', caret - 1);
        if (start < 0) {
            hideMentionMenu();
            return;
        }
        String token = text.substring(start + 1, caret);
        if (token.contains(" ") || token.contains("\n") || token.length() > 40) {
            hideMentionMenu();
            return;
        }
        String q = Texts.normalize(token);
        List<ChatUser> matches = companyUsers.stream()
                .filter(u -> !u.username.equals(currentUser.username))
                .filter(u -> q.isBlank()
                        || Texts.normalize(u.username).contains(q)
                        || Texts.normalize(u.displayName).contains(q))
                .limit(6)
                .collect(Collectors.toList());
        if (matches.isEmpty()) {
            hideMentionMenu();
            return;
        }
        if (mentionMenu == null) {
            mentionMenu = new ContextMenu();
        }
        mentionMenu.getItems().clear();
        for (ChatUser user : matches) {
            MenuItem item = new MenuItem(user.displayName + "  @" + user.username);
            item.setOnAction(e -> insertMention(start, caret, user.username));
            mentionMenu.getItems().add(item);
        }
        if (!mentionMenu.isShowing()) {
            mentionMenu.show(input, javafx.geometry.Side.TOP, 0, -8);
        }
    }

    private void insertMention(int start, int caret, String username) {
        input.replaceText(start, caret, "@" + username + " ");
        input.positionCaret(start + username.length() + 2);
        hideMentionMenu();
        input.requestFocus();
    }

    private void hideMentionMenu() {
        if (mentionMenu != null) {
            mentionMenu.hide();
        }
    }

    private void showTypingIndicator(long conversationId, String actor) {
        if (currentConversation == null || currentConversation.id != conversationId || actor == null || actor.isBlank() || actor.equals(currentUser.username)) {
            return;
        }
        headerDetail.setText(actor + " đang nhập...");
        if (typingClearDelay != null) {
            typingClearDelay.stop();
        }
        typingClearDelay = new PauseTransition(Duration.seconds(3));
        typingClearDelay.setOnFinished(e -> {
            if (currentConversation != null && currentConversation.id == conversationId) {
                headerDetail.setText(conversationDescription(currentConversation));
            }
        });
        typingClearDelay.playFromStart();
    }

    private static long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private void notifyNewMessage(Conversation conversation) {
        if ("DO_NOT_DISTURB".equals(userSettings.presenceStatus)) {
            return;
        }
        if (userSettings.soundEnabled) {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {
            }
        }
        if (userSettings.toastEnabled) {
            showToast(conversation == null ? "Có tin nhắn mới" : "Có tin mới từ " + conversation.title);
        }
        if (userSettings.toastEnabled) {
            lastNativeNotificationConversation = conversation;
            showNativeNotification("Chat nội bộ", conversation == null ? "Có tin nhắn mới" : "Có tin mới từ " + conversation.title);
        }
        if (conversation != null) {
            showChatHead(conversation);
        }
    }

    private void showChatHead(Conversation conversation) {
        hideChatHead();
        Stage popup = new Stage(StageStyle.UNDECORATED);
        popup.initOwner(stage);
        popup.setAlwaysOnTop(true);
        Node avatar = avatarNode(conversation.title, "chat-head-avatar", null);
        Button close = new Button("×");
        close.getStyleClass().add("chat-head-close");
        close.setTooltip(new Tooltip("Ẩn thông báo nổi"));
        close.setOnAction(e -> {
            hideChatHead();
            e.consume();
        });
        StackPane root = new StackPane(avatar, close);
        root.getStyleClass().add("chat-head-root");
        StackPane.setAlignment(close, Pos.TOP_RIGHT);
        root.setOnMouseClicked(e -> {
            selectConversation(conversation.id);
            hideChatHead();
        });
        Scene scene = new Scene(root, 72, 72);
        applyCss(scene);
        popup.setScene(scene);
        popup.show();
        popup.setX(stage.getX() + stage.getWidth() - 104);
        popup.setY(stage.getY() + stage.getHeight() - 124);
        chatHeadStage = popup;
    }

    private void hideChatHead() {
        if (chatHeadStage != null) {
            chatHeadStage.close();
            chatHeadStage = null;
        }
    }

    private void showNativeNotification(String title, String message) {
        try {
            TrayIcon icon = ensureTrayIcon();
            if (icon != null) {
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            }
        } catch (Exception e) {
            AppLog.warn("Không hiển thị được thông báo native.", e);
        }
    }

    private TrayIcon ensureTrayIcon() throws Exception {
        if (!SystemTray.isSupported()) {
            return null;
        }
        if (trayIcon != null) {
            return trayIcon;
        }
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new java.awt.Color(0, 122, 255));
        g.fillOval(1, 1, 14, 14);
        g.setColor(java.awt.Color.WHITE);
        g.drawString("C", 5, 12);
        g.dispose();
        trayIcon = new TrayIcon(image, "Chat nội bộ");
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> Platform.runLater(() -> {
            if (stage != null) {
                stage.show();
                stage.toFront();
            }
            if (lastNativeNotificationConversation != null) {
                selectConversation(lastNativeNotificationConversation.id);
            }
        }));
        SystemTray.getSystemTray().add(trayIcon);
        return trayIcon;
    }

    private void removeTrayIcon() {
        try {
            if (trayIcon != null && SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(trayIcon);
            }
        } catch (Exception ignored) {
        } finally {
            trayIcon = null;
        }
    }

    private void showToast(String text) {
        Stage toast = new Stage(StageStyle.UNDECORATED);
        toast.initOwner(stage);
        Label label = new Label(text);
        label.getStyleClass().add("toast");
        Scene scene = new Scene(new StackPane(label));
        applyCss(scene);
        toast.setScene(scene);
        toast.setAlwaysOnTop(true);
        toast.show();
        toast.setX(stage.getX() + stage.getWidth() - 280);
        toast.setY(stage.getY() + stage.getHeight() - 130);
        PauseTransition delay = new PauseTransition(Duration.seconds(3));
        delay.setOnFinished(e -> toast.close());
        delay.play();
    }

    private void showAccountMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        MenuItem settings = new MenuItem("Cài đặt");
        settings.setOnAction(e -> showSettingsDialog());
        MenuItem account = new MenuItem("Thông tin tài khoản");
        account.setOnAction(e -> showAccountInfo());
        MenuItem logout = new MenuItem("Đăng xuất");
        logout.setOnAction(e -> logoutAndExit());
        menu.getItems().addAll(settings, account, logout);
        menu.show(owner, javafx.geometry.Side.BOTTOM, 0, 6);
    }

    private void showSettingsDialog() {
        activeNav = "SETTINGS";
        refreshNavActiveStyles();
        new SettingsDialog(
                stage,
                config,
                userSettings,
                currentUser,
                this::openFilesRoot,
                this::openReportsFolder,
                this::exportChatBackup,
                this::restoreChatBackup,
                this::showAdminDashboardDialog,
                this::archiveOldMessages,
                this::exportTaskReportHtml,
                this::exportEngagementReportHtml,
                this::afterSettingsSaved).show();
    }

    private void afterSettingsSaved() {
        applyPersonalization();
        if (appRoot != null) {
            appRoot.setCenter(buildWorkspace());
            refreshConversationFilter();
            if (currentConversation != null) {
                conversationList.getSelectionModel().select(currentConversation);
            }
        }
        refreshMessages(true);
        showToast("Đã lưu cài đặt.");
    }

    private void showAccountInfo() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Thông tin tài khoản");
        styleDialog(dialog);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox content = new VBox(12,
                settingLine("Tên hiển thị", currentUser.displayName),
                settingLine("Tài khoản", currentUser.username),
                settingLine("Vai trò", currentUser.isAdmin() ? "Quản trị viên" : "Nhân viên"),
                settingLine("Công ty", currentUser.companyOwner),
                settingLine("Phòng ban", Texts.safe(currentUser.department).isBlank() ? "Chưa cập nhật" : currentUser.department),
                settingLine("Chức vụ", Texts.safe(currentUser.position).isBlank() ? "Chưa cập nhật" : currentUser.position));
        content.getStyleClass().add("settings-content");
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void logoutAndExit() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Đăng xuất");
        confirm.setHeaderText("Bạn muốn đăng xuất và thoát ứng dụng?");
        confirm.setContentText("Ứng dụng sẽ dừng nhận tin nhắn mới trên máy này.");
        ButtonType logout = new ButtonType("Đăng xuất", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(logout, ButtonType.CANCEL);
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == logout) {
                String username = currentUser == null ? "" : currentUser.username;
                try {
                    if (!sessionToken.isBlank()) {
                        securityService.revokeToken(sessionToken);
                    }
                    auditLogService.log(username, "LOGOUT", "SESSION", username, "User requested logout");
                } catch (Exception e) {
                AppLog.warn("Không dọn được session/audit khi đăng xuất.", e);
                }
                if (realtimeClient != null) {
                    realtimeClient.close();
                }
                sessionToken = "";
                hideChatHead();
                currentUser = null;
                currentConversation = null;
                stage.close();
                Platform.exit();
            }
        });
    }

    private void openFilesRoot() {
        try {
            Files.createDirectories(config.filesRoot);
            Desktop.getDesktop().open(config.filesRoot.toFile());
        } catch (Exception e) {
            showError("Không mở được thư mục file", e);
        }
    }

    private void openReportsFolder() {
        try {
            Desktop.getDesktop().open(reportsDirectory().toFile());
        } catch (Exception e) {
            showError("Không mở được thư mục báo cáo", e);
        }
    }

    private Path reportsDirectory() {
        Path reports = Path.of("reports").toAbsolutePath();
        try {
            Files.createDirectories(reports);
        } catch (Exception ignored) {
            return Path.of(".").toAbsolutePath();
        }
        return reports;
    }

    private void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.F) {
                if (messageSearch != null) {
                    messageSearch.requestFocus();
                    messageSearch.selectAll();
                } else if (conversationSearch != null) {
                    conversationSearch.requestFocus();
                    conversationSearch.selectAll();
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                clearComposerState();
                event.consume();
            }
        });
    }

    private void installDragDrop(Node target) {
        target.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                event.consume();
            }
        });
        target.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                selectedFiles.addAll(db.getFiles());
                updateAttachmentLabel();
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    private void clearComposerState() {
        selectedFiles.clear();
        replyToId = null;
        if (replyLabel != null) {
            replyLabel.setText("");
            replyLabel.setVisible(false);
            replyLabel.setManaged(false);
        }
        if (attachmentLabel != null) {
            updateAttachmentLabel();
        }
    }

    private <T> void runDb(DbCall<T> call, Consumer<T> onSuccess, Consumer<Exception> onFailed) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return call.execute();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            onFailed.accept(error instanceof Exception ex ? ex : new Exception(error));
        });
        dbExecutor.submit(task);
    }

    private interface DbCall<T> {
        T execute() throws Exception;
    }

    private void exportChatBackup() {
        if (!currentUser.isAdmin()) {
            showInfo("Chỉ admin được backup dữ liệu chat.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Backup dữ liệu chat");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        chooser.setInitialFileName("chat-backup-" + LocalDate.now() + ".zip");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        Path target = file.toPath();
        runDb(() -> {
            Path exported = backupService.exportChatBackup(currentUser, target);
            auditLogService.log(currentUser.username, "BACKUP_EXPORTED", "BACKUP", "chat", exported.toString());
            return exported;
        }, exported -> showInfo("Đã backup dữ liệu chat:\n" + exported), e -> showError("Không backup được dữ liệu chat", e));
    }

    private void restoreChatBackup() {
        if (!currentUser.isAdmin()) {
            showInfo("Chỉ admin được restore dữ liệu chat.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn file backup chat");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Restore backup chat");
        confirm.setHeaderText("Import dữ liệu từ backup?");
        confirm.setContentText("Restore dùng INSERT IGNORE: không xóa dữ liệu hiện có, chỉ bổ sung bản ghi chưa có.");
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                runDb(() -> {
                    int count = backupService.restoreChatBackup(currentUser, file.toPath());
                    auditLogService.log(currentUser.username, "BACKUP_RESTORED", "BACKUP", "chat", file.getAbsolutePath() + "; rows=" + count);
                    return count;
                }, count -> {
                    showInfo("Đã restore/import " + count + " dòng từ backup.");
                    loadConversations();
                    refreshMessages(true);
                }, e -> showError("Không restore được backup", e));
            }
        });
    }

    private void exportTaskReportHtml() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export task report HTML");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML", "*.html"));
        chooser.setInitialDirectory(reportsDirectory().toFile());
        chooser.setInitialFileName("task-report-" + LocalDate.now() + ".html");
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path target = file.toPath();
        runDb(() -> {
            Path exported = reportService.exportTaskReportHtml(currentUser, target);
            auditLogService.log(currentUser.username, "TASK_REPORT_HTML_EXPORTED", "REPORT", "chat_tasks", exported.toString());
            return exported;
        }, exported -> {
            showInfo("Đã export task report HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("Không export được task report HTML", e));
    }

    private void exportEngagementReportHtml() {
        if (!currentUser.isAdmin()) {
            showInfo("Chỉ admin được export báo cáo tương tác.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export engagement report HTML");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML", "*.html"));
        chooser.setInitialDirectory(reportsDirectory().toFile());
        chooser.setInitialFileName("engagement-report-" + LocalDate.now() + ".html");
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        Path target = file.toPath();
        runDb(() -> {
            Path exported = reportService.exportEngagementReportHtml(currentUser, target);
            auditLogService.log(currentUser.username, "ENGAGEMENT_REPORT_HTML_EXPORTED", "REPORT", "engagement", exported.toString());
            return exported;
        }, exported -> {
            showInfo("Đã export báo cáo tương tác HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("Không export được báo cáo tương tác HTML", e));
    }

    private void showAdminDashboardDialog() {
        new AdminDashboardDialog(
                stage,
                currentUser,
                adminDashboardService,
                chatService,
                auditLogService,
                new AdminDashboardDialog.DbRunner() {
                    @Override
                    public <T> void run(AdminDashboardDialog.DbCall<T> call, Consumer<T> onSuccess, Consumer<Exception> onFailed) {
                        runDb(call::execute, onSuccess, onFailed);
                    }
                },
                this::openGroupDialog,
                this::showInfo,
                this::showError).show();
    }

    private void archiveOldMessages() {
        if (!currentUser.isAdmin()) {
            showInfo("Chỉ admin được archive tin nhắn.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Archive tin cÅ©");
        confirm.setHeaderText("Chuyển tin cũ sang bảng archive?");
        confirm.setContentText("Tin cũ hơn " + config.retentionDays + " ngày sẽ được chuyển sang archive. Bạn vẫn có thể tìm lại bằng bộ lọc archive.");
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                runDb(() -> {
                    int count = chatService.archiveOldMessages(currentUser, config.retentionDays);
                    auditLogService.log(currentUser.username, "MESSAGES_ARCHIVED", "ARCHIVE", currentUser.companyOwner, "count=" + count);
                    return count;
                }, count -> showInfo("Đã archive " + count + " tin nhắn."), e -> showError("Không archive được tin cũ", e));
            }
        });
    }

    private VBox settingsSection(String title, Node... children) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("settings-title");
        VBox box = new VBox(10, titleLabel);
        box.getChildren().addAll(children);
        box.getStyleClass().add("settings-section");
        return box;
    }

    private HBox settingLine(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("settings-key");
        Label valueLabel = new Label(value == null || value.isBlank() ? "Chưa cập nhật" : value);
        valueLabel.getStyleClass().add("settings-value");
        valueLabel.setWrapText(true);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        HBox line = new HBox(12, keyLabel, valueLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("settings-line");
        return line;
    }

    private Button iconButton(String iconName, String tooltip, String styleClass) {
        Button button = new Button();
        button.getStyleClass().add(styleClass);
        button.setTooltip(new Tooltip(tooltip));
        applyIcon(button, iconName);
        installHoverScale(button, 1.05);
        return button;
    }

    private void applyIcon(Button button, String iconName) {
        button.setText("");
        button.setGraphic(svgIcon(iconName, 18));
        installHoverScale(button, button.getStyleClass().contains("send-icon-button") ? 1.07 : 1.05);
    }

    private SVGPath svgIcon(String iconName, double size) {
        SVGPath icon = new SVGPath();
        icon.getStyleClass().add("svg-icon");
        icon.setContent(switch (iconName) {
            case "home" -> "M3 11 L12 4 L21 11 V21 H15 V15 H9 V21 H3 Z";
            case "chat" -> "M4 5 H20 V16 H8 L4 20 Z";
            case "contact" -> "M12 12 A4 4 0 1 0 12 4 A4 4 0 0 0 12 12 M4 21 C4 17 7 15 12 15 C17 15 20 17 20 21";
            case "bell" -> "M6 17 H18 L16 15 V11 C16 8 14 6 12 6 C10 6 8 8 8 11 V15 Z M10 19 C10.5 20 13.5 20 14 19 M12 3 V6";
            case "calendar" -> "M5 5 H19 V21 H5 Z M8 3 V7 M16 3 V7 M5 10 H19";
            case "settings" -> "M12 15 A3 3 0 1 0 12 9 A3 3 0 0 0 12 15 M19 12 C19 11.5 19 11 18.8 10.5 L21 8 L18.5 5.5 L16 7.2 C15.5 7 15 6.8 14.5 6.6 L14 3 H10 L9.5 6.6 C9 6.8 8.5 7 8 7.2 L5.5 5.5 L3 8 L5.2 10.5 C5 11 5 11.5 5 12 C5 12.5 5 13 5.2 13.5 L3 16 L5.5 18.5 L8 16.8 C8.5 17 9 17.2 9.5 17.4 L10 21 H14 L14.5 17.4 C15 17.2 15.5 17 16 16.8 L18.5 18.5 L21 16 L18.8 13.5 C19 13 19 12.5 19 12";
            case "logout" -> "M10 5 H5 V19 H10 M14 8 L18 12 L14 16 M18 12 H9";
            case "search" -> "M11 18 A7 7 0 1 0 11 4 A7 7 0 0 0 11 18 M16 16 L21 21";
            case "filter" -> "M4 5 H20 L14 12 V19 L10 21 V12 Z";
            case "chevron" -> "M8 10 L12 14 L16 10";
            case "collapse" -> "M4 5 H20 M4 12 H14 M4 19 H20 M17 9 L14 12 L17 15";
            case "new-chat" -> "M4 5 H20 V16 H8 L4 20 Z M12 8 V14 M9 11 H15";
            case "message" -> "M4 5 H20 V16 H8 L4 20 Z";
            case "users" -> "M8 11 A4 4 0 1 0 8 3 A4 4 0 0 0 8 11 M2 21 C2 17 4.7 14.5 8 14.5 C11.3 14.5 14 17 14 21 M17 11 A3 3 0 1 0 17 5 A3 3 0 0 0 17 11 M15.5 15 C18.8 15.2 21 17.5 21 21";
            case "pin" -> "M14 3 L21 10 L18.5 12.5 L15.5 9.5 L10 15 L10 20 L8.5 21.5 L2.5 15.5 L4 14 L9 14 L14.5 8.5 L11.5 5.5 Z";
            case "tasks" -> "M8 6 H21 M8 12 H21 M8 18 H21 M3.5 6 L4.5 7 L6.5 4.5 M3.5 12 L4.5 13 L6.5 10.5 M3.5 18 L4.5 19 L6.5 16.5";
            case "download" -> "M12 3 V15 M7 10 L12 15 L17 10 M5 20 H19";
            case "plus" -> "M11 4 H13 V11 H20 V13 H13 V20 H11 V13 H4 V11 H11 Z";
            case "trash" -> "M7 7 H17 L16 21 H8 Z M9 4 H15 L16 6 H8 Z M5 7 H19";
            case "smile" -> "M12 21 A9 9 0 1 0 12 3 A9 9 0 0 0 12 21 M8.5 10 H8.6 M15.4 10 H15.5 M8.5 14 C10 16 14 16 15.5 14";
            case "mic" -> "M12 14 C14 14 15 12.7 15 11 V6 C15 4.3 14 3 12 3 C10 3 9 4.3 9 6 V11 C9 12.7 10 14 12 14 M6 10 C6 14 8.5 17 12 17 C15.5 17 18 14 18 10 M12 17 V21 M9 21 H15";
            case "send" -> "M3 11 L21 3 L13 21 L11 13 Z";
            default -> "M5 7 H19 M5 12 H19 M5 17 H19";
        });
        icon.setScaleX(size / 24.0);
        icon.setScaleY(size / 24.0);
        return icon;
    }

    private void installHoverScale(Node node, double hoverScale) {
        node.setOnMouseEntered(e -> animateScale(node, hoverScale));
        node.setOnMouseExited(e -> animateScale(node, 1.0));
    }

    private void installFocusScale(Node node) {
        node.focusedProperty().addListener((obs, wasFocused, focused) -> animateScale(node, focused ? 1.015 : 1.0));
    }

    private void animateScale(Node node, double scale) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(140), node);
        transition.setToX(scale);
        transition.setToY(scale);
        transition.play();
    }

    private void animateMessagePaneOut() {
        if (messageBox == null) {
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(120), messageBox);
        fade.setToValue(0.35);
        fade.play();
    }

    private void animateMessagePaneIn() {
        if (messageBox == null) {
            return;
        }
        messageBox.setOpacity(0);
        messageBox.setTranslateY(14);
        FadeTransition fade = new FadeTransition(Duration.millis(190), messageBox);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(190), messageBox);
        slide.setFromY(14);
        slide.setToY(0);
        fade.play();
        slide.play();
    }

    private void animateMessageIfNew(Node node, long messageId) {
        if (!animatedMessageIds.add(messageId)) {
            return;
        }
        node.setOpacity(0);
        node.setTranslateY(10);
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition slide = new TranslateTransition(Duration.millis(180), node);
        slide.setFromY(10);
        slide.setToY(0);
        fade.play();
        slide.play();
    }

    private VBox colorPalette(String[] selectedAccent) {
        String[] colors = {
                "#007aff", "#0ea5e9", "#10b981", "#22c55e",
                "#f59e0b", "#f97316", "#ef4444", "#ec4899",
                "#8b5cf6", "#6366f1", "#14b8a6", "#334155"
        };
        VBox wrapper = new VBox(8);
        Label current = new Label("Màu đang chọn: " + selectedAccent[0]);
        current.getStyleClass().add("settings-note");
        HBox row1 = new HBox(8);
        HBox row2 = new HBox(8);
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < colors.length; i++) {
            String color = colors[i];
            Button swatch = new Button();
            swatch.getStyleClass().add("color-swatch");
            swatch.setStyle("-fx-background-color: " + color + ";");
            swatch.setTooltip(new Tooltip(color));
            if (color.equalsIgnoreCase(selectedAccent[0])) {
                swatch.getStyleClass().add("color-swatch-selected");
            }
            swatch.setOnAction(e -> {
                selectedAccent[0] = color;
                current.setText("Màu đang chọn: " + color);
                for (Button b : buttons) {
                    b.getStyleClass().remove("color-swatch-selected");
                }
                swatch.getStyleClass().add("color-swatch-selected");
            });
            buttons.add(swatch);
            (i < 6 ? row1 : row2).getChildren().add(swatch);
        }
        wrapper.getChildren().addAll(current, row1, row2);
        return wrapper;
    }

    private void showEmojiMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        VBox box = new VBox(10);
        box.getStyleClass().add("emoji-panel");
        addEmojiGroup(menu, box, "Cảm xúc", "😀", "😁", "😂", "🤣", "😊", "😍", "😘", "😎");
        addEmojiGroup(menu, box, "Phản hồi nhanh", "👍", "👎", "👏", "🙏", "💪", "🔥", "âœ¨", "❤️");
        addEmojiGroup(menu, box, "Công việc", "âœ…", "❌", "📌", "📎", "📷", "🎬", "💼", "⏰");
        addEmojiGroup(menu, box, "Không khí", "🎉", "⭐", "â˜•", "💡", "🚀", "🎯", "🏆", "💬");
        javafx.scene.control.CustomMenuItem item = new javafx.scene.control.CustomMenuItem(box, false);
        menu.getItems().add(item);
        menu.show(owner, javafx.geometry.Side.TOP, 0, -8);
    }

    private void addEmojiGroup(ContextMenu menu, VBox box, String title, String... emojis) {
        Label label = new Label(title);
        label.getStyleClass().add("emoji-category-title");
        HBox row = new HBox(6);
        for (String value : emojis) {
            Button emoji = new Button(value);
            emoji.getStyleClass().add("emoji-button");
            emoji.setTooltip(new Tooltip("Chèn " + value));
            emoji.setOnAction(e -> {
                input.appendText(value);
                input.requestFocus();
                menu.hide();
            });
            row.getChildren().add(emoji);
        }
        box.getChildren().addAll(label, row);
    }

    private void applyPersonalization() {
        if (appRoot != null) {
            appRoot.setStyle(personalizationStyle());
        }
        String background = chatBackgroundStyle(userSettings.chatBackground);
        if (messageScroll != null) {
            messageScroll.setStyle(background);
        }
        if (messageBox != null) {
            messageBox.setStyle(background);
        }
    }

    private String personalizationStyle() {
        String accent = validHex(userSettings.accentColor) ? userSettings.accentColor : "#007aff";
        String accentDark = darken(accent);
        String accentSoft = rgba(accent, 0.15);
        String text = validHex(userSettings.textColor) ? userSettings.textColor : "#f8fafc";
        String muted = validHex(userSettings.mutedColor) ? userSettings.mutedColor : "#64748b";
        int fontSize = Math.max(12, Math.min(18, userSettings.fontSize));
        return String.join(" ",
                "-app-primary: " + accent + ";",
                "-app-primary-deep: " + accentDark + ";",
                "-app-primary-soft: " + accentSoft + ";",
                "-app-accent: " + accent + ";",
                "-app-accent-dark: " + accentDark + ";",
                "-app-text: " + text + ";",
                "-app-muted: " + muted + ";",
                "-app-font-size: " + fontSize + "px;",
                "-fx-font-size: " + fontSize + "px;");
    }

    private static Color safeColor(String value) {
        try {
            return Color.web(validHex(value) ? value : "#007aff");
        } catch (Exception ignored) {
            return Color.web("#007aff");
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    private static boolean validHex(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}");
    }

    private static String darken(String hex) {
        Color color = safeColor(hex);
        Color darker = color.deriveColor(0, 1.0, 0.78, 1.0);
        return toHex(darker);
    }

    private static String rgba(String hex, double alpha) {
        Color color = safeColor(hex);
        int r = (int) Math.round(color.getRed() * 255);
        int g = (int) Math.round(color.getGreen() * 255);
        int b = (int) Math.round(color.getBlue() * 255);
        return String.format("rgba(%d,%d,%d,%.2f)", r, g, b, Math.max(0, Math.min(1, alpha)));
    }

    private static String chatBackgroundStyle(String key) {
        String background = switch (key == null ? "" : key) {
            case "clean-white" -> "#0f141f";
            case "mint" -> "#0d1a18";
            case "lavender" -> "#151326";
            case "peach" -> "#1d1412";
            case "night" -> "#0b1020";
            default -> "#111827";
        };
        return "-fx-background: " + background + "; -fx-background-color: " + background + ";";
    }

    private void selectConversation(long id) {
        Conversation conversation = conversations.stream().filter(c -> c.id == id).findFirst().orElse(null);
        if (conversation == null) {
            return;
        }
        if (!visibleConversations.contains(conversation)) {
            conversationSearch.clear();
            refreshConversationFilter();
        }
        conversationList.getSelectionModel().select(conversation);
        currentConversation = conversation;
        hideChatHead();
        forceScrollToBottom = true;
        refreshMessages(true);
    }

    private void openFile(File file) {
        try {
            if (!file.exists()) {
                showInfo("File không tồn tại: " + file.getAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(file);
        } catch (Exception e) {
            showError("Không mở được file", e);
        }
    }

    private Node emptyState(String title, String detail) {
        Label icon = new Label("💬");
        icon.getStyleClass().add("empty-icon");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-title");
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("empty-detail");
        VBox box = new VBox(8, icon, titleLabel, detailLabel);
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Label avatar(String text, String styleClass) {
        Label avatar = new Label(initials(text));
        avatar.getStyleClass().add(styleClass);
        return avatar;
    }

    private Node avatarNode(String text, String styleClass, String username) {
        String path = username == null ? "" : userSettings.avatarPath(username);
        if (path == null || path.isBlank()) {
            return avatar(text, styleClass);
        }
        File file = new File(path);
        if (!file.isFile()) {
            return avatar(text, styleClass);
        }
        double size = avatarSize(styleClass);
        Image image = new Image(file.toURI().toString(), size, size, true, true, true);
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(false);
        Circle clip = new Circle(size / 2, size / 2, size / 2);
        view.setClip(clip);
        StackPane wrapper = new StackPane(view);
        wrapper.getStyleClass().addAll(styleClass, "avatar-image-wrap");
        wrapper.setMinSize(size, size);
        wrapper.setPrefSize(size, size);
        wrapper.setMaxSize(size, size);
        return wrapper;
    }

    private static double avatarSize(String styleClass) {
        return switch (styleClass) {
            case "profile-avatar" -> 44;
            case "header-avatar" -> 48;
            case "conversation-avatar" -> 42;
            case "task-avatar" -> 26;
            case "chat-head-avatar" -> 54;
            default -> 32;
        };
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String initials(String text) {
        String value = Texts.safe(text).trim();
        if (value.isBlank()) {
            return "?";
        }
        String[] parts = value.split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(1, parts[0].length())).toUpperCase();
        }
        String first = parts[0].substring(0, 1);
        String last = parts[parts.length - 1].substring(0, 1);
        return (first + last).toUpperCase();
    }

    private static String conversationDescription(Conversation c) {
        String type = switch (c.type) {
            case "DIRECT" -> "Trò chuyện 1-1";
            case "GROUP" -> "Nhóm nội bộ";
            case "COMPANY" -> "Toàn công ty";
            case "DEPARTMENT" -> "Phòng ban";
            default -> "Hội thoại";
        };
        return type + (c.lastMessage == null || c.lastMessage.isBlank() ? "" : " - " + c.lastMessage);
    }

    private void updateDirectPresenceHeader(Conversation conversation) {
        if (conversation == null || !"DIRECT".equals(conversation.type)) {
            return;
        }
        long conversationId = conversation.id;
        runDb(() -> chatService.listMemberUsernames(conversationId), members -> {
            if (currentConversation == null || currentConversation.id != conversationId || headerDetail == null) {
                return;
            }
            String other = members.stream().filter(username -> !username.equals(currentUser.username)).findFirst().orElse("");
            ChatUser user = companyUsers.stream().filter(u -> u.username.equals(other)).findFirst().orElse(null);
            headerDetail.setText("Trò chuyện 1-1 · " + presenceText(user));
        }, e -> {
        });
    }

    private String presenceText(ChatUser user) {
        if (user == null || user.lastSeenAt == null) {
            return "Chưa có trạng thái hoạt động";
        }
        long minutes = java.time.Duration.between(user.lastSeenAt, LocalDateTime.now()).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        if (minutes <= 2) {
            return "Đang online";
        }
        if (minutes < 60) {
            return "Hoạt động " + minutes + " phút trước";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "Hoạt động " + hours + " giờ trước";
        }
        return "Hoạt động " + (hours / 24) + " ngày trước";
    }

    private static String roleSuffix(ChatUser user) {
        if (user.position == null || user.position.isBlank()) {
            return "";
        }
        return " (" + user.position + ")";
    }

    private Dialog<String> simpleDialog(String title, TextArea area) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(title);
        styleDialog(dialog);
        ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        VBox content = new VBox(10, area);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == save ? area.getText() : null);
        return dialog;
    }

    private void styleDialog(Dialog<?> dialog) {
        String css = ChatApp.class.getResource("style.css") == null ? null : Objects.requireNonNull(ChatApp.class.getResource("style.css")).toExternalForm();
        if (css != null) {
            dialog.getDialogPane().getStylesheets().add(css);
        }
        dialog.getDialogPane().getStyleClass().add("custom-dialog");
        dialog.getDialogPane().setStyle(personalizationStyle());
    }

    private void showError(String title, Exception e) {
        AppLog.error(title, e);
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR, e.getMessage());
            styleDialog(alert);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.showAndWait();
        });
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message);
        styleDialog(alert);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return Math.max(1, bytes / 1024) + "KB";
        }
        return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
    }

    private static void applyCss(Scene scene) {
        String css = ChatApp.class.getResource("style.css") == null ? null : Objects.requireNonNull(ChatApp.class.getResource("style.css")).toExternalForm();
        if (css != null) {
            scene.getStylesheets().add(css);
        }
    }

    private record GroupForm(String title, List<String> members) {
    }

    private record ScheduledDraft(String body, LocalDateTime scheduledAt) {
    }

    private record ReminderDraft(Long conversationId, String title, String body, LocalDateTime remindAt) {
    }

    record HomeSummary(int unread, int overdueTasks, int mentions, int pendingWorkflows, int dueToday) {
        boolean isQuiet() {
            return unread == 0 && overdueTasks == 0 && mentions == 0 && pendingWorkflows == 0 && dueToday == 0;
        }
    }

    private record NotificationSummary(int overdueTasks, int mentions, int pendingWorkflows) {
    }

    private record TwoFactorResetRequest(String targetUsername, String confirmerUsername, String confirmerPassword) {
    }

    private record PasswordResetRequest(String targetUsername, String newPassword, String confirmPassword, String confirmerUsername, String confirmerPassword) {
    }

    private record VoteDraft(String question, List<String> options) {
    }
}
