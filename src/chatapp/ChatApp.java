package chatapp;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javafx.animation.PauseTransition;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public class ChatApp extends Application {
    private static final DateTimeFormatter MESSAGE_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CONVERSATION_TIME = DateTimeFormatter.ofPattern("dd/MM");

    private final AppConfig config = AppConfig.load();
    private final Database database = new Database(config);
    private final ChatAuthService authService = new ChatAuthService(database);
    private final FileStorageService storageService = new FileStorageService(config);
    private final ChatService chatService = new ChatService(database, storageService);
    private final UserSettings userSettings = UserSettings.load();

    private Stage stage;
    private CurrentUser currentUser;
    private Conversation currentConversation;
    private final ObservableList<Conversation> conversations = FXCollections.observableArrayList();
    private final ObservableList<Conversation> visibleConversations = FXCollections.observableArrayList();
    private final List<File> selectedFiles = new ArrayList<>();
    private List<ChatUser> companyUsers = new ArrayList<>();
    private List<ChatMessage> currentMessages = new ArrayList<>();
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
    private RealtimeClient realtimeClient;
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

        Button login = new Button("Đăng nhập");
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
                    return false;
                }
                chatService.cleanupOldMessages(config.retentionDays);
                chatService.ensureCompanyConversation(currentUser);
                companyUsers = chatService.listCompanyUsers(currentUser);
                return true;
            }, ok -> {
                login.setDisable(false);
                if (!ok) {
                    status.setText("Sai tài khoản/mật khẩu hoặc tài khoản nhân viên chưa được duyệt.");
                    return;
                }
                showChat();
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

    private void showChat() {
        BorderPane root = new BorderPane();
        appRoot = root;
        root.getStyleClass().add("root");
        root.setLeft(buildSidebar());
        root.setCenter(buildChatPane());

        Scene scene = new Scene(root, 1220, 780);
        applyCss(scene);
        applyPersonalization();
        installShortcuts(scene);
        stage.setScene(scene);
        stage.setMaximized(true);
        connectRealtime();
        loadConversationsAsync();
    }

    private Node buildSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(352);
        sidebar.setMinWidth(320);
        sidebar.setMaxWidth(380);

        Node profileAvatar = avatarNode(currentUser.displayName, "profile-avatar", currentUser.username);
        Label name = new Label(currentUser.displayName);
        name.getStyleClass().add("profile-name");
        Label role = new Label((currentUser.isAdmin() ? "Quản trị viên" : "Nhân viên") + " - " + currentUser.companyOwner);
        role.getStyleClass().add("profile-role");
        VBox profileText = new VBox(2, name, role);
        HBox.setHgrow(profileText, Priority.ALWAYS);
        Button accountMenu = new Button("...");
        accountMenu.getStyleClass().add("account-menu-button");
        accountMenu.setOnAction(e -> showAccountMenu(accountMenu));
        HBox profile = new HBox(12, profileAvatar, profileText, accountMenu);
        profile.setAlignment(Pos.CENTER_LEFT);
        profile.getStyleClass().add("profile-bar");

        conversationSearch = new TextField();
        conversationSearch.setPromptText("Tìm kiếm hội thoại");
        conversationSearch.getStyleClass().add("search-field");
        conversationSearch.textProperty().addListener((obs, old, value) -> refreshConversationFilter());

        Button newDirect = new Button("+ Chat");
        newDirect.getStyleClass().add("sidebar-action");
        newDirect.setOnAction(e -> openDirectDialog());

        newGroupButton = new Button("+ Nhóm");
        newGroupButton.getStyleClass().add("sidebar-action");
        newGroupButton.setDisable(!currentUser.canManageGroups());
        newGroupButton.setOnAction(e -> openGroupDialog(null));

        HBox actions = new HBox(10, newDirect, newGroupButton);
        actions.getStyleClass().add("sidebar-actions");
        HBox.setHgrow(newDirect, Priority.ALWAYS);
        HBox.setHgrow(newGroupButton, Priority.ALWAYS);
        newDirect.setMaxWidth(Double.MAX_VALUE);
        newGroupButton.setMaxWidth(Double.MAX_VALUE);

        conversationList = new ListView<>(visibleConversations);
        conversationList.getStyleClass().add("conversation-list");
        conversationList.setCellFactory(list -> new ConversationCell());
        conversationList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null && !loadingConversations) {
                currentConversation = selected;
                hideChatHead();
                forceScrollToBottom = true;
                refreshMessages(true);
            }
        });
        VBox.setVgrow(conversationList, Priority.ALWAYS);

        sidebar.getChildren().addAll(profile, conversationSearch, actions, conversationList);
        return sidebar;
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

        pinConversationButton = new Button("Ghim");
        pinConversationButton.getStyleClass().add("header-button");
        pinConversationButton.setOnAction(e -> toggleConversationPin());
        manageGroupButton = new Button("Quản lý nhóm");
        manageGroupButton.getStyleClass().add("header-button");
        manageGroupButton.setOnAction(e -> openGroupDialog(currentConversation));

        HBox headerTop = new HBox(12, headerAvatar, titleBlock, pinConversationButton, manageGroupButton);
        headerTop.setAlignment(Pos.CENTER_LEFT);

        messageSearch = new TextField();
        messageSearch.setPromptText("Tìm trong cuộc trò chuyện");
        messageSearch.getStyleClass().add("message-search");
        messageSearch.textProperty().addListener((obs, old, value) -> refreshMessages(true));

        VBox header = new VBox(12, headerTop, messageSearch);
        header.getStyleClass().add("chat-header");
        pane.setTop(header);

        messageBox = new VBox(12);
        messageBox.getStyleClass().add("message-box");
        messageScroll = new ScrollPane(messageBox);
        messageScroll.getStyleClass().add("message-scroll");
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        pane.setCenter(messageScroll);

        replyLabel = new Label();
        replyLabel.getStyleClass().add("reply-composer");
        replyLabel.setVisible(false);
        replyLabel.setManaged(false);

        attachmentLabel = new Label();
        attachmentLabel.getStyleClass().add("attachment-label");
        attachmentLabel.setVisible(false);
        attachmentLabel.setManaged(false);

        input = new TextArea();
        input.setPromptText("Nhập tin nhắn, Ctrl+Enter để gửi");
        input.getStyleClass().add("composer-input");
        input.setPrefRowCount(2);
        input.setWrapText(true);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && e.isControlDown()) {
                sendCurrentMessage();
                e.consume();
            }
        });

        Button plus = composerIconButton("+", "Mở thêm tùy chọn");
        plus.setOnAction(e -> showPlusMenu(plus));
        Button clearFiles = composerIconButton("🗑", "Xóa file đang chọn");
        clearFiles.setOnAction(e -> {
            selectedFiles.clear();
            updateAttachmentLabel();
        });
        Button icons = composerIconButton("😊", "Chèn icon cảm xúc");
        icons.setOnAction(e -> showEmojiMenu(icons));
        Button send = composerIconButton("➤", "Gửi tin nhắn");
        send.getStyleClass().add("send-icon-button");
        send.setOnAction(e -> sendCurrentMessage());

        HBox inputRow = new HBox(10, plus, clearFiles, icons, input, send);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);
        VBox composer = new VBox(8, replyLabel, attachmentLabel, inputRow);
        composer.getStyleClass().add("composer");
        pane.setBottom(composer);
        installDragDrop(pane);
        return pane;
    }

    private void loadConversations() {
        runDb(() -> chatService.listConversations(currentUser), loaded -> {
            long selectedId = currentConversation == null ? 0 : currentConversation.id;
            try {
                loadingConversations = true;
                conversations.setAll(loaded);
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
                }
            } finally {
                loadingConversations = false;
            }
            int unread = loaded.stream().mapToInt(c -> c.unreadCount).sum();
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
        String query = Texts.normalize(conversationSearch == null ? "" : conversationSearch.getText());
        List<Conversation> filtered = conversations.stream()
                .filter(c -> query.isBlank()
                        || Texts.normalize(c.title).contains(query)
                        || Texts.normalize(c.lastMessage).contains(query))
                .collect(Collectors.toList());
        visibleConversations.setAll(filtered);
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
            manageGroupButton.setVisible(currentUser.canManageGroups() && "GROUP".equals(currentConversation.type));
            manageGroupButton.setManaged(manageGroupButton.isVisible());
            pinConversationButton.setText(currentConversation.pinned ? "Bỏ ghim" : "Ghim");
            pinConversationButton.setDisable(false);
            String search = messageSearch == null ? "" : Texts.safe(messageSearch.getText());
            long conversationId = currentConversation.id;
            boolean shouldScrollToBottom = forceScrollToBottom || forceRender || isNearBottom();
            runDb(() -> chatService.listMessages(currentUser, conversationId, search), messages -> {
                if (currentConversation == null || currentConversation.id != conversationId) {
                    return;
                }
                currentMessages = messages;
                if (currentMessages.isEmpty()) {
                    messageBox.getChildren().setAll(emptyState("Chưa có tin nhắn", "Hãy gửi lời chào để bắt đầu cuộc trò chuyện."));
                } else {
                    messageBox.getChildren().setAll(currentMessages.stream().map(this::messageNode).collect(Collectors.toList()));
                }
                lastRenderedConversationId = conversationId;
                lastRenderedMessageKey = search;
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
        }
    }

    private Node messageNode(ChatMessage msg) {
        boolean mine = currentUser.username.equals(msg.senderUsername);
        VBox bubble = new VBox(7);
        bubble.getStyleClass().add(mine ? "bubble-mine" : "bubble-other");
        bubble.setMaxWidth(640);

        Label name = new Label(mine ? "Bạn" : msg.senderName);
        name.getStyleClass().add("message-sender");
        Label time = new Label(msg.createdAt.format(MESSAGE_TIME) + (msg.edited ? " - đã sửa" : ""));
        time.getStyleClass().add("message-time");
        HBox meta = new HBox(8, name, time);
        meta.setAlignment(Pos.CENTER_LEFT);
        bubble.getChildren().add(meta);

        if (msg.pinned) {
            Label pinned = new Label("Tin đã ghim");
            pinned.getStyleClass().add("pin-chip");
            bubble.getChildren().add(pinned);
        }

        if (msg.replyToId != null) {
            Label reply = new Label("Trả lời: " + Texts.shortText(msg.replyPreview, 90));
            reply.getStyleClass().add("reply-preview");
            bubble.getChildren().add(reply);
        }

        if (msg.recalled) {
            Label recalled = new Label("Tin nhắn đã được thu hồi");
            recalled.getStyleClass().add("recalled-text");
            bubble.getChildren().add(recalled);
        } else {
            if ("SALARY_CARD".equals(msg.messageType)) {
                bubble.getChildren().add(salaryCardNode(msg));
            } else if ("WORKFLOW_CARD".equals(msg.messageType)) {
                bubble.getChildren().add(workflowCardNode(msg));
            } else if (msg.body != null && !msg.body.isBlank()) {
                if (isVoteMessage(msg.body)) {
                    bubble.getChildren().add(voteNode(msg.body));
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

        if (mine && msg.seenCount > 0) {
            Label seen = new Label("Đã xem: " + msg.seenCount);
            seen.getStyleClass().add("seen-label");
            bubble.getChildren().add(seen);
        }

        ContextMenu menu = messageMenu(msg, mine);
        bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
        Button more = composerIconButton("⋯", "Tùy chọn tin nhắn");
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
        return wrapper;
    }

    private ContextMenu messageMenu(ChatMessage msg, boolean mine) {
        ContextMenu menu = new ContextMenu();
        javafx.scene.control.MenuItem reply = new javafx.scene.control.MenuItem("Trả lời");
        reply.setOnAction(e -> {
            replyToId = msg.id;
            replyLabel.setText("Đang trả lời: " + Texts.shortText(msg.recalled ? "Tin đã thu hồi" : msg.body, 80));
            replyLabel.setVisible(true);
            replyLabel.setManaged(true);
            input.requestFocus();
        });
        javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem("Sửa tin");
        edit.setDisable(!mine || msg.recalled);
        edit.setOnAction(e -> editMessage(msg));
        javafx.scene.control.MenuItem recall = new javafx.scene.control.MenuItem("Thu hồi");
        recall.setDisable(!mine || msg.recalled);
        recall.setOnAction(e -> recallMessage(msg));
        javafx.scene.control.MenuItem pin = new javafx.scene.control.MenuItem(msg.pinned ? "Bỏ ghim tin" : "Ghim tin");
        pin.setOnAction(e -> pinMessage(msg));
        javafx.scene.control.MenuItem forward = new javafx.scene.control.MenuItem("Chuyển tiếp");
        forward.setDisable(msg.recalled);
        forward.setOnAction(e -> forwardMessage(msg));
        javafx.scene.control.MenuItem task = new javafx.scene.control.MenuItem("Chuyển thành công việc (Giao KPI Task)");
        task.setDisable(msg.recalled);
        task.setOnAction(e -> createTaskFromMessage(msg));
        menu.getItems().addAll(reply, edit, recall, pin, forward, new SeparatorMenuItem(), task);
        return menu;
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
        list.setCellFactory(view -> new ConversationCell());
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

    private boolean isVoteMessage(String body) {
        return body != null && body.startsWith("[VOTE]") && body.contains("|");
    }

    private Node voteNode(String body) {
        String raw = body.substring("[VOTE]".length()).trim();
        String[] parts = raw.split("\\|");
        Label title = new Label(parts.length == 0 ? "Bình chọn" : parts[0].trim());
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
                showToast("Đã chọn tạm thời: " + optionText);
            });
            box.getChildren().add(option);
        }
        if (box.getChildren().size() == 1) {
            box.getChildren().add(label("Chưa có lựa chọn.", "settings-note"));
        }
        return box;
    }

    private Node attachmentNode(Attachment a) {
        File f = new File(a.sharedPath);
        if ("IMAGE".equals(a.fileType) && f.isFile()) {
            VBox box = new VBox(6);
            ImageView view = new ImageView(new Image(f.toURI().toString(), 360, 240, true, true, true));
            view.getStyleClass().add("image-preview");
            view.setOnMouseClicked(e -> openFile(f));
            Label caption = new Label(a.originalName);
            caption.getStyleClass().add("attachment-caption");
            box.getChildren().addAll(view, caption);
            return box;
        }
        Label icon = new Label("VIDEO".equals(a.fileType) ? "▶" : "📄");
        icon.getStyleClass().add("file-icon");
        Label name = new Label(a.originalName);
        name.getStyleClass().add("file-name");
        Label size = new Label(formatSize(a.fileSize));
        size.getStyleClass().add("file-size");
        VBox info = new VBox(2, name, size);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button open = new Button("Mở");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> openFile(f));
        HBox card = new HBox(10, icon, info, open);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("file-card");
        return card;
    }

    private Node salaryCardNode(ChatMessage msg) {
        Label title = new Label("Phiếu lương bảo mật");
        title.getStyleClass().add("vote-title");
        Label body = new Label(Texts.safe(msg.body));
        body.getStyleClass().add("message-body");
        body.setWrapText(true);
        Button open = new Button("Xem chi tiết phiếu lương");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> openSalaryCard(msg));
        VBox card = new VBox(8, title, body, open);
        card.getStyleClass().add("vote-card");
        return card;
    }

    private Node workflowCardNode(ChatMessage msg) {
        Label title = new Label("Quy trình tương tác");
        title.getStyleClass().add("vote-title");
        Label body = new Label(Texts.safe(msg.body));
        body.getStyleClass().add("message-body");
        body.setWrapText(true);
        Label status = new Label("Trạng thái: " + (msg.workflowStatus == null || msg.workflowStatus.isBlank() ? "PENDING" : msg.workflowStatus));
        status.getStyleClass().add("pin-chip");
        HBox actions = new HBox(8);
        if (currentUser.canManageGroups() && (msg.workflowStatus == null || "PENDING".equals(msg.workflowStatus))) {
            Button approve = new Button("Đồng ý");
            approve.getStyleClass().add("file-open-button");
            approve.setOnAction(e -> decideWorkflow(msg, true));
            Button reject = new Button("Từ chối");
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
            chatService.decideWorkflow(currentUser, msg.id, approved);
            return true;
        }, ok -> {
            refreshMessages(true);
            publishRealtime("WORKFLOW_UPDATED", msg.conversationId);
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
        DatePicker date = new DatePicker(LocalDate.now().plusDays(1));
        TextField shift = new TextField("OT".equals(type) ? "Tăng ca" : "Ca mới");
        shift.getStyleClass().add("dialog-search");
        VBox content = new VBox(10, label("Ngày làm việc", "dialog-label"), date, label("Ca/Nội dung", "dialog-label"), shift);
        content.getStyleClass().add("dialog-content");
        dialog.getDialogPane().setContent(content);
        dialog.setResultConverter(btn -> btn == send ? shift.getText() : null);
        dialog.showAndWait().ifPresent(value -> {
            long conversationId = currentConversation.id;
            runDb(() -> chatService.createWorkflow(currentUser, conversationId, type, date.getValue(), value), messageId -> {
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

    private void showPlusMenu(Button owner) {
        ContextMenu menu = new ContextMenu();
        MenuItem files = new MenuItem("Gửi file");
        files.setOnAction(e -> chooseFiles());
        MenuItem media = new MenuItem("Gửi ảnh/video");
        media.setOnAction(e -> chooseMediaFiles());
        MenuItem vote = new MenuItem("Tạo vote");
        vote.setOnAction(e -> showVoteDialog());
        MenuItem ot = new MenuItem("Xin tăng ca");
        ot.setOnAction(e -> showWorkflowDialog("OT"));
        MenuItem shift = new MenuItem("Xin đổi ca");
        shift.setOnAction(e -> showWorkflowDialog("SHIFT_CHANGE"));
        menu.getItems().addAll(files, media, vote, new SeparatorMenuItem(), ot, shift);
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
            return "[VOTE] " + question.getText().trim() + " | " + String.join(" | ", choices);
        });
        dialog.showAndWait().ifPresent(vote -> {
            long conversationId = currentConversation.id;
            runDb(() -> chatService.sendMessage(currentUser, conversationId, vote, null, List.of()), messageId -> {
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
            users.setCellFactory(list -> new UserCell());
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
                    } else {
                        id = editConversation.id;
                        chatService.updateGroup(currentUser, id, form.title, form.members);
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
            return true;
        }, ok -> {
            loadConversations();
            publishRealtime("CONVERSATION_UPDATED", conversationId);
        }, e -> {
            showError("Không ghim được hội thoại", e);
        });
    }

    private void connectRealtime() {
        if (realtimeClient != null) {
            realtimeClient.close();
        }
        realtimeClient = new RealtimeClient(config, () -> Platform.runLater(() -> {
            loadConversations();
            if (currentConversation != null) {
                refreshMessages(true);
            }
        }));
        realtimeClient.connect(currentUser.username);
    }

    private void publishRealtime(String type, long conversationId) {
        if (realtimeClient != null) {
            realtimeClient.publish(type, conversationId);
        }
    }

    private void notifyNewMessage(Conversation conversation) {
        if (userSettings.soundEnabled) {
            try {
                java.awt.Toolkit.getDefaultToolkit().beep();
            } catch (Exception ignored) {
            }
        }
        if (userSettings.toastEnabled) {
            showToast(conversation == null ? "Có tin nhắn mới" : "Có tin mới từ " + conversation.title);
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
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Cài đặt");
        styleDialog(dialog);
        ButtonType save = new ButtonType("Lưu", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);

        CheckBox sound = new CheckBox("Phát âm báo khi có tin nhắn mới");
        sound.getStyleClass().add("settings-check");
        sound.setSelected(userSettings.soundEnabled);
        CheckBox toast = new CheckBox("Hiện thông báo nổi khi có tin nhắn mới");
        toast.getStyleClass().add("settings-check");
        toast.setSelected(userSettings.toastEnabled);
        CheckBox darkMode = new CheckBox("Chế độ tối (chuẩn bị cho bản sau)");
        darkMode.getStyleClass().add("settings-check");
        darkMode.setSelected("dark".equalsIgnoreCase(userSettings.theme));
        darkMode.setDisable(true);
        String[] selectedAccent = {validHex(userSettings.accentColor) ? userSettings.accentColor : "#0068ff"};
        VBox accentPalette = colorPalette(selectedAccent);
        ComboBox<String> backgroundPicker = new ComboBox<>(FXCollections.observableArrayList(
                "soft-blue", "clean-white", "mint", "lavender", "peach", "night"));
        backgroundPicker.getStyleClass().add("dialog-search");
        backgroundPicker.setValue(userSettings.chatBackground == null || userSettings.chatBackground.isBlank() ? "soft-blue" : userSettings.chatBackground);
        String[] selectedAvatar = {userSettings.avatarPath(currentUser.username)};
        Label avatarPath = new Label(selectedAvatar[0] == null || selectedAvatar[0].isBlank() ? "Chưa chọn avatar" : selectedAvatar[0]);
        avatarPath.getStyleClass().add("settings-value");
        avatarPath.setWrapText(true);
        Button chooseAvatar = new Button("Chọn avatar");
        chooseAvatar.getStyleClass().add("header-button");
        chooseAvatar.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Chọn ảnh đại diện");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
            File file = chooser.showOpenDialog(stage);
            if (file != null) {
                selectedAvatar[0] = file.getAbsolutePath();
                avatarPath.setText(selectedAvatar[0]);
            }
        });

        Label filePath = new Label(config.filesRoot.toAbsolutePath().toString());
        filePath.getStyleClass().add("settings-value");
        filePath.setWrapText(true);
        Button openFolder = new Button("Mở thư mục");
        openFolder.getStyleClass().add("header-button");
        openFolder.setOnAction(e -> openFilesRoot());

        VBox notificationSection = settingsSection("Thông báo", sound, toast);
        VBox appearanceSection = settingsSection("Giao diện",
                settingLine("Avatar", "Ảnh đại diện lưu riêng trên máy này"),
                avatarPath,
                chooseAvatar,
                settingLine("Màu chủ đạo", "Chọn màu bạn thích cho nút, avatar và điểm nhấn"),
                accentPalette,
                settingLine("Nền khung chat", "soft-blue, clean-white, mint, lavender, peach, night"),
                backgroundPicker,
                darkMode,
                label("Màu và nền được lưu riêng trên máy của từng nhân viên.", "settings-note"));
        VBox fileSection = settingsSection("File", filePath, openFolder,
                label(Files.exists(config.filesRoot) ? "Thư mục đang tồn tại." : "Thư mục chưa tồn tại, app sẽ tự tạo khi gửi file.", "settings-note"));
        VBox systemSection = settingsSection("Hệ thống",
                settingLine("Database", config.dbUrl),
                settingLine("Giới hạn ảnh", config.maxImageMb + " MB"),
                settingLine("Giới hạn video", config.maxVideoMb + " MB"),
                settingLine("Giới hạn file", config.maxFileMb + " MB"),
                settingLine("Làm mới tin nhắn", config.pollSeconds + " giây"),
                settingLine("Tự xóa tin cũ", config.retentionDays + " ngày"),
                settingLine("File cài đặt", userSettings.path().toString()));

        VBox content = new VBox(14, notificationSection, appearanceSection, fileSection, systemSection);
        content.getStyleClass().add("settings-content");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(620, 560);
        scroll.getStyleClass().add("settings-scroll");
        dialog.getDialogPane().setContent(scroll);
        dialog.setResultConverter(btn -> {
            if (btn == save) {
                userSettings.soundEnabled = sound.isSelected();
                userSettings.toastEnabled = toast.isSelected();
                userSettings.theme = darkMode.isSelected() ? "dark" : "light";
                userSettings.accentColor = selectedAccent[0];
                userSettings.chatBackground = backgroundPicker.getValue();
                userSettings.setAvatarPath(currentUser.username, selectedAvatar[0]);
                userSettings.save();
                applyPersonalization();
                if (appRoot != null) {
                    appRoot.setLeft(buildSidebar());
                    refreshConversationFilter();
                    if (currentConversation != null) {
                        conversationList.getSelectionModel().select(currentConversation);
                    }
                }
                refreshMessages(true);
                showToast("Đã lưu cài đặt.");
            }
            return null;
        });
        dialog.showAndWait();
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
                if (realtimeClient != null) {
                    realtimeClient.close();
                }
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

    private Button composerIconButton(String icon, String tooltip) {
        Button button = new Button(icon);
        button.getStyleClass().add("composer-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private VBox colorPalette(String[] selectedAccent) {
        String[] colors = {
                "#0068ff", "#0ea5e9", "#10b981", "#22c55e",
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
        addEmojiGroup(menu, box, "Phản hồi nhanh", "👍", "👎", "👏", "🙏", "💪", "🔥", "✨", "❤️");
        addEmojiGroup(menu, box, "Công việc", "✅", "❌", "📌", "📎", "📷", "🎬", "💼", "⏰");
        addEmojiGroup(menu, box, "Không khí", "🎉", "⭐", "☕", "💡", "🚀", "🎯", "🏆", "💬");
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
        String accent = validHex(userSettings.accentColor) ? userSettings.accentColor : "#0068ff";
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
            return Color.web(validHex(value) ? value : "#0068ff");
        } catch (Exception ignored) {
            return Color.web("#0068ff");
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
            case "clean-white" -> "#f8fafc";
            case "mint" -> "linear-gradient(to bottom right, #ecfdf5, #f0fdfa)";
            case "lavender" -> "linear-gradient(to bottom right, #f5f3ff, #eef2ff)";
            case "peach" -> "linear-gradient(to bottom right, #fff7ed, #fff1f2)";
            case "night" -> "linear-gradient(to bottom right, #dbeafe, #e0e7ff)";
            default -> "linear-gradient(to bottom right, #eef6ff, #f8fbff)";
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
            default -> "Hội thoại";
        };
        return type + (c.lastMessage == null || c.lastMessage.isBlank() ? "" : " - " + c.lastMessage);
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

    private final class ConversationCell extends ListCell<Conversation> {
        @Override
        protected void updateItem(Conversation item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label avatar = avatar(item.title, "conversation-avatar");
            Label title = new Label(item.title);
            title.getStyleClass().add("conversation-title");
            Label preview = new Label(item.lastMessage == null || item.lastMessage.isBlank() ? "Chưa có tin nhắn" : item.lastMessage);
            preview.getStyleClass().add("conversation-last");
            VBox text = new VBox(4, title, preview);
            HBox.setHgrow(text, Priority.ALWAYS);

            VBox right = new VBox(6);
            right.setAlignment(Pos.CENTER_RIGHT);
            if (item.updatedAt != null) {
                Label time = new Label(item.updatedAt.format(CONVERSATION_TIME));
                time.getStyleClass().add("conversation-time");
                right.getChildren().add(time);
            }
            if (item.unreadCount > 0) {
                Label unread = new Label(String.valueOf(item.unreadCount));
                unread.getStyleClass().add("unread-badge");
                right.getChildren().add(unread);
            }

            HBox row = new HBox(10, avatar, text, right);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("conversation-row");
            setGraphic(row);
        }
    }

    private final class UserCell extends ListCell<ChatUser> {
        @Override
        protected void updateItem(ChatUser item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label avatar = avatar(item.displayName, "conversation-avatar");
            Label name = new Label(item.displayName);
            name.getStyleClass().add("conversation-title");
            Label detail = new Label(item.username + roleSuffix(item));
            detail.getStyleClass().add("conversation-last");
            HBox row = new HBox(10, avatar, new VBox(4, name, detail));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("dialog-user-row");
            setGraphic(row);
        }
    }

    private record GroupForm(String title, List<String> members) {
    }
}
