package chatapp;

import java.util.Set;

import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class SidebarPanel {
    interface Callbacks {
        Node avatarNode(String displayName, String styleClass, String username);
        Node svgIcon(String iconName, double size);
        void showAccountMenu(Button owner);
        void showPresenceMenu(Button owner);
        String presenceText();
        void openDirectDialog();
        void openGroupDialog();
        void conversationSelected(Conversation conversation);
        void showHomePanel();
        void showContactPanel();
        void showMyTasksPanel();
        void showNotificationsPanel();
        void showCalendarPanel();
        void showSettingsDialog();
        void logoutAndExit();
        void showToast(String message);
    }

    private final CurrentUser currentUser;
    private final UserSettings userSettings;
    private final ObservableList<Conversation> conversations;
    private final ObservableList<Conversation> visibleConversations;
    private final Set<Long> conversationIdsWithTasks;
    private final Callbacks callbacks;

    private final HBox root = new HBox(0);
    private final ListView<Conversation> conversationList;
    private final TextField conversationSearch = new TextField();
    private VBox conversationPanel;
    private VBox navRail;
    private VBox navItemsBox;
    private Button notificationsNavButton;
    private Button tasksNavButton;
    private String activeNav = "CHAT";
    private String conversationFilter = "ALL";

    SidebarPanel(CurrentUser currentUser,
                 UserSettings userSettings,
                 ObservableList<Conversation> conversations,
                 ObservableList<Conversation> visibleConversations,
                 Set<Long> conversationIdsWithTasks,
                 Callbacks callbacks) {
        this.currentUser = currentUser;
        this.userSettings = userSettings;
        this.conversations = conversations;
        this.visibleConversations = visibleConversations;
        this.conversationIdsWithTasks = conversationIdsWithTasks;
        this.callbacks = callbacks;
        this.conversationList = new ListView<>(visibleConversations);
        build();
    }

    Node node() {
        return root;
    }

    ListView<Conversation> conversationList() {
        return conversationList;
    }

    TextField conversationSearch() {
        return conversationSearch;
    }

    void setActive(String navKey) {
        activeNav = navKey;
        refreshActiveStyles();
    }

    void showConversations() {
        setActive("CHAT");
        conversationPanel.getChildren().setAll(conversationHeader(), searchRow(), searchModeRow(), filterRow(), actionsRow(), conversationList);
        VBox.setVgrow(conversationList, Priority.ALWAYS);
        refreshConversationFilter();
    }

    void showPage(String title, String subtitle, Node content) {
        Label pageTitle = new Label(title);
        pageTitle.getStyleClass().add("sidebar-title");
        Label pageSub = new Label(subtitle);
        pageSub.getStyleClass().add("sidebar-subtitle");
        Button back = new Button("Chat");
        back.getStyleClass().add("sidebar-action");
        back.setGraphic(callbacks.svgIcon("chat", 15));
        back.setOnAction(e -> showConversations());
        VBox titleBlock = new VBox(2, pageTitle, pageSub);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        HBox header = new HBox(12, titleBlock, back);
        header.setAlignment(Pos.CENTER_LEFT);
        conversationPanel.getChildren().setAll(header, content);
        VBox.setVgrow(content, Priority.ALWAYS);
    }

    void refreshConversationFilter() {
        String query = Texts.normalize(conversationSearch.getText());
        visibleConversations.setAll(conversations.stream()
                .filter(this::matchesConversationFilter)
                .filter(c -> query.isBlank()
                        || Texts.normalize(c.title).contains(query)
                        || Texts.normalize(c.lastMessage).contains(query))
                .toList());
    }

    void refreshBadges(int unread, int overdue) {
        if (notificationsNavButton != null) {
            notificationsNavButton.setText(unread > 0 ? "THÔNG BÁO (" + unread + ")" : "THÔNG BÁO");
        }
        if (tasksNavButton != null) {
            tasksNavButton.setText(overdue > 0 ? "TASKS (" + overdue + ")" : "TASKS");
        }
    }

    void applyCollapsed() {
        if (conversationPanel == null || navRail == null) {
            return;
        }
        boolean collapsed = userSettings.sidebarCollapsed;
        conversationPanel.setVisible(!collapsed);
        conversationPanel.setManaged(!collapsed);
        conversationPanel.setMouseTransparent(collapsed);
        navRail.setPrefWidth(collapsed ? 462 : 142);
        navRail.setMinWidth(collapsed ? 430 : 130);
        navRail.setMaxWidth(collapsed ? 500 : 160);
        root.setPrefWidth(462);
        root.setMinWidth(430);
        root.setMaxWidth(500);
    }

    private void build() {
        root.getStyleClass().add("sidebar");
        root.setPrefWidth(462);
        root.setMinWidth(430);
        root.setMaxWidth(500);

        navRail = new VBox(24, profileBlock(), navItems(), navItem("collapse", "COLLAPSE", null, this::toggleCollapsed), logoutItem());
        navRail.getStyleClass().add("nav-rail");
        navRail.setAlignment(Pos.TOP_CENTER);
        navRail.setPrefWidth(142);
        navRail.setMinWidth(130);

        conversationList.getStyleClass().add("conversation-list");
        conversationList.setCellFactory(list -> new SidebarCells.ConversationCell());
        conversationList.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if (selected != null) {
                callbacks.conversationSelected(selected);
            }
        });

        conversationPanel = new VBox(14);
        conversationPanel.getStyleClass().add("conversation-panel");
        HBox.setHgrow(conversationPanel, Priority.ALWAYS);
        root.getChildren().addAll(navRail, conversationPanel);
        showConversations();
        applyCollapsed();
    }

    private Node profileBlock() {
        Node profileAvatar = callbacks.avatarNode(currentUser.displayName, "profile-avatar", currentUser.username);
        Label presenceDot = new Label();
        presenceDot.getStyleClass().addAll("presence-dot", "presence-" + userSettings.presenceStatus.toLowerCase());
        StackPane avatarWrap = new StackPane(profileAvatar, presenceDot);
        StackPane.setAlignment(presenceDot, Pos.BOTTOM_RIGHT);
        Label name = new Label(currentUser.displayName);
        name.getStyleClass().add("profile-name");
        Label role = new Label((currentUser.isAdmin() ? "Quản trị viên" : "Nhân viên") + " - " + currentUser.companyOwner);
        role.getStyleClass().add("profile-role");
        Button accountMenu = iconButton("chevron", "Tài khoản", "account-menu-button");
        accountMenu.setOnAction(e -> callbacks.showAccountMenu(accountMenu));
        HBox profileName = new HBox(4, name, accountMenu);
        profileName.setAlignment(Pos.CENTER);
        Button presence = new Button(callbacks.presenceText());
        presence.getStyleClass().add("presence-button");
        presence.setOnAction(e -> callbacks.showPresenceMenu(presence));
        VBox profile = new VBox(7, avatarWrap, profileName, role, presence);
        profile.setAlignment(Pos.CENTER);
        profile.getStyleClass().add("profile-bar");
        return profile;
    }

    private VBox navItems() {
        Button homeNav = navItem("home", "TỔNG QUAN", "HOME", callbacks::showHomePanel);
        Button chatNav = navItem("chat", "CHAT", "CHAT", this::showConversations);
        Button contactNav = navItem("contact", "CONTACT", "CONTACT", callbacks::showContactPanel);
        notificationsNavButton = navItem("bell", "THÔNG BÁO", "NOTIFICATIONS", callbacks::showNotificationsPanel);
        tasksNavButton = navItem("tasks", "TASKS", "TASKS", callbacks::showMyTasksPanel);
        Button calendarNav = navItem("calendar", "CALENDAR", "CALENDAR", callbacks::showCalendarPanel);
        Button settingsNav = navItem("settings", "SETTINGS", "SETTINGS", callbacks::showSettingsDialog);
        navItemsBox = new VBox(10, homeNav, chatNav, contactNav, notificationsNavButton, tasksNavButton, calendarNav, settingsNav);
        navItemsBox.getStyleClass().add("nav-items");
        VBox.setVgrow(navItemsBox, Priority.ALWAYS);
        return navItemsBox;
    }

    private Button logoutItem() {
        Button logout = navItem("logout", "LOG OUT", null, callbacks::logoutAndExit);
        logout.getStyleClass().add("logout-nav-item");
        return logout;
    }

    private Node conversationHeader() {
        Label panelTitle = new Label("Chats");
        panelTitle.getStyleClass().add("sidebar-title");
        Label panelSub = new Label("Recent Chats");
        panelSub.getStyleClass().add("sidebar-subtitle");
        VBox titleBlock = new VBox(2, panelTitle, panelSub);
        HBox.setHgrow(titleBlock, Priority.ALWAYS);
        Button newDirect = new Button("Chat mới");
        newDirect.getStyleClass().add("sidebar-action");
        newDirect.setGraphic(callbacks.svgIcon("new-chat", 15));
        newDirect.setOnAction(e -> callbacks.openDirectDialog());
        HBox panelHeader = new HBox(12, titleBlock, newDirect);
        panelHeader.getStyleClass().add("conversation-panel-header");
        panelHeader.setAlignment(Pos.CENTER_LEFT);
        return panelHeader;
    }

    private Node searchRow() {
        conversationSearch.setPromptText("Tìm hội thoại");
        conversationSearch.getStyleClass().add("search-field");
        conversationSearch.textProperty().addListener((obs, old, value) -> refreshConversationFilter());
        Label searchIcon = new Label();
        searchIcon.setGraphic(callbacks.svgIcon("search", 15));
        searchIcon.getStyleClass().add("search-icon");
        Label filter = new Label("Tin nhắn");
        filter.getStyleClass().add("search-filter");
        HBox searchRow = new HBox(8, searchIcon, conversationSearch, filter);
        searchRow.getStyleClass().add("search-row");
        searchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(conversationSearch, Priority.ALWAYS);
        return searchRow;
    }

    private Node actionsRow() {
        Button newGroup = new Button("Nhóm");
        newGroup.getStyleClass().add("sidebar-action");
        newGroup.setGraphic(callbacks.svgIcon("users", 15));
        newGroup.setDisable(!currentUser.canManageGroups());
        newGroup.setOnAction(e -> callbacks.openGroupDialog());
        HBox actions = new HBox(10, newGroup);
        actions.getStyleClass().add("sidebar-actions");
        HBox.setHgrow(newGroup, Priority.ALWAYS);
        newGroup.setMaxWidth(Double.MAX_VALUE);
        return actions;
    }

    private HBox filterRow() {
        HBox row = new HBox(7,
                chipButton("Tất cả", () -> setConversationFilter("ALL")),
                chipButton("Chưa đọc", () -> setConversationFilter("UNREAD")),
                chipButton("1-1", () -> setConversationFilter("DIRECT")),
                chipButton("Nhóm", () -> setConversationFilter("GROUP")),
                chipButton("Có task", () -> setConversationFilter("TASK")),
                chipButton("Đã ghim", () -> setConversationFilter("PINNED")));
        row.getStyleClass().add("filter-row");
        return row;
    }

    private HBox searchModeRow() {
        HBox row = new HBox(7,
                chipButton("Chat", () -> {}),
                chipButton("Người", callbacks::showContactPanel),
                chipButton("Task", callbacks::showMyTasksPanel),
                chipButton("Tin", () -> callbacks.showToast("Tìm tin nhắn sẽ được bổ sung.")),
                chipButton("File", () -> callbacks.showToast("Tìm file sẽ được bổ sung.")));
        row.getStyleClass().add("filter-row");
        return row;
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
                setActive(navKey);
            }
            action.run();
        });
        return button;
    }

    private Node iconBadge(String iconName, double size, String styleClass) {
        StackPane badge = new StackPane(callbacks.svgIcon(iconName, size));
        badge.getStyleClass().add(styleClass);
        badge.setMinSize(34, 34);
        badge.setPrefSize(34, 34);
        badge.setMaxSize(34, 34);
        return badge;
    }

    private Button iconButton(String iconName, String tooltip, String styleClass) {
        Button button = new Button();
        button.getStyleClass().add(styleClass);
        button.setTooltip(new Tooltip(tooltip));
        button.setGraphic(callbacks.svgIcon(iconName, 18));
        return button;
    }

    private Button chipButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("filter-chip");
        button.setOnAction(e -> action.run());
        return button;
    }

    private void setConversationFilter(String filter) {
        conversationFilter = filter;
        refreshConversationFilter();
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

    private void refreshActiveStyles() {
        if (navItemsBox == null) {
            return;
        }
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

    private void toggleCollapsed() {
        userSettings.sidebarCollapsed = !userSettings.sidebarCollapsed;
        userSettings.save();
        applyCollapsed();
    }
}
