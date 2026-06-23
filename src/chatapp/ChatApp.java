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
    private final SecurityService securityService = new SecurityService(database, config);
    private final AuditLogService auditLogService = new AuditLogService(database);
    private final ReportService reportService = new ReportService(database);
    private final BackupService backupService = new BackupService(database);
    private final AdminDashboardService adminDashboardService = new AdminDashboardService(database);
    private final WebhookService webhookService = new WebhookService(config);
    private final PendingMessageService pendingMessageService = new PendingMessageService();
    private final UserSettings userSettings = UserSettings.load();

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
    private Button newGroupButton;
    private Button manageGroupButton;
    private Button pinConversationButton;
    private Button exportConversationButton;
    private Button advancedSearchButton;
    private Button taskDrawerButton;
    private TaskDrawerPanel taskDrawer;
    private HBox sidebar;
    private VBox conversationPanel;
    private VBox navRail;
    private VBox navItemsBox;
    private Button notificationsNavButton;
    private Button tasksNavButton;
    private String activeNav = "CHAT";
    private String conversationFilter = "ALL";
    private String searchMode = "CHAT";
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
        stage.setTitle("Chat nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ cÃƒÆ’Ã‚Â´ng ty");
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
        Label title = new Label("Chat nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢");
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Ãƒâ€žÃ‚ÂÃƒâ€žÃ†â€™ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p bÃƒÂ¡Ã‚ÂºÃ‚Â±ng tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n cÃƒÆ’Ã‚Â´ng ty");
        subtitle.getStyleClass().add("login-subtitle");

        TextField username = new TextField();
        username.setPromptText("TÃƒÆ’Ã‚Âªn tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n");
        username.getStyleClass().add("login-input");
        PasswordField password = new PasswordField();
        password.setPromptText("MÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u");
        password.getStyleClass().add("login-input");

        Button login = new Button("Ãƒâ€žÃ‚ÂÃƒâ€žÃ†â€™ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p");
        login.getStyleClass().add("primary-button");
        login.setMaxWidth(Double.MAX_VALUE);

        Label status = new Label();
        status.getStyleClass().add("error-text");
        status.setWrapText(true);

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
                    status.setText("Sai tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n/mÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u hoÃƒÂ¡Ã‚ÂºÃ‚Â·c tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n nhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t.");
                    return;
                }
                handlePostPasswordLogin(status);
            }, e -> {
                login.setDisable(false);
                status.setText("KhÃƒÆ’Ã‚Â´ng thÃƒÂ¡Ã‚Â»Ã†â€™ kÃƒÂ¡Ã‚ÂºÃ‚Â¿t nÃƒÂ¡Ã‚Â»Ã¢â‚¬Ëœi: " + e.getMessage());
            });
        };

        login.setOnAction(e -> doLogin.run());
        password.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                doLogin.run();
            }
        });

        VBox card = new VBox(16, logo, title, subtitle, username, password, login, status);
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
            }, e -> status.setText("KhÃƒÆ’Ã‚Â´ng kiÃƒÂ¡Ã‚Â»Ã†â€™m tra Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c 2FA: " + e.getMessage()));
            return;
        }
        finishLogin(status);
    }

    private void setupAdminTwoFactor(Label status) {
        String secret = securityService.generateTotpSecret();
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("ThiÃƒÂ¡Ã‚ÂºÃ‚Â¿t lÃƒÂ¡Ã‚ÂºÃ‚Â­p 2FA Admin");
        styleDialog(dialog);
        ButtonType verify = new ButtonType("XÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(verify, ButtonType.CANCEL);
        TextField code = new TextField();
        code.getStyleClass().add("login-input");
        String currentCode = securityService.currentTotp(secret);
        VBox content = new VBox(10,
                label("LÃƒâ€ Ã‚Â°u secret nÃƒÆ’Ã‚Â y vÃƒÆ’Ã‚Â o ÃƒÂ¡Ã‚Â»Ã‚Â©ng dÃƒÂ¡Ã‚Â»Ã‚Â¥ng OTP:", "dialog-label"),
                settingLine("Secret", secret),
                settingLine("MÃƒÆ’Ã‚Â£ hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n tÃƒÂ¡Ã‚ÂºÃ‚Â¡i", currentCode),
                label("NhÃƒÂ¡Ã‚ÂºÃ‚Â­p mÃƒÆ’Ã‚Â£ OTP Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ bÃƒÂ¡Ã‚ÂºÃ‚Â­t 2FA:", "dialog-label"),
                code);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == verify ? code.getText() : null);
        dialog.showAndWait().ifPresentOrElse(value -> {
            if (!securityService.verifyTotp(secret, value)) {
                status.setText("MÃƒÆ’Ã‚Â£ 2FA khÃƒÆ’Ã‚Â´ng Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng.");
                auditLogService.log(currentUser.username, "2FA_SETUP_FAILED", "USER", currentUser.username, "");
                return;
            }
            runDb(() -> {
                authService.saveTwoFactorSecret(currentUser.username, secret);
                auditLogService.log(currentUser.username, "2FA_ENABLED", "USER", currentUser.username, "");
                return true;
            }, ok -> finishLogin(status), e -> status.setText("KhÃƒÆ’Ã‚Â´ng lÃƒâ€ Ã‚Â°u Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c 2FA: " + e.getMessage()));
        }, () -> status.setText("Admin cÃƒÂ¡Ã‚ÂºÃ‚Â§n bÃƒÂ¡Ã‚ÂºÃ‚Â­t 2FA Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€žÃ†â€™ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p."));
    }

    private void verifyAdminTwoFactor(Label status) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("XÃƒÆ’Ã‚Â¡c thÃƒÂ¡Ã‚Â»Ã‚Â±c 2FA");
        styleDialog(dialog);
        ButtonType verify = new ButtonType("XÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n", ButtonBar.ButtonData.OK_DONE);
        ButtonType forgot = new ButtonType("QuÃƒÆ’Ã‚Âªn mÃƒÆ’Ã‚Â£ 2FA?", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(forgot, verify, ButtonType.CANCEL);
        TextField code = new TextField();
        code.getStyleClass().add("login-input");
        dialog.getDialogPane().setContent(new VBox(10, label("NhÃƒÂ¡Ã‚ÂºÃ‚Â­p mÃƒÆ’Ã‚Â£ OTP admin", "dialog-label"), code));
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
                status.setText("MÃƒÆ’Ã‚Â£ 2FA khÃƒÆ’Ã‚Â´ng Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng.");
            }
        }, e -> status.setText("KhÃƒÆ’Ã‚Â´ng xÃƒÆ’Ã‚Â¡c thÃƒÂ¡Ã‚Â»Ã‚Â±c Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c 2FA: " + e.getMessage())), () -> status.setText("BÃƒÂ¡Ã‚ÂºÃ‚Â¡n Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ hÃƒÂ¡Ã‚Â»Ã‚Â§y xÃƒÆ’Ã‚Â¡c thÃƒÂ¡Ã‚Â»Ã‚Â±c 2FA."));
    }

    private void showTwoFactorResetDialog(Label status) {
        Dialog<TwoFactorResetRequest> dialog = new Dialog<>();
        dialog.setTitle("KhÃƒÆ’Ã‚Â´i phÃƒÂ¡Ã‚Â»Ã‚Â¥c 2FA Admin");
        styleDialog(dialog);
        ButtonType reset = new ButtonType("Reset 2FA", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(reset, ButtonType.CANCEL);

        TextField target = new TextField(currentUser == null ? "" : currentUser.username);
        target.setPromptText("Admin cÃƒÂ¡Ã‚ÂºÃ‚Â§n reset 2FA");
        target.getStyleClass().add("login-input");
        TextField confirmer = new TextField();
        confirmer.setPromptText("Admin xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n");
        confirmer.getStyleClass().add("login-input");
        PasswordField confirmerPassword = new PasswordField();
        confirmerPassword.setPromptText("MÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u admin xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n");
        confirmerPassword.getStyleClass().add("login-input");

        VBox content = new VBox(10,
                label("YÃƒÆ’Ã‚Âªu cÃƒÂ¡Ã‚ÂºÃ‚Â§u mÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢t admin khÃƒÆ’Ã‚Â¡c xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ reset 2FA.", "dialog-label"),
                target,
                confirmer,
                confirmerPassword,
                label("Sau khi reset, admin nÃƒÆ’Ã‚Â y phÃƒÂ¡Ã‚ÂºÃ‚Â£i thiÃƒÂ¡Ã‚ÂºÃ‚Â¿t lÃƒÂ¡Ã‚ÂºÃ‚Â­p lÃƒÂ¡Ã‚ÂºÃ‚Â¡i 2FA ÃƒÂ¡Ã‚Â»Ã…Â¸ lÃƒÂ¡Ã‚ÂºÃ‚Â§n Ãƒâ€žÃ¢â‚¬ËœÃƒâ€žÃ†â€™ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p theo.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == reset
                ? new TwoFactorResetRequest(target.getText().trim(), confirmer.getText().trim(), confirmerPassword.getText())
                : null);
        dialog.showAndWait().ifPresent(request -> {
            if (request.targetUsername.isBlank() || request.confirmerUsername.isBlank() || request.confirmerPassword.isBlank()) {
                status.setText("Vui lÃƒÆ’Ã‚Â²ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Â§ tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n cÃƒÂ¡Ã‚ÂºÃ‚Â§n reset vÃƒÆ’Ã‚Â  admin xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n.");
                return;
            }
            runDb(() -> {
                if (!authService.isAdminUser(request.targetUsername)) {
                    throw new IllegalArgumentException("TÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n cÃƒÂ¡Ã‚ÂºÃ‚Â§n reset khÃƒÆ’Ã‚Â´ng phÃƒÂ¡Ã‚ÂºÃ‚Â£i admin.");
                }
                if (request.targetUsername.equalsIgnoreCase(request.confirmerUsername)) {
                    throw new IllegalArgumentException("KhÃƒÆ’Ã‚Â´ng thÃƒÂ¡Ã‚Â»Ã†â€™ tÃƒÂ¡Ã‚Â»Ã‚Â± reset 2FA cÃƒÂ¡Ã‚Â»Ã‚Â§a chÃƒÆ’Ã‚Â­nh mÃƒÆ’Ã‚Â¬nh.");
                }
                if (!authService.hasOtherAdmin(request.targetUsername)) {
                    throw new IllegalArgumentException("KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ admin khÃƒÆ’Ã‚Â¡c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n. Vui lÃƒÆ’Ã‚Â²ng liÃƒÆ’Ã‚Âªn hÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡ IT/quÃƒÂ¡Ã‚ÂºÃ‚Â£n trÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ database.");
                }
                if (!authService.verifyAdminCredentials(request.confirmerUsername, request.confirmerPassword)) {
                    throw new IllegalArgumentException("Admin xÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â­n khÃƒÆ’Ã‚Â´ng hÃƒÂ¡Ã‚Â»Ã‚Â£p lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡ hoÃƒÂ¡Ã‚ÂºÃ‚Â·c sai mÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u.");
                }
                authService.resetTwoFactor(request.targetUsername);
                auditLogService.log(request.confirmerUsername, "2FA_RESET", "USER", request.targetUsername, "Reset by another admin");
                return true;
            }, ok -> status.setText("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ reset 2FA. Admin cÃƒÂ¡Ã‚ÂºÃ‚Â§n thiÃƒÂ¡Ã‚ÂºÃ‚Â¿t lÃƒÂ¡Ã‚ÂºÃ‚Â­p lÃƒÂ¡Ã‚ÂºÃ‚Â¡i ÃƒÂ¡Ã‚Â»Ã…Â¸ lÃƒÂ¡Ã‚ÂºÃ‚Â§n Ãƒâ€žÃ¢â‚¬ËœÃƒâ€žÃ†â€™ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p theo."),
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
        }, ok -> showChat(), e -> status.setText("KhÃƒÆ’Ã‚Â´ng thÃƒÂ¡Ã‚Â»Ã†â€™ khÃƒÂ¡Ã‚Â»Ã…Â¸i tÃƒÂ¡Ã‚ÂºÃ‚Â¡o phiÃƒÆ’Ã‚Âªn chat: " + e.getMessage()));
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
        sidebar = new HBox(0);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(462);
        sidebar.setMinWidth(430);
        sidebar.setMaxWidth(500);

        Node profileAvatar = avatarNode(currentUser.displayName, "profile-avatar", currentUser.username);
        Label presenceDot = new Label();
        presenceDot.getStyleClass().addAll("presence-dot", "presence-" + userSettings.presenceStatus.toLowerCase());
        StackPane avatarWrap = new StackPane(profileAvatar, presenceDot);
        StackPane.setAlignment(presenceDot, Pos.BOTTOM_RIGHT);
        Label name = new Label(currentUser.displayName);
        name.getStyleClass().add("profile-name");
        Label role = new Label((currentUser.isAdmin() ? "QuÃƒÂ¡Ã‚ÂºÃ‚Â£n trÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ viÃƒÆ’Ã‚Âªn" : "NhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn") + " - " + currentUser.companyOwner);
        role.getStyleClass().add("profile-role");
        Button accountMenu = iconButton("chevron", "TÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n", "account-menu-button");
        accountMenu.setOnAction(e -> showAccountMenu(accountMenu));
        HBox profileName = new HBox(4, name, accountMenu);
        profileName.setAlignment(Pos.CENTER);
        Button presence = new Button(presenceText());
        presence.getStyleClass().add("presence-button");
        presence.setOnAction(e -> showPresenceMenu(presence));
        VBox profile = new VBox(7, avatarWrap, profileName, role, presence);
        profile.setAlignment(Pos.CENTER);
        profile.getStyleClass().add("profile-bar");

        Button homeNav = navItem("home", "TÃƒÂ¡Ã‚Â»Ã¢â‚¬ÂNG QUAN", "HOME", this::showHomePanel);
        Button chatNav = navItem("chat", "CHAT", "CHAT", this::rebuildSidebarOnly);
        Button contactNav = navItem("contact", "CONTACT", "CONTACT", this::showContactPanel);
        notificationsNavButton = navItem("bell", "THÃƒÆ’Ã¢â‚¬ÂNG BÃƒÆ’Ã‚ÂO", "NOTIFICATIONS", this::showNotificationsPanel);
        tasksNavButton = navItem("tasks", "TASKS", "TASKS", this::showMyTasksPanel);
        Button calendarNav = navItem("calendar", "CALENDAR", "CALENDAR", this::showCalendarPanel);
        Button settingsNav = navItem("settings", "SETTINGS", "SETTINGS", this::showSettingsDialog);
        navItemsBox = new VBox(10, homeNav, chatNav, contactNav, notificationsNavButton, tasksNavButton, calendarNav, settingsNav);
        navItemsBox.getStyleClass().add("nav-items");
        VBox.setVgrow(navItemsBox, Priority.ALWAYS);
        Button collapse = navItem("collapse", "COLLAPSE", null, this::toggleSidebarCollapsed);
        Button logout = navItem("logout", "LOG OUT", null, this::logoutAndExit);
        logout.getStyleClass().add("logout-nav-item");
        navRail = new VBox(24, profile, navItemsBox, collapse, logout);
        navRail.getStyleClass().add("nav-rail");
        navRail.setAlignment(Pos.TOP_CENTER);
        navRail.setPrefWidth(142);
        navRail.setMinWidth(130);

        Label panelTitle = new Label("Chats");
        panelTitle.getStyleClass().add("sidebar-title");
        Label panelSub = new Label("Recent Chats");
        panelSub.getStyleClass().add("sidebar-subtitle");
        VBox titleBlock = new VBox(2, panelTitle, panelSub);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        Button newDirect = new Button("Chat mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi");
        newDirect.getStyleClass().add("sidebar-action");
        newDirect.setGraphic(svgIcon("new-chat", 15));
        installHoverScale(newDirect, 1.025);
        newDirect.setOnAction(e -> openDirectDialog());
        HBox panelHeader = new HBox(12, titleBlock, newDirect);
        panelHeader.getStyleClass().add("conversation-panel-header");
        panelHeader.setAlignment(Pos.CENTER_LEFT);

        conversationSearch = new TextField();
        conversationSearch.setPromptText("TÃƒÆ’Ã‚Â¬m hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i");
        conversationSearch.getStyleClass().add("search-field");
        installFocusScale(conversationSearch);
        conversationSearch.textProperty().addListener((obs, old, value) -> refreshConversationFilter());
        Label searchIcon = new Label();
        searchIcon.setGraphic(svgIcon("search", 15));
        searchIcon.getStyleClass().add("search-icon");
        Label filter = new Label("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
        filter.getStyleClass().add("search-filter");
        HBox searchRow = new HBox(8, searchIcon, conversationSearch, filter);
        searchRow.getStyleClass().add("search-row");
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(conversationSearch, Priority.ALWAYS);

        newGroupButton = new Button("NhÃƒÆ’Ã‚Â³m");
        newGroupButton.getStyleClass().add("sidebar-action");
        newGroupButton.setGraphic(svgIcon("users", 15));
        installHoverScale(newGroupButton, 1.025);
        newGroupButton.setDisable(!currentUser.canManageGroups());
        newGroupButton.setOnAction(e -> openGroupDialog(null));
        HBox actions = new HBox(10, newGroupButton);
        actions.getStyleClass().add("sidebar-actions");
        HBox.setHgrow(newGroupButton, Priority.ALWAYS);
        newGroupButton.setMaxWidth(Double.MAX_VALUE);

        conversationList = new ListView<>(visibleConversations);
        conversationList.getStyleClass().add("conversation-list");
        conversationList.setCellFactory(list -> new SidebarCells.ConversationCell());
        conversationList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && !loadingConversations) {
                currentConversation = selected;
                hideChatHead();
                forceScrollToBottom = true;
                refreshMessages(true);
                loadTasksAsync();
            }
        });
        VBox.setVgrow(conversationList, Priority.ALWAYS);

        conversationPanel = new VBox(14, panelHeader, searchRow, searchModeRow(), filterRow(), actions, conversationList);
        conversationPanel.getStyleClass().add("conversation-panel");
        HBox.setHgrow(conversationPanel, Priority.ALWAYS);
        sidebar.getChildren().addAll(navRail, conversationPanel);
        applySidebarCollapsed();
        return sidebar;
    }

    private Button navItem(String icon, String text, String navKey, Runnable action) {
        Button button = new Button(text);
        button.setGraphic(iconBadge(icon, 19, "nav-icon-wrap"));
        button.getStyleClass().add("nav-item");
        button.setUserData(navKey);
        if (navKey != null && navKey.equals(activeNav)) {
            button.getStyleClass().add("nav-item-active");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(e -> {
            if (navKey != null) {
                activeNav = navKey;
                refreshNavActiveStyles();
            }
            action.run();
        });
        installHoverScale(button, 1.02);
        return button;
    }

    private Node iconBadge(String iconName, double size, String styleClass) {
        StackPane badge = new StackPane(svgIcon(iconName, size));
        badge.getStyleClass().add(styleClass);
        badge.setMinSize(34, 34);
        badge.setPrefSize(34, 34);
        badge.setMaxSize(34, 34);
        return badge;
    }

    private void refreshNavActiveStyles() {
        if (navItemsBox == null) return;
        for (Node node : navItemsBox.getChildren()) {
            if (node instanceof Button button) {
                Object key = button.getUserData();
                boolean active = key != null && key.equals(activeNav);
                if (active && !button.getStyleClass().contains("nav-item-active")) {
                    button.getStyleClass().add("nav-item-active");
                } else if (!active) {
                    button.getStyleClass().remove("nav-item-active");
                }
            }
        }
    }

    private Button chipButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("filter-chip");
        button.setOnAction(e -> action.run());
        installHoverScale(button, 1.02);
        return button;
    }

    private void setConversationFilter(String filter) {
        conversationFilter = filter;
        refreshConversationFilter();
    }

    private HBox filterRow() {
        HBox row = new HBox(7,
                chipButton("TÃƒÂ¡Ã‚ÂºÃ‚Â¥t cÃƒÂ¡Ã‚ÂºÃ‚Â£", () -> setConversationFilter("ALL")),
                chipButton("ChÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc", () -> setConversationFilter("UNREAD")),
                chipButton("1-1", () -> setConversationFilter("DIRECT")),
                chipButton("NhÃƒÆ’Ã‚Â³m", () -> setConversationFilter("GROUP")),
                chipButton("CÃƒÆ’Ã‚Â³ task", () -> setConversationFilter("TASK")),
                chipButton("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ ghim", () -> setConversationFilter("PINNED")));
        row.getStyleClass().add("filter-row");
        return row;
    }

    private HBox searchModeRow() {
        HBox row = new HBox(7,
                chipButton("Chat", () -> searchMode = "CHAT"),
                chipButton("NgÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi", () -> {
                    searchMode = "USER";
                    showContactPanel();
                }),
                chipButton("Task", () -> {
                    searchMode = "TASK";
                    showMyTasksPanel();
                }),
                chipButton("Tin", () -> showToast("TÃƒÆ’Ã‚Â¬m tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n sÃƒÂ¡Ã‚ÂºÃ‚Â½ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c bÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ sung.")),
                chipButton("File", () -> showToast("TÃƒÆ’Ã‚Â¬m file sÃƒÂ¡Ã‚ÂºÃ‚Â½ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c bÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ sung.")));
        row.getStyleClass().add("filter-row");
        return row;
    }

    private void showSidebarPage(String title, String subtitle, Node content) {
        if (conversationPanel == null) return;
        Label pageTitle = new Label(title);
        pageTitle.getStyleClass().add("sidebar-title");
        Label pageSub = new Label(subtitle);
        pageSub.getStyleClass().add("sidebar-subtitle");
        Button back = new Button("Chat");
        back.getStyleClass().add("sidebar-action");
        back.setGraphic(svgIcon("chat", 15));
        back.setOnAction(e -> rebuildSidebarOnly());
        HBox header = new HBox(12, new VBox(2, pageTitle, pageSub), back);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(header.getChildren().get(0), Priority.ALWAYS);
        conversationPanel.getChildren().setAll(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);
    }

    private void rebuildSidebarOnly() {
        activeNav = "CHAT";
        if (appRoot != null) {
            appRoot.setCenter(buildWorkspace());
            refreshConversationFilter();
        } else {
            refreshNavActiveStyles();
        }
    }

    private void showHomePanel() {
        activeNav = "HOME";
        refreshNavActiveStyles();
        int unread = conversations.stream().mapToInt(c -> c.unreadCount).sum();
        runDb(() -> {
            List<ChatTask> tasks = chatService.listTasksForUser(currentUser);
            LocalDate today = LocalDate.now();
            int dueToday = (int) tasks.stream()
                    .filter(t -> t.deadline != null && !"DONE".equals(t.status))
                    .filter(t -> t.deadline.toLocalDate().equals(today))
                    .count();
            return new HomeSummary(
                    unread,
                    chatService.countOverdueTasks(currentUser),
                    chatService.listMentionsForUser(currentUser).size(),
                    chatService.countPendingWorkflows(currentUser),
                    dueToday);
        }, summary -> {
            if (summary != null) {
                Node content = HomePanel.build(summary, this::openFirstUnreadConversation, this::showMyTasksPanel, this::showNotificationsPanel, this::showCalendarPanel);
                showSidebarPage("TÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ng quan hÃƒÆ’Ã‚Â´m nay", "CÃƒÆ’Ã‚Â¡c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c cÃƒÂ¡Ã‚ÂºÃ‚Â§n chÃƒÆ’Ã‚Âº ÃƒÆ’Ã‚Â½ ngay", content);
                return;
            }
            VBox metrics = new VBox(10,
                    settingLine("Tin chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc", String.valueOf(summary.unread())),
                    settingLine("Task quÃƒÆ’Ã‚Â¡ hÃƒÂ¡Ã‚ÂºÃ‚Â¡n", String.valueOf(summary.overdueTasks())),
                    settingLine("Workflow chÃƒÂ¡Ã‚Â»Ã‚Â duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t", String.valueOf(summary.pendingWorkflows())),
                    settingLine("Deadline hÃƒÆ’Ã‚Â´m nay", String.valueOf(summary.dueToday())),
                    settingLine("NhÃƒÂ¡Ã‚ÂºÃ‚Â¯c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n tÃƒÆ’Ã‚Â´i", String.valueOf(summary.mentions())));
            metrics.getStyleClass().add("settings-section");

            Button openUnread = new Button("MÃƒÂ¡Ã‚Â»Ã…Â¸ chat chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc");
            openUnread.getStyleClass().add("sidebar-action");
            openUnread.setGraphic(svgIcon("chat", 15));
            openUnread.setOnAction(e -> openFirstUnreadConversation());

            Button myTasks = new Button("ViÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c cÃƒÂ¡Ã‚Â»Ã‚Â§a tÃƒÆ’Ã‚Â´i");
            myTasks.getStyleClass().add("sidebar-action");
            myTasks.setGraphic(svgIcon("tasks", 15));
            myTasks.setOnAction(e -> showMyTasksPanel());

            Button workflows = new Button("Workflow chÃƒÂ¡Ã‚Â»Ã‚Â duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t");
            workflows.getStyleClass().add("sidebar-action");
            workflows.setGraphic(svgIcon("bell", 15));
            workflows.setOnAction(e -> showNotificationsPanel());

            Button calendar = new Button("LÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch tuÃƒÂ¡Ã‚ÂºÃ‚Â§n nÃƒÆ’Ã‚Â y");
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
                content.getChildren().add(emptyState("HÃƒÆ’Ã‚Â´m nay khÃƒÆ’Ã‚Â¡ yÃƒÆ’Ã‚Âªn tÃƒâ€žÃ‚Â©nh", "KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ tin chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc, task quÃƒÆ’Ã‚Â¡ hÃƒÂ¡Ã‚ÂºÃ‚Â¡n hoÃƒÂ¡Ã‚ÂºÃ‚Â·c deadline cÃƒÂ¡Ã‚ÂºÃ‚Â§n xÃƒÂ¡Ã‚Â»Ã‚Â­ lÃƒÆ’Ã‚Â½ ngay."));
            }
            showSidebarPage("TÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ng quan hÃƒÆ’Ã‚Â´m nay", "CÃƒÆ’Ã‚Â¡c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c cÃƒÂ¡Ã‚ÂºÃ‚Â§n chÃƒÆ’Ã‚Âº ÃƒÆ’Ã‚Â½ ngay", content);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ng quan", e));
    }

    private void openFirstUnreadConversation() {
        conversations.stream()
                .filter(c -> c.unreadCount > 0)
                .findFirst()
                .ifPresentOrElse(conversation -> selectConversation(conversation.id), () -> showInfo("KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc."));
    }

    private void showContactPanel() {
        activeNav = "CONTACT";
        refreshNavActiveStyles();
        runDb(() -> chatService.listCompanyUsers(currentUser), users -> {
            ObservableList<ChatUser> visibleUsers = FXCollections.observableArrayList(users);
            TextField search = new TextField();
            search.setPromptText("TÃƒÆ’Ã‚Â¬m tÃƒÆ’Ã‚Âªn, tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n, chÃƒÂ¡Ã‚Â»Ã‚Â©c vÃƒÂ¡Ã‚Â»Ã‚Â¥");
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
                    }, ex -> showError("KhÃƒÆ’Ã‚Â´ng mÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c chat", ex));
                }
            });
            VBox content = new VBox(10, search, list);
            VBox.setVgrow(list, Priority.ALWAYS);
            showSidebarPage("Danh bÃƒÂ¡Ã‚ÂºÃ‚Â¡", "NhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn trong cÃƒÆ’Ã‚Â´ng ty", content);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c danh bÃƒÂ¡Ã‚ÂºÃ‚Â¡", e));
    }

    private void showMyTasksPanel() {
        activeNav = "TASKS";
        refreshNavActiveStyles();
        runDb(() -> chatService.listTasksForUser(currentUser), tasks -> {
            TextField search = new TextField();
            search.setPromptText("TÃƒÆ’Ã‚Â¬m task, ngÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi thÃƒÂ¡Ã‚Â»Ã‚Â±c hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n");
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
                    box.getChildren().add(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "CÃƒÆ’Ã‚Â¡c cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c giao sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
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
            showSidebarPage("ViÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c cÃƒÂ¡Ã‚Â»Ã‚Â§a tÃƒÆ’Ã‚Â´i", "TÃƒÂ¡Ã‚ÂºÃ‚Â¥t cÃƒÂ¡Ã‚ÂºÃ‚Â£ cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c liÃƒÆ’Ã‚Âªn quan", content);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", e));
    }

    private void showNotificationsPanel() {
        activeNav = "NOTIFICATIONS";
        refreshNavActiveStyles();
        runDb(() -> new NotificationSummary(
                chatService.countOverdueTasks(currentUser),
                chatService.listMentionsForUser(currentUser).size(),
                chatService.countPendingWorkflows(currentUser)), summary -> {
            VBox box = new VBox(10);
            box.getStyleClass().add("sidebar-page-list");
            int unread = conversations.stream().mapToInt(c -> c.unreadCount).sum();
            box.getChildren().add(settingLine("Tin chÃƒâ€ Ã‚Â°a Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Âc", String.valueOf(unread)));
            box.getChildren().add(settingLine("NhÃƒÂ¡Ã‚ÂºÃ‚Â¯c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n tÃƒÆ’Ã‚Â´i", String.valueOf(summary.mentions)));
            box.getChildren().add(settingLine("Task quÃƒÆ’Ã‚Â¡ hÃƒÂ¡Ã‚ÂºÃ‚Â¡n", String.valueOf(summary.overdueTasks)));
            box.getChildren().add(settingLine("Workflow chÃƒÂ¡Ã‚Â»Ã‚Â duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t", String.valueOf(summary.pendingWorkflows)));
            if (unread == 0 && summary.mentions == 0 && summary.overdueTasks == 0 && summary.pendingWorkflows == 0) {
                box.getChildren().add(emptyState("KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ thÃƒÆ’Ã‚Â´ng bÃƒÆ’Ã‚Â¡o mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi", "CÃƒÆ’Ã‚Â¡c cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t quan trÃƒÂ¡Ã‚Â»Ã‚Âng sÃƒÂ¡Ã‚ÂºÃ‚Â½ xuÃƒÂ¡Ã‚ÂºÃ‚Â¥t hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
            }
            showSidebarPage("ThÃƒÆ’Ã‚Â´ng bÃƒÆ’Ã‚Â¡o", "TÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ng hÃƒÂ¡Ã‚Â»Ã‚Â£p cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi", box);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c thÃƒÆ’Ã‚Â´ng bÃƒÆ’Ã‚Â¡o", e));
    }

    private void showCalendarPanel() {
        activeNav = "CALENDAR";
        refreshNavActiveStyles();
        runDb(() -> chatService.listTasksForUser(currentUser), tasks -> {
            VBox box = new VBox(10);
            box.getStyleClass().add("sidebar-page-list");
            LocalDate today = LocalDate.now();
            List<ChatTask> due = tasks.stream()
                    .filter(t -> t.deadline != null && !"DONE".equals(t.status))
                    .filter(t -> !t.deadline.toLocalDate().isAfter(today.plusDays(7)))
                    .collect(Collectors.toList());
            if (due.isEmpty()) {
                box.getChildren().add(emptyState("LÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch trÃƒÂ¡Ã‚Â»Ã¢â‚¬Ëœng", "KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ deadline trong 7 ngÃƒÆ’Ã‚Â y tÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi."));
            } else {
                for (ChatTask task : due) box.getChildren().add(taskCard(task));
            }
            ScrollPane scroll = new ScrollPane(box);
            scroll.getStyleClass().add("task-scroll");
            scroll.setFitToWidth(true);
            showSidebarPage("LÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch", "Deadline hÃƒÆ’Ã‚Â´m nay vÃƒÆ’Ã‚Â  tuÃƒÂ¡Ã‚ÂºÃ‚Â§n nÃƒÆ’Ã‚Â y", scroll);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch", e));
    }

    private void toggleSidebarCollapsed() {
        userSettings.sidebarCollapsed = !userSettings.sidebarCollapsed;
        userSettings.save();
        applySidebarCollapsed();
    }

    private void applySidebarCollapsed() {
        if (conversationPanel == null || sidebar == null || navRail == null) return;
        boolean collapsed = userSettings.sidebarCollapsed;
        conversationPanel.setVisible(!collapsed);
        conversationPanel.setManaged(!collapsed);
        conversationPanel.setMouseTransparent(collapsed);
        navRail.setPrefWidth(collapsed ? 462 : 142);
        navRail.setMinWidth(collapsed ? 430 : 130);
        navRail.setMaxWidth(collapsed ? 500 : 160);
        sidebar.setPrefWidth(462);
        sidebar.setMinWidth(430);
        sidebar.setMaxWidth(500);
    }

    private String presenceText() {
        return switch (userSettings.presenceStatus == null ? "ONLINE" : userSettings.presenceStatus) {
            case "BUSY" -> "Ãƒâ€žÃ‚Âang bÃƒÂ¡Ã‚ÂºÃ‚Â­n";
            case "DO_NOT_DISTURB" -> "KhÃƒÆ’Ã‚Â´ng lÃƒÆ’Ã‚Â m phiÃƒÂ¡Ã‚Â»Ã‚Ân";
            case "AWAY" -> "VÃƒÂ¡Ã‚ÂºÃ‚Â¯ng mÃƒÂ¡Ã‚ÂºÃ‚Â·t";
            default -> "Online";
        };
    }

    private void showPresenceMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        addPresenceItem(menu, "ONLINE", "Online");
        addPresenceItem(menu, "BUSY", "Ãƒâ€žÃ‚Âang bÃƒÂ¡Ã‚ÂºÃ‚Â­n");
        addPresenceItem(menu, "DO_NOT_DISTURB", "KhÃƒÆ’Ã‚Â´ng lÃƒÆ’Ã‚Â m phiÃƒÂ¡Ã‚Â»Ã‚Ân");
        addPresenceItem(menu, "AWAY", "VÃƒÂ¡Ã‚ÂºÃ‚Â¯ng mÃƒÂ¡Ã‚ÂºÃ‚Â·t");
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
        headerTitle = new Label("ChÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i");
        headerTitle.getStyleClass().add("chat-title");
        headerDetail = new Label("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ cÃƒÆ’Ã‚Â´ng ty");
        headerDetail.getStyleClass().add("chat-subtitle");
        VBox titleBlock = new VBox(3, headerTitle, headerDetail);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        pinConversationButton = iconButton("pin", "Ghim hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i", "header-button");
        pinConversationButton.getStyleClass().add("header-button");
        pinConversationButton.setOnAction(e -> toggleConversationPin());
        manageGroupButton = new Button("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÆ’Ã‚Â³m");
        manageGroupButton.getStyleClass().add("header-button");
        manageGroupButton.setText("");
        manageGroupButton.setGraphic(svgIcon("users", 17));
        manageGroupButton.setTooltip(new Tooltip("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÆ’Ã‚Â³m"));
        installHoverScale(manageGroupButton, 1.05);
        manageGroupButton.setOnAction(e -> openGroupDialog(currentConversation));
        taskDrawerButton = iconButton("tasks", "BÃƒÂ¡Ã‚ÂºÃ‚Â£ng cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "header-button");
        taskDrawerButton.setOnAction(e -> toggleTaskDrawer());
        exportConversationButton = iconButton("download", "XuÃƒÂ¡Ã‚ÂºÃ‚Â¥t lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat CSV", "header-button");
        exportConversationButton.setOnAction(e -> exportCurrentConversationCsv());

        HBox headerTop = new HBox(12, headerAvatar, titleBlock, pinConversationButton, manageGroupButton, taskDrawerButton, exportConversationButton);
        headerTop.setAlignment(Pos.CENTER_LEFT);

        messageSearch = new TextField();
        messageSearch.setPromptText("TÃƒÆ’Ã‚Â¬m trong cuÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢c trÃƒÆ’Ã‚Â² chuyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n");
        messageSearch.getStyleClass().add("message-search");
        installFocusScale(messageSearch);
        messageSearch.textProperty().addListener((obs, old, value) -> refreshMessages(true));
        advancedSearchButton = iconButton("filter", "TÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m nÃƒÆ’Ã‚Â¢ng cao", "header-button");
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

        replyLabel = new Label();
        replyLabel.getStyleClass().add("reply-composer");
        replyLabel.setVisible(false);
        replyLabel.setManaged(false);

        attachmentLabel = new Label();
        attachmentLabel.getStyleClass().add("attachment-label");
        attachmentLabel.setVisible(false);
        attachmentLabel.setManaged(false);

        input = new TextArea();
        input.setPromptText("NhÃƒÂ¡Ã‚ÂºÃ‚Â­p tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n, Ctrl+Enter Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ gÃƒÂ¡Ã‚Â»Ã‚Â­i");
        input.getStyleClass().add("composer-input");
        input.setPrefRowCount(2);
        input.setWrapText(true);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && e.isControlDown()) {
                sendCurrentMessage();
                e.consume();
            }
        });
        input.textProperty().addListener((obs, old, value) -> publishTypingIfNeeded());
        input.addEventHandler(KeyEvent.KEY_RELEASED, e -> updateMentionSuggestions());

        Button plus = composerIconButton("+", "MÃƒÂ¡Ã‚Â»Ã…Â¸ thÃƒÆ’Ã‚Âªm tÃƒÆ’Ã‚Â¹y chÃƒÂ¡Ã‚Â»Ã‚Ân");
        applyIcon(plus, "plus");
        plus.setOnAction(e -> showPlusMenu(plus));
        Button clearFiles = composerIconButton("ÃƒÂ°Ã…Â¸Ã¢â‚¬â€Ã¢â‚¬Ëœ", "XÃƒÆ’Ã‚Â³a file Ãƒâ€žÃ¢â‚¬Ëœang chÃƒÂ¡Ã‚Â»Ã‚Ân");
        applyIcon(clearFiles, "trash");
        clearFiles.setOnAction(e -> {
            selectedFiles.clear();
            updateAttachmentLabel();
        });
        Button icons = composerIconButton("ÃƒÂ°Ã…Â¸Ã‹Å“Ã…Â ", "ChÃƒÆ’Ã‚Â¨n icon cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc");
        applyIcon(icons, "smile");
        icons.setOnAction(e -> showEmojiMenu(icons));
        voiceButton = composerIconButton("ÃƒÂ°Ã…Â¸Ã…Â½Ã¢â€žÂ¢", "Ghi ÃƒÆ’Ã‚Â¢m voice message");
        applyIcon(voiceButton, "mic");
        voiceButton.setOnAction(e -> toggleVoiceRecording());
        Button send = composerIconButton("ÃƒÂ¢Ã…Â¾Ã‚Â¤", "GÃƒÂ¡Ã‚Â»Ã‚Â­i tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
        applyIcon(send, "send");
        send.getStyleClass().add("send-icon-button");
        send.setOnAction(e -> sendCurrentMessage());

        HBox inputRow = new HBox(10, plus, clearFiles, icons, voiceButton, input, send);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);
        VBox composer = new VBox(8, replyLabel, attachmentLabel, inputRow);
        composer.getStyleClass().add("composer");
        StackPane composerShell = new StackPane(composer);
        composerShell.getStyleClass().add("composer-shell");
        pane.setBottom(composerShell);
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
        runDb(() -> chatService.listTasksByConversation(currentUser, conversationId), tasks -> {
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
        runDb(() -> chatService.updateTaskStatus(currentUser, task.id, status), updated -> {
            loadTasksAsync();
            if ("DONE".equals(status)) {
                webhookService.notifyIntegrations("Task hoÃƒÆ’Ã‚Â n thÃƒÆ’Ã‚Â nh", task.title + " - " + task.assigneeUsername + " (+" + task.kpiPoints + " KPI)",
                        userSettings.slackWebhookUrl, userSettings.teamsWebhookUrl);
                refreshMessages(true);
                publishRealtime("MESSAGE_CREATED", conversationId);
            }
            publishRealtime("TASK_UPDATED", conversationId);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", e));
    }

    private boolean isTaskOverdue(ChatTask task) {
        return task.deadline != null && !"DONE".equals(task.status) && task.deadline.isBefore(LocalDateTime.now());
    }

    private String taskDeadlineText(ChatTask task) {
        if (task.deadline == null) return "KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ hÃƒÂ¡Ã‚ÂºÃ‚Â¡n chÃƒÆ’Ã‚Â³t";
        String prefix = isTaskOverdue(task) ? "QuÃƒÆ’Ã‚Â¡ hÃƒÂ¡Ã‚ÂºÃ‚Â¡n: " : "HÃƒÂ¡Ã‚ÂºÃ‚Â¡n: ";
        return prefix + task.deadline.format(TASK_TIME);
    }

    private static String taskStatusText(String status) {
        return switch (status) {
            case "IN_PROGRESS" -> "Ãƒâ€žÃ‚Âang lÃƒÆ’Ã‚Â m";
            case "REVIEW" -> "ChÃƒÂ¡Ã‚Â»Ã‚Â duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t";
            case "DONE" -> "HoÃƒÆ’Ã‚Â n thÃƒÆ’Ã‚Â nh";
            default -> "CÃƒÂ¡Ã‚ÂºÃ‚Â§n lÃƒÆ’Ã‚Â m";
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
            showError("LÃƒÂ¡Ã‚Â»Ã¢â‚¬â€i tÃƒÂ¡Ã‚ÂºÃ‚Â£i hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i", e);
        });
    }

    private void loadConversationsAsync() {
        loadConversations();
    }

    private void refreshConversationFilter() {
        String query = Texts.normalize(conversationSearch == null ? "" : conversationSearch.getText());
        List<Conversation> filtered = conversations.stream()
                .filter(this::matchesConversationFilter)
                .filter(c -> query.isBlank()
                        || Texts.normalize(c.title).contains(query)
                        || Texts.normalize(c.lastMessage).contains(query))
                .collect(Collectors.toList());
        visibleConversations.setAll(filtered);
    }

    private boolean matchesConversationFilter(Conversation c) {
        return switch (conversationFilter) {
            case "UNREAD" -> c.unreadCount > 0;
            case "DIRECT" -> "DIRECT".equals(c.type);
            case "GROUP" -> "GROUP".equals(c.type) || "COMPANY".equals(c.type) || "DEPARTMENT".equals(c.type);
            case "TASK" -> conversationIdsWithTasks.contains(c.id);
            case "PINNED" -> c.pinned;
            default -> true;
        };
    }

    private void loadConversationTaskMarkers() {
        runDb(() -> chatService.conversationIdsWithTasks(currentUser), ids -> {
            conversationIdsWithTasks.clear();
            conversationIdsWithTasks.addAll(ids);
            refreshConversationFilter();
        }, e -> {
        });
    }

    private void updateNavBadges(int unread) {
        if (notificationsNavButton != null) {
            notificationsNavButton.setText(unread > 0 ? "NOTIFICATIONS (" + unread + ")" : "NOTIFICATIONS");
        }
        if (tasksNavButton != null) {
            runDb(() -> chatService.countOverdueTasks(currentUser), overdue -> {
                if (tasksNavButton != null) {
                    tasksNavButton.setText(overdue > 0 ? "TASKS (" + overdue + ")" : "TASKS");
                }
            }, e -> {
            });
        }
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
            pinConversationButton.setText(currentConversation.pinned ? "BÃƒÂ¡Ã‚Â»Ã‚Â ghim" : "Ghim");
            pinConversationButton.setText("");
            pinConversationButton.setTooltip(new Tooltip(currentConversation.pinned ? "BÃƒÂ¡Ã‚Â»Ã‚Â ghim hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i" : "Ghim hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i"));
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
                    messageBox.getChildren().setAll(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n", "HÃƒÆ’Ã‚Â£y gÃƒÂ¡Ã‚Â»Ã‚Â­i lÃƒÂ¡Ã‚Â»Ã‚Âi chÃƒÆ’Ã‚Â o Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ bÃƒÂ¡Ã‚ÂºÃ‚Â¯t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â§u cuÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢c trÃƒÆ’Ã‚Â² chuyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n."));
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
            }, e -> showError("LÃƒÂ¡Ã‚Â»Ã¢â‚¬â€i tÃƒÂ¡Ã‚ÂºÃ‚Â£i tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n", e));
        } catch (Exception e) {
            showError("LÃƒÂ¡Ã‚Â»Ã¢â‚¬â€i tÃƒÂ¡Ã‚ÂºÃ‚Â£i tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n", e);
        }
    }

    private boolean isNearBottom() {
        return messageScroll == null || messageScroll.getVvalue() > 0.92;
    }

    private void showEmptyConversation() {
        if (headerAvatar != null) {
            headerAvatar.setText("C");
            headerTitle.setText("ChÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i");
            headerDetail.setText("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n, nhÃƒÆ’Ã‚Â³m vÃƒÆ’Ã‚Â  file nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y");
            pinConversationButton.setDisable(true);
            manageGroupButton.setVisible(false);
            manageGroupButton.setManaged(false);
            messageBox.getChildren().setAll(emptyState("ChÃƒâ€ Ã‚Â°a chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i", "ChÃƒÂ¡Ã‚Â»Ã‚Ân mÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢t cuÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢c trÃƒÆ’Ã‚Â² chuyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n bÃƒÆ’Ã‚Âªn trÃƒÆ’Ã‚Â¡i hoÃƒÂ¡Ã‚ÂºÃ‚Â·c tÃƒÂ¡Ã‚ÂºÃ‚Â¡o chat mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi."));
            currentTasks = new ArrayList<>();
            renderTasks();
        }
    }

    private Node messageNode(ChatMessage msg) {
        boolean mine = currentUser.username.equals(msg.senderUsername);
        VBox bubble = new VBox(7);
        bubble.getStyleClass().add(mine ? "bubble-mine" : "bubble-other");
        bubble.setMaxWidth(640);

        Label name = new Label(mine ? "BÃƒÂ¡Ã‚ÂºÃ‚Â¡n" : msg.senderName);
        name.getStyleClass().add("message-sender");
        Label time = new Label(msg.createdAt.format(MESSAGE_TIME) + (msg.edited ? " - Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ sÃƒÂ¡Ã‚Â»Ã‚Â­a" : ""));
        time.getStyleClass().add("message-time");
        HBox meta = new HBox(8, name, time);
        meta.setAlignment(Pos.CENTER_LEFT);
        bubble.getChildren().add(meta);

        if (msg.pinned) {
            Label pinned = new Label("Tin Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ ghim");
            pinned.getStyleClass().add("pin-chip");
            bubble.getChildren().add(pinned);
        }

        if (msg.replyToId != null) {
            Label reply = new Label("TrÃƒÂ¡Ã‚ÂºÃ‚Â£ lÃƒÂ¡Ã‚Â»Ã‚Âi: " + Texts.shortText(msg.replyPreview, 90));
            reply.getStyleClass().add("reply-preview");
            bubble.getChildren().add(reply);
        }

        if (msg.recalled) {
            Label recalled = new Label("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c thu hÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“i");
            recalled.getStyleClass().add("recalled-text");
            bubble.getChildren().add(recalled);
        } else {
            if ("SALARY_CARD".equals(msg.messageType)) {
                bubble.getChildren().add(salaryCardNode(msg));
            } else if ("WORKFLOW_CARD".equals(msg.messageType)) {
                bubble.getChildren().add(workflowCardNode(msg));
            } else if (msg.body != null && !msg.body.isBlank()) {
                if ("POLL".equals(msg.messageType) || isVoteMessage(msg.body)) {
                    bubble.getChildren().add(voteNode(msg));
                } else {
                    Label body = new Label(msg.body);
                    body.getStyleClass().add("message-body");
                    body.setWrapText(true);
                    bubble.getChildren().add(body);
                }
            }
            for (Attachment a : msg.attachments) {
                bubble.getChildren().add(attachmentNode(a));
            }
        }

        if (msg.reactionSummary != null && !msg.reactionSummary.isBlank()) {
            Label reactions = new Label(msg.reactionSummary);
            reactions.getStyleClass().add("reaction-summary");
            reactions.setOnMouseClicked(e -> showReactionDetails(msg));
            bubble.getChildren().add(reactions);
        }

        if (mine && msg.seenCount > 0) {
            Label seen = new Label("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ xem: " + msg.seenCount);
            seen.getStyleClass().add("seen-label");
            bubble.getChildren().add(seen);
        }

        ContextMenu menu = messageMenu(msg, mine);
        bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
        Button more = composerIconButton("ÃƒÂ¢Ã¢â‚¬Â¹Ã‚Â¯", "TÃƒÆ’Ã‚Â¹y chÃƒÂ¡Ã‚Â»Ã‚Ân tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
        applyIcon(more, "menu");
        more.getStyleClass().add("message-more-button");
        more.setOnAction(e -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 4));

        HBox wrapper = new HBox(10);
        wrapper.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (!mine) {
            wrapper.getChildren().add(avatarNode(msg.senderName, "message-avatar", msg.senderUsername));
            wrapper.getChildren().add(bubble);
            wrapper.getChildren().add(more);
        } else {
            wrapper.getChildren().add(more);
            wrapper.getChildren().add(bubble);
            wrapper.getChildren().add(avatarNode(currentUser.displayName, "message-avatar", currentUser.username));
        }
        animateMessageIfNew(wrapper, msg.id);
        return wrapper;
    }

    private ContextMenu messageMenu(ChatMessage msg, boolean mine) {
        ContextMenu menu = new ContextMenu();
        javafx.scene.control.MenuItem reply = new javafx.scene.control.MenuItem("TrÃƒÂ¡Ã‚ÂºÃ‚Â£ lÃƒÂ¡Ã‚Â»Ã‚Âi");
        reply.setOnAction(e -> {
            replyToId = msg.id;
            replyLabel.setText("Ãƒâ€žÃ‚Âang trÃƒÂ¡Ã‚ÂºÃ‚Â£ lÃƒÂ¡Ã‚Â»Ã‚Âi: " + Texts.shortText(msg.recalled ? "Tin Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ thu hÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“i" : msg.body, 80));
            replyLabel.setVisible(true);
            replyLabel.setManaged(true);
            input.requestFocus();
        });
        javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem("SÃƒÂ¡Ã‚Â»Ã‚Â­a tin");
        edit.setDisable(!mine || msg.recalled);
        edit.setOnAction(e -> editMessage(msg));
        javafx.scene.control.MenuItem recall = new javafx.scene.control.MenuItem("Thu hÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“i");
        recall.setDisable(!mine || msg.recalled);
        recall.setOnAction(e -> recallMessage(msg));
        javafx.scene.control.MenuItem pin = new javafx.scene.control.MenuItem(msg.pinned ? "BÃƒÂ¡Ã‚Â»Ã‚Â ghim tin" : "Ghim tin");
        pin.setOnAction(e -> pinMessage(msg));
        javafx.scene.control.MenuItem forward = new javafx.scene.control.MenuItem("ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p");
        forward.setDisable(msg.recalled);
        forward.setOnAction(e -> forwardMessage(msg));
        Menu react = new Menu("CÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc");
        for (String emoji : List.of("ÃƒÂ°Ã…Â¸Ã¢â‚¬ËœÃ‚Â", "ÃƒÂ¢Ã‚ÂÃ‚Â¤ÃƒÂ¯Ã‚Â¸Ã‚Â", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã¢â‚¬Å¡", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã‚Â®", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã‚Â¢", "ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦")) {
            MenuItem item = new MenuItem(emoji);
            item.setOnAction(e -> reactToMessage(msg, emoji));
            react.getItems().add(item);
        }
        MenuItem removeReaction = new MenuItem("BÃƒÂ¡Ã‚Â»Ã‚Â cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc");
        removeReaction.setOnAction(e -> removeReaction(msg));
        react.getItems().add(new SeparatorMenuItem());
        react.getItems().add(removeReaction);
        MenuItem reactionDetails = new MenuItem("Xem ai Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ thÃƒÂ¡Ã‚ÂºÃ‚Â£ cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc");
        reactionDetails.setOnAction(e -> showReactionDetails(msg));
        javafx.scene.control.MenuItem task = new javafx.scene.control.MenuItem("ChuyÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÆ’Ã‚Â nh cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c (Giao KPI Task)");
        task.setText("Giao thÃƒÆ’Ã‚Â nh cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c (KPI Task)");
        task.setDisable(msg.recalled);
        task.setOnAction(e -> createChatTaskFromMessage(msg));
        menu.getItems().addAll(reply, edit, recall, pin, forward, react, reactionDetails, new SeparatorMenuItem(), task);
        return menu;
    }

    private void showReactionDetails(ChatMessage msg) {
        runDb(() -> chatService.listReactionDetails(currentUser, msg.id), details -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("CÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox box = new VBox(10);
            box.getStyleClass().add("settings-content");
            if (details.isEmpty()) {
                box.getChildren().add(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc", "Khi cÃƒÆ’Ã‚Â³ ngÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi thÃƒÂ¡Ã‚ÂºÃ‚Â£ cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc, danh sÃƒÆ’Ã‚Â¡ch sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
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
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c danh sÃƒÆ’Ã‚Â¡ch cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc", e));
    }

    private void reactToMessage(ChatMessage msg, String emoji) {
        runDb(() -> {
            chatService.addReaction(currentUser, msg.id, emoji);
            auditLogService.log(currentUser.username, "MESSAGE_REACTION_ADDED", "MESSAGE", msg.id, emoji);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng thÃƒÆ’Ã‚Âªm Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc", e));
    }

    private void removeReaction(ChatMessage msg) {
        runDb(() -> {
            chatService.removeReaction(currentUser, msg.id);
            auditLogService.log(currentUser.username, "MESSAGE_REACTION_REMOVED", "MESSAGE", msg.id, "");
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng bÃƒÂ¡Ã‚Â»Ã‚Â Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c cÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc", e));
    }

    private void createChatTaskFromMessage(ChatMessage msg) {
        long conversationId = msg.conversationId;
        runDb(() -> chatService.listMemberUsernames(conversationId), members -> {
            if (members.isEmpty()) {
                showInfo("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ thÃƒÆ’Ã‚Â nh viÃƒÆ’Ã‚Âªn Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ giao viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c.");
                return;
            }
            Dialog<TaskDraft> dialog = new Dialog<>();
            dialog.setTitle("Giao thÃƒÆ’Ã‚Â nh cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c (KPI Task)");
            styleDialog(dialog);
            ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u task", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            String body = Texts.safe(msg.body);
            String defaultTitle = body.isBlank() ? "CÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c tÃƒÂ¡Ã‚Â»Ã‚Â« file/tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n" : Texts.shortText(body, 70);
            TextField title = new TextField(defaultTitle);
            title.getStyleClass().add("dialog-search");
            TextArea description = new TextArea(body.isBlank() ? "CÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c tÃƒÂ¡Ã‚Â»Ã‚Â« file/tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n" : body);
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
                    label("TÃƒÆ’Ã‚Âªn cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "dialog-label"), title,
                    label("NgÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi thÃƒÂ¡Ã‚Â»Ã‚Â±c hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n", "dialog-label"), assignee,
                    label("MÃƒÆ’Ã‚Â´ tÃƒÂ¡Ã‚ÂºÃ‚Â£ cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "dialog-label"), description,
                    label("Ãƒâ€žÃ‚ÂÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ Ãƒâ€ Ã‚Â°u tiÃƒÆ’Ã‚Âªn", "dialog-label"), priority,
                    label("Deadline", "dialog-label"), deadline,
                    label("Ãƒâ€žÃ‚ÂiÃƒÂ¡Ã‚Â»Ã†â€™m KPI", "dialog-label"), kpi);
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
            dialog.showAndWait().ifPresent(draft -> runDb(() -> chatService.createTask(
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
                showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ tÃƒÂ¡Ã‚ÂºÃ‚Â¡o KPI Task.");
                publishRealtime("TASK_UPDATED", conversationId);
            }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â¡o Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c KPI Task", e)));
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c danh sÃƒÆ’Ã‚Â¡ch thÃƒÆ’Ã‚Â nh viÃƒÆ’Ã‚Âªn", e));
    }

    private record TaskDraft(String title, String description, String assignee, String priority, LocalDate deadline, int kpiPoints) {
    }

    private void createTaskFromMessage(ChatMessage msg) {
        runDb(() -> chatService.listTaskTargets(currentUser), targets -> {
            if (targets.isEmpty()) {
                showInfo("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ nhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn APPROVED Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ giao viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c.");
                return;
            }
            Dialog<TaskTarget> dialog = new Dialog<>();
            dialog.setTitle("Giao KPI Task tÃƒÂ¡Ã‚Â»Ã‚Â« tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
            styleDialog(dialog);
            ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u task", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            ComboBox<TaskTarget> employee = new ComboBox<>(FXCollections.observableArrayList(targets));
            employee.getStyleClass().add("dialog-search");
            targets.stream()
                    .filter(t -> t.username != null && t.username.equals(msg.senderUsername))
                    .findFirst()
                    .ifPresent(employee::setValue);
            TextArea description = new TextArea(Texts.safe(msg.body).isBlank() ? "CÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c tÃƒÂ¡Ã‚Â»Ã‚Â« file/tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n" : msg.body);
            description.getStyleClass().add("dialog-text-area");
            description.setWrapText(true);
            DatePicker deadline = new DatePicker(LocalDate.now().plusDays(1));
            VBox content = new VBox(10,
                    label("NgÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi thÃƒÂ¡Ã‚Â»Ã‚Â±c hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n", "dialog-label"), employee,
                    label("MÃƒÆ’Ã‚Â´ tÃƒÂ¡Ã‚ÂºÃ‚Â£ cÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "dialog-label"), description,
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
                showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ tÃƒÂ¡Ã‚ÂºÃ‚Â¡o KPI Task.");
                publishRealtime("TASK_UPDATED", msg.conversationId);
            }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â¡o Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c KPI Task", e)));
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c danh sÃƒÆ’Ã‚Â¡ch nhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn", e));
    }

    private void forwardMessage(ChatMessage msg) {
        List<Conversation> targets = conversations.stream()
                .filter(c -> c.id != msg.conversationId)
                .collect(Collectors.toList());
        if (targets.isEmpty()) {
            showInfo("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i khÃƒÆ’Ã‚Â¡c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ chuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p.");
            return;
        }
        Dialog<Conversation> dialog = new Dialog<>();
        dialog.setTitle("ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
        styleDialog(dialog);
        ButtonType send = new ButtonType("ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        ListView<Conversation> list = new ListView<>(FXCollections.observableArrayList(targets));
        list.getStyleClass().add("dialog-list");
        list.setCellFactory(view -> new SidebarCells.ConversationCell());
        list.setPrefSize(460, 360);
        VBox content = new VBox(10, label("ChÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i nhÃƒÂ¡Ã‚ÂºÃ‚Â­n tin:", "dialog-label"), list);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == send ? list.getSelectionModel().getSelectedItem() : null);
        dialog.showAndWait().ifPresent(target -> {
            String text = Texts.safe(msg.body).isBlank()
                    ? "ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p file tÃƒÂ¡Ã‚Â»Ã‚Â« " + msg.senderName
                    : "ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p: " + msg.body;
            runDb(() -> chatService.sendMessage(currentUser, target.id, text, null, List.of()), messageId -> {
                loadConversations();
                publishRealtime("MESSAGE_CREATED", target.id);
                showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ chuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n.");
            }, e -> {
                showError("KhÃƒÆ’Ã‚Â´ng chuyÃƒÂ¡Ã‚Â»Ã†â€™n tiÃƒÂ¡Ã‚ÂºÃ‚Â¿p Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin", e);
            });
        });
    }

    private boolean isVoteMessage(String body) {
        return body != null && body.startsWith("[VOTE]") && body.contains("|");
    }

    private Node voteNode(ChatMessage msg) {
        String body = Texts.safe(msg.body);
        if (!"POLL".equals(msg.messageType)) {
            return voteNode(body);
        }
        String raw = body.startsWith("[VOTE]") ? body.substring("[VOTE]".length()).trim() : body;
        String[] parts = raw.split("\\|");
        Label title = new Label(parts.length == 0 ? "BÃƒÆ’Ã‚Â¬nh chÃƒÂ¡Ã‚Â»Ã‚Ân" : parts[0].trim());
        title.getStyleClass().add("vote-title");
        VBox box = new VBox(8, title);
        box.getStyleClass().add("vote-card");
        runDb(() -> chatService.listPollOptions(currentUser, msg.id), options -> {
            box.getChildren().setAll(title);
            for (PollOption pollOption : options) {
                Button option = new Button(pollOption.optionText + "  (" + pollOption.voteCount + ")");
                option.getStyleClass().add("vote-option");
                if (pollOption.selectedByMe) option.getStyleClass().add("vote-option-selected");
                option.setMaxWidth(Double.MAX_VALUE);
                option.setOnAction(e -> castPollVote(msg, pollOption));
                box.getChildren().add(option);
            }
            if (options.isEmpty()) {
                box.getChildren().add(label("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ lÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân.", "settings-note"));
            }
        }, e -> box.getChildren().add(label("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c kÃƒÂ¡Ã‚ÂºÃ‚Â¿t quÃƒÂ¡Ã‚ÂºÃ‚Â£ vote.", "settings-note")));
        return box;
    }

    private void castPollVote(ChatMessage msg, PollOption option) {
        runDb(() -> {
            chatService.castPollVote(currentUser, option.pollId, option.optionId);
            auditLogService.log(currentUser.username, "POLL_VOTED", "POLL", option.pollId, "message=" + msg.id);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("MESSAGE_UPDATED", msg.conversationId);
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng lÃƒâ€ Ã‚Â°u Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c lÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân vote", e));
    }

    private Node voteNode(String body) {
        String raw = body.substring("[VOTE]".length()).trim();
        String[] parts = raw.split("\\|");
        Label title = new Label(parts.length == 0 ? "BÃƒÆ’Ã‚Â¬nh chÃƒÂ¡Ã‚Â»Ã‚Ân" : parts[0].trim());
        title.getStyleClass().add("vote-title");
        VBox box = new VBox(8, title);
        box.getStyleClass().add("vote-card");
        for (int i = 1; i < parts.length; i++) {
            String optionText = parts[i].trim();
            if (optionText.isBlank()) {
                continue;
            }
            Button option = new Button(optionText);
            option.getStyleClass().add("vote-option");
            option.setMaxWidth(Double.MAX_VALUE);
            option.setOnAction(e -> {
                option.getStyleClass().add("vote-option-selected");
                showToast("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ chÃƒÂ¡Ã‚Â»Ã‚Ân tÃƒÂ¡Ã‚ÂºÃ‚Â¡m thÃƒÂ¡Ã‚Â»Ã‚Âi: " + optionText);
            });
            box.getChildren().add(option);
        }
        if (box.getChildren().size() == 1) {
            box.getChildren().add(label("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ lÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân.", "settings-note"));
        }
        return box;
    }

    private Node attachmentNode(Attachment a) {
        File f = new File(a.sharedPath);
        boolean skipPreview = userSettings.bandwidthSaving && a.fileSize > 1024L * 1024L;
        if ("IMAGE".equals(a.fileType) && f.isFile() && !skipPreview) {
            VBox box = new VBox(6);
            ImageView view = new ImageView(new Image(f.toURI().toString(), 360, 240, true, true, true));
            view.getStyleClass().add("image-preview");
            view.setOnMouseClicked(e -> openFile(f));
            Label caption = new Label(a.originalName);
            caption.getStyleClass().add("attachment-caption");
            box.getChildren().addAll(view, caption);
            return box;
        }
        if ("AUDIO".equals(a.fileType) && f.isFile()) {
            return audioAttachmentNode(a, f);
        }
        Label icon = new Label("VIDEO".equals(a.fileType) ? "ÃƒÂ¢Ã¢â‚¬â€œÃ‚Â¶" : "ÃƒÂ°Ã…Â¸Ã¢â‚¬Å“Ã¢â‚¬Å¾");
        icon.getStyleClass().add("file-icon");
        Label name = new Label(a.originalName);
        name.getStyleClass().add("file-name");
        Label size = new Label(formatSize(a.fileSize));
        size.getStyleClass().add("file-size");
        VBox info = new VBox(2, name, size);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button open = new Button("MÃƒÂ¡Ã‚Â»Ã…Â¸");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> openFile(f));
        HBox card = new HBox(10, icon, info, open);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("file-card");
        return card;
    }

    private Node audioAttachmentNode(Attachment a, File f) {
        Label icon = new Label("ÃƒÂ°Ã…Â¸Ã…Â½Ã¢â€žÂ¢");
        icon.getStyleClass().add("file-icon");
        Label name = new Label(a.originalName);
        name.getStyleClass().add("file-name");
        Label meta = new Label(formatSize(a.fileSize) + " Ãƒâ€šÃ‚Â· " + audioDurationText(f) + " Ãƒâ€šÃ‚Â· PhiÃƒÆ’Ã‚Âªn ÃƒÆ’Ã‚Â¢m: Ãƒâ€žÃ¢â‚¬Ëœang chÃƒÂ¡Ã‚Â»Ã‚Â");
        meta.getStyleClass().add("file-size");
        VBox info = new VBox(2, name, meta);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button play = new Button("PhÃƒÆ’Ã‚Â¡t");
        play.getStyleClass().add("file-open-button");
        play.setOnAction(e -> playAudioFile(f, play));
        Button open = new Button("MÃƒÂ¡Ã‚Â»Ã…Â¸");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> openFile(f));
        HBox card = new HBox(10, icon, info, play, open);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("file-card");
        return card;
    }

    private void playAudioFile(File file, Button playButton) {
        try {
            if (activeAudioClip != null && activeAudioClip.isRunning()) {
                activeAudioClip.stop();
                activeAudioClip.close();
                activeAudioClip = null;
                playButton.setText("PhÃƒÆ’Ã‚Â¡t");
                return;
            }
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(file)) {
                Clip clip = AudioSystem.getClip();
                clip.open(stream);
                activeAudioClip = clip;
                playButton.setText("DÃƒÂ¡Ã‚Â»Ã‚Â«ng");
                clip.addLineListener(event -> {
                    if (event.getType() == javax.sound.sampled.LineEvent.Type.STOP) {
                        Platform.runLater(() -> playButton.setText("PhÃƒÆ’Ã‚Â¡t"));
                    }
                });
                clip.start();
            }
        } catch (Exception ex) {
            showError("KhÃƒÆ’Ã‚Â´ng phÃƒÆ’Ã‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c voice message", ex);
        }
    }

    private String audioDurationText(File file) {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(file)) {
            AudioFormat format = stream.getFormat();
            long frames = stream.getFrameLength();
            if (format.getFrameRate() <= 0 || frames <= 0) {
                return "Voice message";
            }
            long seconds = Math.round(frames / format.getFrameRate());
            return String.format("%d:%02d", seconds / 60, seconds % 60);
        } catch (Exception ignored) {
            return "Voice message";
        }
    }

    private Node salaryCardNode(ChatMessage msg) {
        Label title = new Label("PhiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng bÃƒÂ¡Ã‚ÂºÃ‚Â£o mÃƒÂ¡Ã‚ÂºÃ‚Â­t");
        title.getStyleClass().add("vote-title");
        Label body = new Label(Texts.safe(msg.body));
        body.getStyleClass().add("message-body");
        body.setWrapText(true);
        Button open = new Button("Xem chi tiÃƒÂ¡Ã‚ÂºÃ‚Â¿t phiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> openSalaryCard(msg));
        VBox card = new VBox(8, title, body, open);
        card.getStyleClass().add("vote-card");
        return card;
    }

    private Node workflowCardNode(ChatMessage msg) {
        Label title = new Label("Quy trÃƒÆ’Ã‚Â¬nh tÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng tÃƒÆ’Ã‚Â¡c");
        title.getStyleClass().add("vote-title");
        Label body = new Label(Texts.safe(msg.body));
        body.getStyleClass().add("message-body");
        body.setWrapText(true);
        Label status = new Label("TrÃƒÂ¡Ã‚ÂºÃ‚Â¡ng thÃƒÆ’Ã‚Â¡i: " + (msg.workflowStatus == null || msg.workflowStatus.isBlank() ? "PENDING" : msg.workflowStatus));
        status.getStyleClass().add("pin-chip");
        HBox actions = new HBox(8);
        if (currentUser.canManageGroups() && (msg.workflowStatus == null || "PENDING".equals(msg.workflowStatus))) {
            Button approve = new Button("Ãƒâ€žÃ‚ÂÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“ng ÃƒÆ’Ã‚Â½");
            approve.getStyleClass().add("file-open-button");
            approve.setOnAction(e -> decideWorkflow(msg, true));
            Button reject = new Button("TÃƒÂ¡Ã‚Â»Ã‚Â« chÃƒÂ¡Ã‚Â»Ã¢â‚¬Ëœi");
            reject.getStyleClass().add("file-open-button");
            reject.setOnAction(e -> decideWorkflow(msg, false));
            actions.getChildren().addAll(approve, reject);
        }
        VBox card = new VBox(8, title, body, status, actions);
        card.getStyleClass().add("vote-card");
        return card;
    }

    private void decideWorkflow(ChatMessage msg, boolean approved) {
        runDb(() -> {
            return chatService.decideBusinessWorkflow(currentUser, msg.id, approved);
        }, synced -> {
            refreshMessages(true);
            publishRealtime("WORKFLOW_UPDATED", msg.conversationId);
            if (approved && !synced) {
                showInfo("Workflow Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c duyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡t trong chat, nhÃƒâ€ Ã‚Â°ng chÃƒâ€ Ã‚Â°a tÃƒÆ’Ã‚Â¬m thÃƒÂ¡Ã‚ÂºÃ‚Â¥y bÃƒÂ¡Ã‚ÂºÃ‚Â£ng schedules/timekeeping Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“ng bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ chÃƒÂ¡Ã‚ÂºÃ‚Â¥m cÃƒÆ’Ã‚Â´ng.");
            }
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c workflow", e));
    }

    private void openSalaryCard(ChatMessage msg) {
        PasswordField password = new PasswordField();
        password.setPromptText("NhÃƒÂ¡Ã‚ÂºÃ‚Â­p lÃƒÂ¡Ã‚ÂºÃ‚Â¡i mÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u");
        password.getStyleClass().add("login-input");
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("XÃƒÆ’Ã‚Â¡c thÃƒÂ¡Ã‚Â»Ã‚Â±c phiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng");
        styleDialog(dialog);
        ButtonType open = new ButtonType("Xem", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(open, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(new VBox(10, label("Vui lÃƒÆ’Ã‚Â²ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­p lÃƒÂ¡Ã‚ÂºÃ‚Â¡i mÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ xem phiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng.", "dialog-label"), password));
        dialog.setResultConverter(btn -> btn == open ? password.getText() : null);
        dialog.showAndWait().ifPresent(pass -> runDb(() -> {
            CurrentUser verified = authService.login(currentUser.username, pass);
            if (verified == null) {
                throw new IllegalArgumentException("MÃƒÂ¡Ã‚ÂºÃ‚Â­t khÃƒÂ¡Ã‚ÂºÃ‚Â©u khÃƒÆ’Ã‚Â´ng Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng.");
            }
            return chatService.salaryDetail(currentUser, msg.metadataJson);
        }, detail -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, detail);
            styleDialog(alert);
            alert.setTitle("Chi tiÃƒÂ¡Ã‚ÂºÃ‚Â¿t phiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng");
            alert.setHeaderText("PhiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng");
            alert.showAndWait();
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng mÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c phiÃƒÂ¡Ã‚ÂºÃ‚Â¿u lÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng", e)));
    }

    private void showWorkflowDialog(String type) {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc.");
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("OT".equals(type) ? "Xin tÃƒâ€žÃ†â€™ng ca" : "Xin Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢i ca");
        styleDialog(dialog);
        ButtonType send = new ButtonType("GÃƒÂ¡Ã‚Â»Ã‚Â­i yÃƒÆ’Ã‚Âªu cÃƒÂ¡Ã‚ÂºÃ‚Â§u", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, ButtonType.CANCEL);
        if ("LEAVE".equals(type)) {
            dialog.setTitle("Xin nghÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° phÃƒÆ’Ã‚Â©p");
        }
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        TextField shift = new TextField("OT".equals(type) ? "TÃƒâ€žÃ†â€™ng ca" : "Ca mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi");
        if ("LEAVE".equals(type)) {
            shift.setText("NghÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° phÃƒÆ’Ã‚Â©p");
        }
        shift.getStyleClass().add("dialog-search");
        VBox content = new VBox(10, label("NgÃƒÆ’Ã‚Â y lÃƒÆ’Ã‚Â m viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "dialog-label"), date, label("Ca/NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung", "dialog-label"), shift);
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
            }, e -> showError("KhÃƒÆ’Ã‚Â´ng gÃƒÂ¡Ã‚Â»Ã‚Â­i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c workflow", e));
        });
    }

    private void sendCurrentMessage() {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc.");
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
                showInfo("KhÃƒÆ’Ã‚Â´ng gÃƒÂ¡Ã‚Â»Ã‚Â­i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin. App Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ lÃƒâ€ Ã‚Â°u vÃƒÆ’Ã‚Â o hÃƒÆ’Ã‚Â ng Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Â£i offline vÃƒÆ’Ã‚Â  sÃƒÂ¡Ã‚ÂºÃ‚Â½ thÃƒÂ¡Ã‚Â»Ã‚Â­ gÃƒÂ¡Ã‚Â»Ã‚Â­i lÃƒÂ¡Ã‚ÂºÃ‚Â¡i sau.");
                return;
            }
            showError("KhÃƒÆ’Ã‚Â´ng gÃƒÂ¡Ã‚Â»Ã‚Â­i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin", e);
        });
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("ChÃƒÂ¡Ã‚Â»Ã‚Ân file gÃƒÂ¡Ã‚Â»Ã‚Â­i kÃƒÆ’Ã‚Â¨m");
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
                voiceButton.setTooltip(new Tooltip("BÃƒÂ¡Ã‚ÂºÃ‚Â¥m Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã†â€™ dÃƒÂ¡Ã‚Â»Ã‚Â«ng ghi ÃƒÆ’Ã‚Â¢m"));
            }
            Thread writer = new Thread(() -> {
                try (AudioInputStream stream = new AudioInputStream(line)) {
                    AudioSystem.write(stream, AudioFileFormat.Type.WAVE, recordingFile);
                } catch (Exception e) {
                    System.err.println("Voice recording write failed: " + e.getMessage());
                }
            }, "chat-voice-recorder");
            writer.setDaemon(true);
            writer.start();
            showToast("Ãƒâ€žÃ‚Âang ghi ÃƒÆ’Ã‚Â¢m...");
        } catch (Exception e) {
            recordingLine = null;
            recordingFile = null;
            showError("KhÃƒÆ’Ã‚Â´ng bÃƒÂ¡Ã‚ÂºÃ‚Â¯t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â§u ghi ÃƒÆ’Ã‚Â¢m Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c", e);
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
                showToast("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ thÃƒÆ’Ã‚Âªm voice message vÃƒÆ’Ã‚Â o tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n.");
            }
        } catch (Exception e) {
            showError("KhÃƒÆ’Ã‚Â´ng dÃƒÂ¡Ã‚Â»Ã‚Â«ng ghi ÃƒÆ’Ã‚Â¢m Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c", e);
        } finally {
            recordingLine = null;
            recordingFile = null;
            if (voiceButton != null) {
                voiceButton.getStyleClass().remove("recording-button");
                voiceButton.setTooltip(new Tooltip("Ghi ÃƒÆ’Ã‚Â¢m voice message"));
            }
        }
    }

    private void showPlusMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        MenuItem files = new MenuItem("GÃƒÂ¡Ã‚Â»Ã‚Â­i file");
        files.setOnAction(e -> chooseFiles());
        MenuItem media = new MenuItem("GÃƒÂ¡Ã‚Â»Ã‚Â­i ÃƒÂ¡Ã‚ÂºÃ‚Â£nh/video");
        media.setOnAction(e -> chooseMediaFiles());
        MenuItem vote = new MenuItem("TÃƒÂ¡Ã‚ÂºÃ‚Â¡o vote");
        vote.setOnAction(e -> showVoteDialog());
        MenuItem scheduled = new MenuItem("HÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i");
        scheduled.setOnAction(e -> showScheduledMessageDialog());
        MenuItem scheduledCenter = new MenuItem("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â");
        scheduledCenter.setOnAction(e -> showScheduledCenterDialog());
        MenuItem reminder = new MenuItem("TÃƒÂ¡Ã‚ÂºÃ‚Â¡o nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c");
        reminder.setOnAction(e -> showReminderDialog(null));
        MenuItem reminderCenter = new MenuItem("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c");
        reminderCenter.setOnAction(e -> showReminderCenterDialog());
        MenuItem mentions = new MenuItem("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n tÃƒÆ’Ã‚Â´i");
        mentions.setOnAction(e -> showMentionsDialog());
        MenuItem exportHtml = new MenuItem("XuÃƒÂ¡Ã‚ÂºÃ‚Â¥t hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i HTML");
        exportHtml.setOnAction(e -> exportCurrentConversationHtml());
        MenuItem ot = new MenuItem("Xin tÃƒâ€žÃ†â€™ng ca");
        ot.setOnAction(e -> showWorkflowDialog("OT"));
        MenuItem shift = new MenuItem("Xin Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢i ca");
        shift.setOnAction(e -> showWorkflowDialog("SHIFT_CHANGE"));
        MenuItem leave = new MenuItem("Xin nghÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° phÃƒÆ’Ã‚Â©p");
        leave.setOnAction(e -> showWorkflowDialog("LEAVE"));
        menu.getItems().addAll(files, media, vote, scheduled, scheduledCenter, reminder, reminderCenter, mentions, exportHtml, new SeparatorMenuItem(), ot, shift, leave);
        menu.show(owner, javafx.geometry.Side.TOP, 0, -8);
    }

    private void chooseMediaFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("ChÃƒÂ¡Ã‚Â»Ã‚Ân ÃƒÂ¡Ã‚ÂºÃ‚Â£nh hoÃƒÂ¡Ã‚ÂºÃ‚Â·c video");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("ÃƒÂ¡Ã‚ÂºÃ‚Â¢nh vÃƒÆ’Ã‚Â  video", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.mp4", "*.mov", "*.avi", "*.mkv"),
                new FileChooser.ExtensionFilter("ÃƒÂ¡Ã‚ÂºÃ‚Â¢nh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"),
                new FileChooser.ExtensionFilter("Video", "*.mp4", "*.mov", "*.avi", "*.mkv"));
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null && !files.isEmpty()) {
            selectedFiles.addAll(files);
            updateAttachmentLabel();
        }
    }

    private void showScheduledMessageDialog() {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc khi hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i.");
            return;
        }
        Dialog<ScheduledDraft> dialog = new Dialog<>();
        dialog.setTitle("HÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i tin");
        styleDialog(dialog);
        ButtonType schedule = new ButtonType("HÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(schedule, ButtonType.CANCEL);

        TextArea body = new TextArea(input.getText());
        body.setPromptText("NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n");
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        TextField time = new TextField("08:00");
        time.setPromptText("HH:mm");
        time.getStyleClass().add("dialog-search");

        VBox content = new VBox(10,
                label("NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung", "dialog-label"), body,
                label("NgÃƒÆ’Ã‚Â y gÃƒÂ¡Ã‚Â»Ã‚Â­i", "dialog-label"), date,
                label("GiÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i", "dialog-label"), time,
                label("Server realtime sÃƒÂ¡Ã‚ÂºÃ‚Â½ tÃƒÂ¡Ã‚Â»Ã‚Â± gÃƒÂ¡Ã‚Â»Ã‚Â­i khi Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n hÃƒÂ¡Ã‚ÂºÃ‚Â¡n.", "settings-note"));
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != schedule) {
                return null;
            }
            try {
                return new ScheduledDraft(body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception ex) {
                showInfo("GiÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i phÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹nh dÃƒÂ¡Ã‚ÂºÃ‚Â¡ng HH:mm.");
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
                showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i tin.");
                publishRealtime("CONVERSATION_UPDATED", conversationId);
            }, e -> showError("KhÃƒÆ’Ã‚Â´ng hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c", e));
        });
    }

    private void showScheduledCenterDialog() {
        runDb(() -> chatService.listScheduledMessages(currentUser), items -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox list = new VBox(10);
            list.getStyleClass().add("sidebar-page-list");
            if (items.isEmpty()) {
                list.getChildren().add(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â", "CÃƒÆ’Ã‚Â¡c tin Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ hÃƒÂ¡Ã‚ÂºÃ‚Â¹n sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
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
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â", e));
    }

    private Node scheduledMessageCard(ScheduledMessage item, Dialog<?> owner) {
        Label title = new Label((item.conversationTitle == null ? "HÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i" : item.conversationTitle) + " - " + item.status);
        title.getStyleClass().add("task-card-title");
        Label time = new Label("GÃƒÂ¡Ã‚Â»Ã‚Â­i lÃƒÆ’Ã‚Âºc: " + (item.scheduledAt == null ? "" : item.scheduledAt.format(TASK_TIME)));
        time.getStyleClass().add("task-deadline");
        Label body = new Label(Texts.shortText(item.body, 140));
        body.getStyleClass().add("conversation-last");
        body.setWrapText(true);
        Button edit = new Button("SÃƒÂ¡Ã‚Â»Ã‚Â­a");
        edit.getStyleClass().add("header-button");
        edit.setDisable(!"PENDING".equalsIgnoreCase(item.status));
        edit.setOnAction(e -> {
            owner.close();
            showEditScheduledDialog(item);
        });
        Button cancel = new Button("HÃƒÂ¡Ã‚Â»Ã‚Â§y");
        cancel.getStyleClass().add("header-button");
        cancel.setDisable(!"PENDING".equalsIgnoreCase(item.status));
        cancel.setOnAction(e -> runDb(() -> {
            chatService.cancelScheduledMessage(currentUser, item.id);
            auditLogService.log(currentUser.username, "SCHEDULED_MESSAGE_CANCELLED", "SCHEDULED_MESSAGE", item.id, "");
            return true;
        }, ok -> showScheduledCenterDialog(), ex -> showError("KhÃƒÆ’Ã‚Â´ng hÃƒÂ¡Ã‚Â»Ã‚Â§y Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â", ex)));
        HBox actions = new HBox(8, edit, cancel);
        VBox card = new VBox(8, title, time, body, actions);
        card.getStyleClass().add("task-card");
        return card;
    }

    private void showEditScheduledDialog(ScheduledMessage item) {
        Dialog<ScheduledDraft> dialog = new Dialog<>();
        dialog.setTitle("SÃƒÂ¡Ã‚Â»Ã‚Â­a tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â");
        styleDialog(dialog);
        ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextArea body = new TextArea(item.body);
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(item.scheduledAt == null ? LocalDate.now().plusDays(1) : item.scheduledAt.toLocalDate());
        TextField time = new TextField(item.scheduledAt == null ? "08:00" : item.scheduledAt.toLocalTime().withSecond(0).withNano(0).toString());
        time.getStyleClass().add("dialog-search");
        VBox content = new VBox(10, label("NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung", "dialog-label"), body, label("NgÃƒÆ’Ã‚Â y gÃƒÂ¡Ã‚Â»Ã‚Â­i", "dialog-label"), date, label("GiÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i", "dialog-label"), time);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != save) return null;
            try {
                return new ScheduledDraft(body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception e) {
                showInfo("GiÃƒÂ¡Ã‚Â»Ã‚Â gÃƒÂ¡Ã‚Â»Ã‚Â­i phÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹nh dÃƒÂ¡Ã‚ÂºÃ‚Â¡ng HH:mm.");
                return null;
            }
        });
        dialog.showAndWait().ifPresent(draft -> runDb(() -> {
            chatService.updateScheduledMessage(currentUser, item.id, draft.body, draft.scheduledAt);
            auditLogService.log(currentUser.username, "SCHEDULED_MESSAGE_UPDATED", "SCHEDULED_MESSAGE", item.id, "");
            return true;
        }, ok -> showScheduledCenterDialog(), e -> showError("KhÃƒÆ’Ã‚Â´ng sÃƒÂ¡Ã‚Â»Ã‚Â­a Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin hÃƒÂ¡Ã‚ÂºÃ‚Â¹n giÃƒÂ¡Ã‚Â»Ã‚Â", e)));
    }

    private void showReminderDialog(ChatReminder edit) {
        Dialog<ReminderDraft> dialog = new Dialog<>();
        dialog.setTitle(edit == null ? "TÃƒÂ¡Ã‚ÂºÃ‚Â¡o nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c" : "SÃƒÂ¡Ã‚Â»Ã‚Â­a nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c");
        styleDialog(dialog);
        ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField title = new TextField(edit == null ? "" : edit.title);
        title.setPromptText("TiÃƒÆ’Ã‚Âªu Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Â");
        title.getStyleClass().add("dialog-search");
        TextArea body = new TextArea(edit == null ? "" : Texts.safe(edit.body));
        body.setPromptText("NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c");
        body.getStyleClass().add("dialog-text-area");
        body.setWrapText(true);
        DatePicker date = new DatePicker(edit == null || edit.remindAt == null ? LocalDate.now().plusDays(1) : edit.remindAt.toLocalDate());
        TextField time = new TextField(edit == null || edit.remindAt == null ? "08:00" : edit.remindAt.toLocalTime().withSecond(0).withNano(0).toString());
        time.getStyleClass().add("dialog-search");
        CheckBox conversationReminder = new CheckBox("GÃƒÂ¡Ã‚Â»Ã‚Â­i nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c vÃƒÆ’Ã‚Â o hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n tÃƒÂ¡Ã‚ÂºÃ‚Â¡i");
        conversationReminder.getStyleClass().add("settings-check");
        conversationReminder.setSelected(currentConversation != null && (edit == null || edit.conversationId != null));
        VBox content = new VBox(10, label("TiÃƒÆ’Ã‚Âªu Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã‚Â", "dialog-label"), title, label("NÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i dung", "dialog-label"), body,
                label("NgÃƒÆ’Ã‚Â y nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c", "dialog-label"), date, label("GiÃƒÂ¡Ã‚Â»Ã‚Â nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c", "dialog-label"), time, conversationReminder);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> {
            if (btn != save) return null;
            try {
                Long conversationId = conversationReminder.isSelected() && currentConversation != null ? currentConversation.id : null;
                return new ReminderDraft(conversationId, title.getText(), body.getText(), LocalDateTime.of(date.getValue(), LocalTime.parse(time.getText().trim())));
            } catch (Exception e) {
                showInfo("GiÃƒÂ¡Ã‚Â»Ã‚Â nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c phÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Âºng Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹nh dÃƒÂ¡Ã‚ÂºÃ‚Â¡ng HH:mm.");
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
        }, ok -> showReminderCenterDialog(), e -> showError("KhÃƒÆ’Ã‚Â´ng lÃƒâ€ Ã‚Â°u Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", e)));
    }

    private void showReminderCenterDialog() {
        runDb(() -> chatService.listReminders(currentUser), reminders -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Button add = new Button("TÃƒÂ¡Ã‚ÂºÃ‚Â¡o nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c");
            add.getStyleClass().add("header-button");
            add.setOnAction(e -> {
                dialog.close();
                showReminderDialog(null);
            });
            VBox list = new VBox(10, add);
            list.getStyleClass().add("sidebar-page-list");
            if (reminders.isEmpty()) {
                list.getChildren().add(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "CÃƒÆ’Ã‚Â¡c nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ tÃƒÂ¡Ã‚ÂºÃ‚Â¡i Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
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
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", e));
    }

    private Node reminderCard(ChatReminder reminder, Dialog<?> owner) {
        Label title = new Label(reminder.title + " - " + reminder.status);
        title.getStyleClass().add("task-card-title");
        Label target = new Label(reminder.conversationTitle == null ? "NhÃƒÂ¡Ã‚ÂºÃ‚Â¯c cÃƒÆ’Ã‚Â¡ nhÃƒÆ’Ã‚Â¢n" : "HÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i: " + reminder.conversationTitle);
        target.getStyleClass().add("conversation-last");
        Label time = new Label("NhÃƒÂ¡Ã‚ÂºÃ‚Â¯c lÃƒÆ’Ã‚Âºc: " + (reminder.remindAt == null ? "" : reminder.remindAt.format(TASK_TIME)));
        time.getStyleClass().add("task-deadline");
        Label body = new Label(Texts.shortText(reminder.body, 140));
        body.setWrapText(true);
        body.getStyleClass().add("conversation-last");
        Button edit = new Button("SÃƒÂ¡Ã‚Â»Ã‚Â­a");
        edit.getStyleClass().add("header-button");
        edit.setDisable(!"PENDING".equalsIgnoreCase(reminder.status));
        edit.setOnAction(e -> {
            owner.close();
            showReminderDialog(reminder);
        });
        Button cancel = new Button("HÃƒÂ¡Ã‚Â»Ã‚Â§y");
        cancel.getStyleClass().add("header-button");
        cancel.setDisable(!"PENDING".equalsIgnoreCase(reminder.status));
        cancel.setOnAction(e -> runDb(() -> {
            chatService.cancelReminder(currentUser, reminder.id);
            auditLogService.log(currentUser.username, "REMINDER_CANCELLED", "REMINDER", reminder.id, "");
            return true;
        }, ok -> showReminderCenterDialog(), ex -> showError("KhÃƒÆ’Ã‚Â´ng hÃƒÂ¡Ã‚Â»Ã‚Â§y Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", ex)));
        VBox card = new VBox(8, title, target, time, body, new HBox(8, edit, cancel));
        card.getStyleClass().add("task-card");
        return card;
    }

    private void showMentionsDialog() {
        runDb(() -> chatService.listMentionsForUser(currentUser), mentions -> {
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n tÃƒÆ’Ã‚Â´i");
            styleDialog(dialog);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            VBox list = new VBox(10);
            list.getStyleClass().add("sidebar-page-list");
            if (mentions.isEmpty()) {
                list.getChildren().add(emptyState("ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ mention", "Khi ai Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â³ @ bÃƒÂ¡Ã‚ÂºÃ‚Â¡n, tin sÃƒÂ¡Ã‚ÂºÃ‚Â½ hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n ÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â¢y."));
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
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c danh sÃƒÆ’Ã‚Â¡ch mention", e));
    }

    private Node mentionCard(MentionItem mention, Dialog<?> owner) {
        Label title = new Label((mention.conversationTitle == null ? "HÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i" : mention.conversationTitle) + " - " + mention.senderName);
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
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc khi tÃƒÂ¡Ã‚ÂºÃ‚Â¡o vote.");
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("TÃƒÂ¡Ã‚ÂºÃ‚Â¡o vote");
        styleDialog(dialog);
        ButtonType create = new ButtonType("TÃƒÂ¡Ã‚ÂºÃ‚Â¡o vote", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);
        TextField question = new TextField();
        question.setPromptText("CÃƒÆ’Ã‚Â¢u hÃƒÂ¡Ã‚Â»Ã‚Âi bÃƒÆ’Ã‚Â¬nh chÃƒÂ¡Ã‚Â»Ã‚Ân");
        question.getStyleClass().add("dialog-search");
        TextArea options = new TextArea("Ãƒâ€žÃ‚ÂÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“ng ÃƒÆ’Ã‚Â½\nKhÃƒÆ’Ã‚Â´ng Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“ng ÃƒÆ’Ã‚Â½");
        options.setPromptText("MÃƒÂ¡Ã‚Â»Ã¢â‚¬â€i lÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân trÃƒÆ’Ã‚Âªn mÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢t dÃƒÆ’Ã‚Â²ng");
        options.getStyleClass().add("dialog-text-area");
        options.setWrapText(true);
        VBox content = new VBox(10,
                label("CÃƒÆ’Ã‚Â¢u hÃƒÂ¡Ã‚Â»Ã‚Âi", "dialog-label"),
                question,
                label("LÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân", "dialog-label"),
                options,
                label("Vote V1 chÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ lÃƒÂ¡Ã‚Â»Ã‚Â±a chÃƒÂ¡Ã‚Â»Ã‚Ân trong chat, chÃƒâ€ Ã‚Â°a lÃƒâ€ Ã‚Â°u kÃƒÂ¡Ã‚ÂºÃ‚Â¿t quÃƒÂ¡Ã‚ÂºÃ‚Â£ lÃƒÆ’Ã‚Â¢u dÃƒÆ’Ã‚Â i.", "settings-note"));
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
                showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â¡o Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c vote", e);
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
        attachmentLabel.setText("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â­nh kÃƒÆ’Ã‚Â¨m: " + selectedFiles.stream().map(File::getName).collect(Collectors.joining(", ")));
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
            ButtonType open = new ButtonType("MÃƒÂ¡Ã‚Â»Ã…Â¸ chat", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(open, ButtonType.CANCEL);

            TextField search = new TextField();
            search.setPromptText("TÃƒÆ’Ã‚Â¬m tÃƒÆ’Ã‚Âªn hoÃƒÂ¡Ã‚ÂºÃ‚Â·c tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n");
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
                    showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â¡o Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c chat 1-1", e);
                }
            });
        } catch (Exception e) {
            showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i danh sÃƒÆ’Ã‚Â¡ch nhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn", e);
        }
    }

    private void openGroupDialog(Conversation editConversation) {
        if (!currentUser.canManageGroups()) {
            showInfo("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° Admin hoÃƒÂ¡Ã‚ÂºÃ‚Â·c TrÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã…Â¸ng phÃƒÆ’Ã‚Â²ng Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tÃƒÂ¡Ã‚ÂºÃ‚Â¡o/quÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÆ’Ã‚Â³m.");
            return;
        }
        try {
            companyUsers = chatService.listCompanyUsers(currentUser);
            List<String> currentMembers = editConversation == null ? List.of() : chatService.listMemberUsernames(editConversation.id);
            Dialog<GroupForm> dialog = new Dialog<>();
            dialog.setTitle(editConversation == null ? "TÃƒÂ¡Ã‚ÂºÃ‚Â¡o nhÃƒÆ’Ã‚Â³m" : "QuÃƒÂ¡Ã‚ÂºÃ‚Â£n lÃƒÆ’Ã‚Â½ nhÃƒÆ’Ã‚Â³m");
            styleDialog(dialog);
            ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u", ButtonBar.ButtonData.OK_DONE);
            dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

            TextField title = new TextField(editConversation == null ? "" : editConversation.title);
            title.setPromptText("TÃƒÆ’Ã‚Âªn nhÃƒÆ’Ã‚Â³m");
            title.getStyleClass().add("dialog-search");
            TextField search = new TextField();
            search.setPromptText("TÃƒÆ’Ã‚Â¬m thÃƒÆ’Ã‚Â nh viÃƒÆ’Ã‚Âªn");
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
            VBox content = new VBox(12, label("TÃƒÆ’Ã‚Âªn nhÃƒÆ’Ã‚Â³m", "dialog-label"), title, search, scroll);
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
                    showError("KhÃƒÆ’Ã‚Â´ng lÃƒâ€ Ã‚Â°u Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c nhÃƒÆ’Ã‚Â³m", e);
                }
            });
        } catch (Exception e) {
            showError("KhÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚ÂºÃ‚Â£i dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u nhÃƒÆ’Ã‚Â³m", e);
        }
    }

    private void editMessage(ChatMessage msg) {
        TextArea area = new TextArea(Texts.safe(msg.body));
        area.getStyleClass().add("dialog-text-area");
        area.setWrapText(true);
        Dialog<String> dialog = simpleDialog("SÃƒÂ¡Ã‚Â»Ã‚Â­a tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n", area);
        dialog.showAndWait().ifPresent(text -> {
            runDb(() -> {
                chatService.editMessage(currentUser, msg.id, text);
                auditLogService.log(currentUser.username, "MESSAGE_EDITED", "MESSAGE", msg.id, "conversation=" + msg.conversationId);
                return true;
            }, ok -> {
                refreshMessages(true);
                publishRealtime("MESSAGE_UPDATED", msg.conversationId);
            }, e -> {
                showError("KhÃƒÆ’Ã‚Â´ng sÃƒÂ¡Ã‚Â»Ã‚Â­a Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin", e);
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
            showError("KhÃƒÆ’Ã‚Â´ng thu hÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“i Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin", e);
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
            showError("KhÃƒÆ’Ã‚Â´ng ghim Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin", e);
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
            showError("KhÃƒÆ’Ã‚Â´ng ghim Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i", e);
        });
    }

    private void exportCurrentConversationCsv() {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc khi export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("XuÃƒÂ¡Ã‚ÂºÃ‚Â¥t lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat");
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
        }, exported -> showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ export lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat:\n" + exported), e -> showError("KhÃƒÆ’Ã‚Â´ng export Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat", e));
    }

    private void exportCurrentConversationHtml() {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc khi export.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("XuÃƒÂ¡Ã‚ÂºÃ‚Â¥t lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat HTML");
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
            showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ export lÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ch sÃƒÂ¡Ã‚Â»Ã‚Â­ chat HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng export Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c HTML", e));
    }

    private void showAdvancedMessageSearchDialog() {
        if (currentConversation == null) {
            showInfo("HÃƒÆ’Ã‚Â£y chÃƒÂ¡Ã‚Â»Ã‚Ân hÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc khi tÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m.");
            return;
        }
        Dialog<MessageSearchCriteria> dialog = new Dialog<>();
        dialog.setTitle("TÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m nÃƒÆ’Ã‚Â¢ng cao");
        styleDialog(dialog);
        ButtonType search = new ButtonType("TÃƒÆ’Ã‚Â¬m", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(search, ButtonType.CANCEL);

        TextField keyword = new TextField(messageSearch == null ? "" : messageSearch.getText());
        keyword.setPromptText("TÃƒÂ¡Ã‚Â»Ã‚Â« khÃƒÆ’Ã‚Â³a, tÃƒÆ’Ã‚Âªn file");
        keyword.getStyleClass().add("dialog-search");
        ComboBox<String> sender = new ComboBox<>();
        sender.getItems().add("");
        for (ChatUser user : companyUsers) {
            sender.getItems().add(user.username);
        }
        sender.setPromptText("NgÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi gÃƒÂ¡Ã‚Â»Ã‚Â­i");
        sender.getStyleClass().add("dialog-search");
        DatePicker from = new DatePicker();
        DatePicker to = new DatePicker();
        CheckBox onlyFiles = new CheckBox("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° tin cÃƒÆ’Ã‚Â³ file");
        CheckBox onlyPinned = new CheckBox("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° tin Ãƒâ€žÃ¢â‚¬ËœÃƒÆ’Ã‚Â£ ghim");
        CheckBox onlyMentions = new CheckBox("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯c Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â¿n tÃƒÆ’Ã‚Â´i");
        CheckBox onlyTasks = new CheckBox("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° task/workflow");
        CheckBox includeArchive = new CheckBox("TÃƒÆ’Ã‚Â¬m trong archive");
        for (CheckBox cb : List.of(onlyFiles, onlyPinned, onlyMentions, onlyTasks, includeArchive)) {
            cb.getStyleClass().add("settings-check");
        }

        VBox content = new VBox(10,
                label("TÃƒÂ¡Ã‚Â»Ã‚Â« khÃƒÆ’Ã‚Â³a", "dialog-label"), keyword,
                label("NgÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Âi gÃƒÂ¡Ã‚Â»Ã‚Â­i", "dialog-label"), sender,
                label("TÃƒÂ¡Ã‚Â»Ã‚Â« ngÃƒÆ’Ã‚Â y", "dialog-label"), from,
                label("Ãƒâ€žÃ‚ÂÃƒÂ¡Ã‚ÂºÃ‚Â¿n ngÃƒÆ’Ã‚Â y", "dialog-label"), to,
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
                messageBox.getChildren().setAll(emptyState("KhÃƒÆ’Ã‚Â´ng cÃƒÆ’Ã‚Â³ kÃƒÂ¡Ã‚ÂºÃ‚Â¿t quÃƒÂ¡Ã‚ÂºÃ‚Â£", "ThÃƒÂ¡Ã‚Â»Ã‚Â­ Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢i tÃƒÂ¡Ã‚Â»Ã‚Â« khÃƒÆ’Ã‚Â³a hoÃƒÂ¡Ã‚ÂºÃ‚Â·c bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ lÃƒÂ¡Ã‚Â»Ã‚Âc tÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m."));
            } else {
                messageBox.getChildren().setAll(messages.stream().map(this::messageNode).collect(Collectors.toList()));
            }
            headerDetail.setText("KÃƒÂ¡Ã‚ÂºÃ‚Â¿t quÃƒÂ¡Ã‚ÂºÃ‚Â£ tÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m nÃƒÆ’Ã‚Â¢ng cao: " + messages.size() + " tin");
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng tÃƒÆ’Ã‚Â¬m kiÃƒÂ¡Ã‚ÂºÃ‚Â¿m Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n", e));
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
        headerDetail.setText(actor + " Ãƒâ€žÃ¢â‚¬Ëœang nhÃƒÂ¡Ã‚ÂºÃ‚Â­p...");
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
            showToast(conversation == null ? "CÃƒÆ’Ã‚Â³ tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi" : "CÃƒÆ’Ã‚Â³ tin mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi tÃƒÂ¡Ã‚Â»Ã‚Â« " + conversation.title);
        }
        if (userSettings.toastEnabled) {
            lastNativeNotificationConversation = conversation;
            showNativeNotification("Chat nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢", conversation == null ? "CÃƒÆ’Ã‚Â³ tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi" : "CÃƒÆ’Ã‚Â³ tin mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi tÃƒÂ¡Ã‚Â»Ã‚Â« " + conversation.title);
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
        Button close = new Button("ÃƒÆ’Ã¢â‚¬â€");
        close.getStyleClass().add("chat-head-close");
        close.setTooltip(new Tooltip("ÃƒÂ¡Ã‚ÂºÃ‚Â¨n thÃƒÆ’Ã‚Â´ng bÃƒÆ’Ã‚Â¡o nÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢i"));
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
            System.err.println("Native notification unavailable: " + e.getMessage());
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
        trayIcon = new TrayIcon(image, "Chat nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢");
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
        MenuItem settings = new MenuItem("CÃƒÆ’Ã‚Â i Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚ÂºÃ‚Â·t");
        settings.setOnAction(e -> showSettingsDialog());
        MenuItem account = new MenuItem("ThÃƒÆ’Ã‚Â´ng tin tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n");
        account.setOnAction(e -> showAccountInfo());
        MenuItem logout = new MenuItem("Ãƒâ€žÃ‚ÂÃƒâ€žÃ†â€™ng xuÃƒÂ¡Ã‚ÂºÃ‚Â¥t");
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
        showToast("Ã„ÂÃƒÂ£ lÃ†Â°u cÃƒÂ i Ã„â€˜Ã¡ÂºÂ·t.");
    }

    private void showAccountInfo() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("ThÃƒÆ’Ã‚Â´ng tin tÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n");
        styleDialog(dialog);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        VBox content = new VBox(12,
                settingLine("TÃƒÆ’Ã‚Âªn hiÃƒÂ¡Ã‚Â»Ã†â€™n thÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹", currentUser.displayName),
                settingLine("TÃƒÆ’Ã‚Â i khoÃƒÂ¡Ã‚ÂºÃ‚Â£n", currentUser.username),
                settingLine("Vai trÃƒÆ’Ã‚Â²", currentUser.isAdmin() ? "QuÃƒÂ¡Ã‚ÂºÃ‚Â£n trÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¹ viÃƒÆ’Ã‚Âªn" : "NhÃƒÆ’Ã‚Â¢n viÃƒÆ’Ã‚Âªn"),
                settingLine("CÃƒÆ’Ã‚Â´ng ty", currentUser.companyOwner),
                settingLine("PhÃƒÆ’Ã‚Â²ng ban", Texts.safe(currentUser.department).isBlank() ? "ChÃƒâ€ Ã‚Â°a cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t" : currentUser.department),
                settingLine("ChÃƒÂ¡Ã‚Â»Ã‚Â©c vÃƒÂ¡Ã‚Â»Ã‚Â¥", Texts.safe(currentUser.position).isBlank() ? "ChÃƒâ€ Ã‚Â°a cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t" : currentUser.position));
        content.getStyleClass().add("settings-content");
        dialog.getDialogPane().setContent(content);
        dialog.showAndWait();
    }

    private void logoutAndExit() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Ãƒâ€žÃ‚ÂÃƒâ€žÃ†â€™ng xuÃƒÂ¡Ã‚ÂºÃ‚Â¥t");
        confirm.setHeaderText("BÃƒÂ¡Ã‚ÂºÃ‚Â¡n muÃƒÂ¡Ã‚Â»Ã¢â‚¬Ëœn Ãƒâ€žÃ¢â‚¬ËœÃƒâ€žÃ†â€™ng xuÃƒÂ¡Ã‚ÂºÃ‚Â¥t vÃƒÆ’Ã‚Â  thoÃƒÆ’Ã‚Â¡t ÃƒÂ¡Ã‚Â»Ã‚Â©ng dÃƒÂ¡Ã‚Â»Ã‚Â¥ng?");
        confirm.setContentText("ÃƒÂ¡Ã‚Â»Ã‚Â¨ng dÃƒÂ¡Ã‚Â»Ã‚Â¥ng sÃƒÂ¡Ã‚ÂºÃ‚Â½ dÃƒÂ¡Ã‚Â»Ã‚Â«ng nhÃƒÂ¡Ã‚ÂºÃ‚Â­n tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n mÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºi trÃƒÆ’Ã‚Âªn mÃƒÆ’Ã‚Â¡y nÃƒÆ’Ã‚Â y.");
        ButtonType logout = new ButtonType("Ãƒâ€žÃ‚ÂÃƒâ€žÃ†â€™ng xuÃƒÂ¡Ã‚ÂºÃ‚Â¥t", ButtonBar.ButtonData.OK_DONE);
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
                    System.err.println("Logout audit/session cleanup failed: " + e.getMessage());
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
            showError("KhÃƒÆ’Ã‚Â´ng mÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c thÃƒâ€ Ã‚Â° mÃƒÂ¡Ã‚Â»Ã‚Â¥c file", e);
        }
    }

    private void openReportsFolder() {
        try {
            Desktop.getDesktop().open(reportsDirectory().toFile());
        } catch (Exception e) {
            showError("KhÃƒÆ’Ã‚Â´ng mÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c thÃƒâ€ Ã‚Â° mÃƒÂ¡Ã‚Â»Ã‚Â¥c bÃƒÆ’Ã‚Â¡o cÃƒÆ’Ã‚Â¡o", e);
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
            showInfo("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° admin Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c backup dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u chat.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Backup dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u chat");
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
        }, exported -> showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ backup dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u chat:\n" + exported), e -> showError("KhÃƒÆ’Ã‚Â´ng backup Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u chat", e));
    }

    private void restoreChatBackup() {
        if (!currentUser.isAdmin()) {
            showInfo("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° admin Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c restore dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u chat.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("ChÃƒÂ¡Ã‚Â»Ã‚Ân file backup chat");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("ZIP", "*.zip"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Restore backup chat");
        confirm.setHeaderText("Import dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u tÃƒÂ¡Ã‚Â»Ã‚Â« backup?");
        confirm.setContentText("Restore dÃƒÆ’Ã‚Â¹ng INSERT IGNORE: khÃƒÆ’Ã‚Â´ng xÃƒÆ’Ã‚Â³a dÃƒÂ¡Ã‚Â»Ã‚Â¯ liÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡u hiÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n cÃƒÆ’Ã‚Â³, chÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° bÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¢ sung bÃƒÂ¡Ã‚ÂºÃ‚Â£n ghi chÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³.");
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                runDb(() -> {
                    int count = backupService.restoreChatBackup(currentUser, file.toPath());
                    auditLogService.log(currentUser.username, "BACKUP_RESTORED", "BACKUP", "chat", file.getAbsolutePath() + "; rows=" + count);
                    return count;
                }, count -> {
                    showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ restore/import " + count + " dÃƒÆ’Ã‚Â²ng tÃƒÂ¡Ã‚Â»Ã‚Â« backup.");
                    loadConversations();
                    refreshMessages(true);
                }, e -> showError("KhÃƒÆ’Ã‚Â´ng restore Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c backup", e));
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
            showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ export task report HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng export Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c task report HTML", e));
    }

    private void exportEngagementReportHtml() {
        if (!currentUser.isAdmin()) {
            showInfo("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° admin Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c export bÃƒÆ’Ã‚Â¡o cÃƒÆ’Ã‚Â¡o tÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng tÃƒÆ’Ã‚Â¡c.");
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
            showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ export bÃƒÆ’Ã‚Â¡o cÃƒÆ’Ã‚Â¡o tÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng tÃƒÆ’Ã‚Â¡c HTML:\n" + exported);
            try {
                Desktop.getDesktop().open(exported.toFile());
            } catch (Exception ignored) {
            }
        }, e -> showError("KhÃƒÆ’Ã‚Â´ng export Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c bÃƒÆ’Ã‚Â¡o cÃƒÆ’Ã‚Â¡o tÃƒâ€ Ã‚Â°Ãƒâ€ Ã‚Â¡ng tÃƒÆ’Ã‚Â¡c HTML", e));
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
            showInfo("ChÃƒÂ¡Ã‚Â»Ã¢â‚¬Â° admin Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c archive tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleDialog(confirm);
        confirm.setTitle("Archive tin cÃƒâ€¦Ã‚Â©");
        confirm.setHeaderText("ChuyÃƒÂ¡Ã‚Â»Ã†â€™n tin cÃƒâ€¦Ã‚Â© sang bÃƒÂ¡Ã‚ÂºÃ‚Â£ng archive?");
        confirm.setContentText("Tin cÃƒâ€¦Ã‚Â© hÃƒâ€ Ã‚Â¡n " + config.retentionDays + " ngÃƒÆ’Ã‚Â y sÃƒÂ¡Ã‚ÂºÃ‚Â½ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c chuyÃƒÂ¡Ã‚Â»Ã†â€™n sang archive. BÃƒÂ¡Ã‚ÂºÃ‚Â¡n vÃƒÂ¡Ã‚ÂºÃ‚Â«n cÃƒÆ’Ã‚Â³ thÃƒÂ¡Ã‚Â»Ã†â€™ tÃƒÆ’Ã‚Â¬m lÃƒÂ¡Ã‚ÂºÃ‚Â¡i bÃƒÂ¡Ã‚ÂºÃ‚Â±ng bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ lÃƒÂ¡Ã‚Â»Ã‚Âc archive.");
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == ButtonType.OK) {
                runDb(() -> {
                    int count = chatService.archiveOldMessages(currentUser, config.retentionDays);
                    auditLogService.log(currentUser.username, "MESSAGES_ARCHIVED", "ARCHIVE", currentUser.companyOwner, "count=" + count);
                    return count;
                }, count -> showInfo("Ãƒâ€žÃ‚ÂÃƒÆ’Ã‚Â£ archive " + count + " tin nhÃƒÂ¡Ã‚ÂºÃ‚Â¯n."), e -> showError("KhÃƒÆ’Ã‚Â´ng archive Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c tin cÃƒâ€¦Ã‚Â©", e));
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
        Label valueLabel = new Label(value == null || value.isBlank() ? "ChÃƒâ€ Ã‚Â°a cÃƒÂ¡Ã‚ÂºÃ‚Â­p nhÃƒÂ¡Ã‚ÂºÃ‚Â­t" : value);
        valueLabel.getStyleClass().add("settings-value");
        valueLabel.setWrapText(true);
        HBox.setHgrow(valueLabel, Priority.ALWAYS);
        HBox line = new HBox(12, keyLabel, valueLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("settings-line");
        return line;
    }

    private Button composerIconButton(String icon, String tooltip) {
        Button button = new Button(icon);
        button.getStyleClass().add("composer-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        installHoverScale(button, 1.05);
        return button;
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
        Label current = new Label("MÃƒÆ’Ã‚Â u Ãƒâ€žÃ¢â‚¬Ëœang chÃƒÂ¡Ã‚Â»Ã‚Ân: " + selectedAccent[0]);
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
                current.setText("MÃƒÆ’Ã‚Â u Ãƒâ€žÃ¢â‚¬Ëœang chÃƒÂ¡Ã‚Â»Ã‚Ân: " + color);
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
        addEmojiGroup(menu, box, "CÃƒÂ¡Ã‚ÂºÃ‚Â£m xÃƒÆ’Ã‚Âºc", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã¢â€šÂ¬", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã‚Â", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã¢â‚¬Å¡", "ÃƒÂ°Ã…Â¸Ã‚Â¤Ã‚Â£", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã…Â ", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã‚Â", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã‹Å“", "ÃƒÂ°Ã…Â¸Ã‹Å“Ã…Â½");
        addEmojiGroup(menu, box, "PhÃƒÂ¡Ã‚ÂºÃ‚Â£n hÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“i nhanh", "ÃƒÂ°Ã…Â¸Ã¢â‚¬ËœÃ‚Â", "ÃƒÂ°Ã…Â¸Ã¢â‚¬ËœÃ…Â½", "ÃƒÂ°Ã…Â¸Ã¢â‚¬ËœÃ‚Â", "ÃƒÂ°Ã…Â¸Ã¢â€žÂ¢Ã‚Â", "ÃƒÂ°Ã…Â¸Ã¢â‚¬â„¢Ã‚Âª", "ÃƒÂ°Ã…Â¸Ã¢â‚¬ÂÃ‚Â¥", "ÃƒÂ¢Ã…â€œÃ‚Â¨", "ÃƒÂ¢Ã‚ÂÃ‚Â¤ÃƒÂ¯Ã‚Â¸Ã‚Â");
        addEmojiGroup(menu, box, "CÃƒÆ’Ã‚Â´ng viÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡c", "ÃƒÂ¢Ã…â€œÃ¢â‚¬Â¦", "ÃƒÂ¢Ã‚ÂÃ…â€™", "ÃƒÂ°Ã…Â¸Ã¢â‚¬Å“Ã…â€™", "ÃƒÂ°Ã…Â¸Ã¢â‚¬Å“Ã…Â½", "ÃƒÂ°Ã…Â¸Ã¢â‚¬Å“Ã‚Â·", "ÃƒÂ°Ã…Â¸Ã…Â½Ã‚Â¬", "ÃƒÂ°Ã…Â¸Ã¢â‚¬â„¢Ã‚Â¼", "ÃƒÂ¢Ã‚ÂÃ‚Â°");
        addEmojiGroup(menu, box, "KhÃƒÆ’Ã‚Â´ng khÃƒÆ’Ã‚Â­", "ÃƒÂ°Ã…Â¸Ã…Â½Ã¢â‚¬Â°", "ÃƒÂ¢Ã‚Â­Ã‚Â", "ÃƒÂ¢Ã‹Å“Ã¢â‚¬Â¢", "ÃƒÂ°Ã…Â¸Ã¢â‚¬â„¢Ã‚Â¡", "ÃƒÂ°Ã…Â¸Ã…Â¡Ã¢â€šÂ¬", "ÃƒÂ°Ã…Â¸Ã…Â½Ã‚Â¯", "ÃƒÂ°Ã…Â¸Ã‚ÂÃ¢â‚¬Â ", "ÃƒÂ°Ã…Â¸Ã¢â‚¬â„¢Ã‚Â¬");
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
            emoji.setTooltip(new Tooltip("ChÃƒÆ’Ã‚Â¨n " + value));
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
        String accent = validHex(userSettings.accentColor) ? userSettings.accentColor : "#007aff";
        String accentDark = darken(accent);
        if (appRoot != null) {
            appRoot.setStyle("-app-accent: " + accent + "; -app-accent-dark: " + accentDark + ";");
        }
        String background = chatBackgroundStyle(userSettings.chatBackground);
        if (messageScroll != null) {
            messageScroll.setStyle(background);
        }
        if (messageBox != null) {
            messageBox.setStyle(background);
        }
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
                showInfo("File khÃƒÆ’Ã‚Â´ng tÃƒÂ¡Ã‚Â»Ã¢â‚¬Å“n tÃƒÂ¡Ã‚ÂºÃ‚Â¡i: " + file.getAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(file);
        } catch (Exception e) {
            showError("KhÃƒÆ’Ã‚Â´ng mÃƒÂ¡Ã‚Â»Ã…Â¸ Ãƒâ€žÃ¢â‚¬ËœÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã‚Â£c file", e);
        }
    }

    private Node emptyState(String title, String detail) {
        Label icon = new Label("ÃƒÂ°Ã…Â¸Ã¢â‚¬â„¢Ã‚Â¬");
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
            case "DIRECT" -> "TrÃƒÆ’Ã‚Â² chuyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n 1-1";
            case "GROUP" -> "NhÃƒÆ’Ã‚Â³m nÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i bÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢";
            case "COMPANY" -> "ToÃƒÆ’Ã‚Â n cÃƒÆ’Ã‚Â´ng ty";
            case "DEPARTMENT" -> "PhÃƒÆ’Ã‚Â²ng ban";
            default -> "HÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢i thoÃƒÂ¡Ã‚ÂºÃ‚Â¡i";
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
            headerDetail.setText("TrÃƒÆ’Ã‚Â² chuyÃƒÂ¡Ã‚Â»Ã¢â‚¬Â¡n 1-1 Ãƒâ€šÃ‚Â· " + presenceText(user));
        }, e -> {
        });
    }

    private String presenceText(ChatUser user) {
        if (user == null || user.lastSeenAt == null) {
            return "ChÃƒâ€ Ã‚Â°a cÃƒÆ’Ã‚Â³ trÃƒÂ¡Ã‚ÂºÃ‚Â¡ng thÃƒÆ’Ã‚Â¡i hoÃƒÂ¡Ã‚ÂºÃ‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ng";
        }
        long minutes = java.time.Duration.between(user.lastSeenAt, LocalDateTime.now()).toMinutes();
        if (minutes < 0) {
            minutes = 0;
        }
        if (minutes <= 2) {
            return "Ãƒâ€žÃ‚Âang online";
        }
        if (minutes < 60) {
            return "HoÃƒÂ¡Ã‚ÂºÃ‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ng " + minutes + " phÃƒÆ’Ã‚Âºt trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "HoÃƒÂ¡Ã‚ÂºÃ‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ng " + hours + " giÃƒÂ¡Ã‚Â»Ã‚Â trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc";
        }
        return "HoÃƒÂ¡Ã‚ÂºÃ‚Â¡t Ãƒâ€žÃ¢â‚¬ËœÃƒÂ¡Ã‚Â»Ã¢â€žÂ¢ng " + (hours / 24) + " ngÃƒÆ’Ã‚Â y trÃƒâ€ Ã‚Â°ÃƒÂ¡Ã‚Â»Ã¢â‚¬Âºc";
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
        ButtonType save = new ButtonType("LÃƒâ€ Ã‚Â°u", ButtonBar.ButtonData.OK_DONE);
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
    }

    private void showError(String title, Exception e) {
        e.printStackTrace();
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

    private record VoteDraft(String question, List<String> options) {
    }
}
