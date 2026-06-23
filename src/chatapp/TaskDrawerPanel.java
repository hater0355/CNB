package chatapp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class TaskDrawerPanel extends VBox {
    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final Runnable onClose;
    private final BiConsumer<ChatTask, String> onStatusChange;
    private final BiFunction<String, String, Node> avatarProvider;
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label("0% hoàn thành");
    private final Label totalLabel = taskMetric("Tổng", "0");
    private final Label doingLabel = taskMetric("Đang làm", "0");
    private final Label overdueLabel = taskMetric("Quá hạn", "0");
    private final VBox taskListBox = new VBox(12);

    TaskDrawerPanel(Runnable onClose, BiConsumer<ChatTask, String> onStatusChange, BiFunction<String, String, Node> avatarProvider) {
        this.onClose = onClose;
        this.onStatusChange = onStatusChange;
        this.avatarProvider = avatarProvider;
        build();
    }

    void render(List<ChatTask> currentTasks) {
        List<ChatTask> tasks = currentTasks == null ? List.of() : currentTasks;
        long done = tasks.stream().filter(t -> "DONE".equals(t.status)).count();
        long doing = tasks.stream().filter(t -> "IN_PROGRESS".equals(t.status) || "REVIEW".equals(t.status)).count();
        long overdue = tasks.stream().filter(this::isTaskOverdue).count();
        double progress = tasks.isEmpty() ? 0 : (double) done / tasks.size();
        progressBar.setProgress(progress);
        progressLabel.setText(Math.round(progress * 100) + "% hoàn thành");
        totalLabel.setText("Tổng\n" + tasks.size());
        doingLabel.setText("Đang làm\n" + doing);
        overdueLabel.setText("Quá hạn\n" + overdue);

        taskListBox.getChildren().clear();
        if (tasks.isEmpty()) {
            taskListBox.getChildren().add(emptyState("Chưa có công việc", "Giao việc từ menu tin nhắn để theo dõi tại đây."));
            return;
        }
        for (String status : List.of("TODO", "IN_PROGRESS", "REVIEW", "DONE")) {
            List<ChatTask> group = tasks.stream().filter(t -> status.equals(t.status)).toList();
            taskListBox.getChildren().add(taskGroup(status, group));
        }
    }

    private void build() {
        Label title = new Label("Bảng tiến độ công việc");
        title.getStyleClass().add("task-drawer-title");
        Button close = new Button("×");
        close.getStyleClass().add("task-close-button");
        close.setTooltip(new Tooltip("Đóng bảng công việc"));
        close.setOnAction(e -> onClose.run());
        HBox header = new HBox(10, title, close);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        progressBar.getStyleClass().add("task-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressLabel.getStyleClass().add("task-progress-label");

        HBox metrics = new HBox(10, totalLabel, doingLabel, overdueLabel);
        metrics.getStyleClass().add("task-metrics");

        taskListBox.getStyleClass().add("task-list");
        ScrollPane scroll = new ScrollPane(taskListBox);
        scroll.getStyleClass().add("task-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        getChildren().setAll(header, progressBar, progressLabel, metrics, scroll);
        getStyleClass().add("task-drawer");
        setPrefWidth(360);
        setMinWidth(330);
        setMaxWidth(390);
        setSpacing(14);
    }

    private Node taskGroup(String status, List<ChatTask> tasks) {
        Label title = new Label(taskStatusText(status) + " (" + tasks.size() + ")");
        title.getStyleClass().add("task-group-title");
        VBox cards = new VBox(8);
        for (ChatTask task : tasks) {
            cards.getChildren().add(taskCard(task));
        }
        VBox group = new VBox(8, title, cards);
        group.getStyleClass().add("task-group");
        return group;
    }

    private Node taskCard(ChatTask task) {
        Label title = new Label(task.title);
        title.getStyleClass().add("task-card-title");
        title.setWrapText(true);

        Node avatar = avatarProvider.apply(task.assigneeName, task.assigneeUsername);
        Label assignee = new Label(task.assigneeName == null || task.assigneeName.isBlank() ? task.assigneeUsername : task.assigneeName);
        assignee.getStyleClass().add("task-assignee");
        HBox assigneeRow = new HBox(8, avatar, assignee);
        assigneeRow.setAlignment(Pos.CENTER_LEFT);

        Label priority = new Label(task.priority);
        priority.getStyleClass().addAll("task-priority", "task-priority-" + task.priority.toLowerCase());
        Label kpi = new Label("+" + task.kpiPoints + " KPI");
        kpi.getStyleClass().add("task-kpi");
        Button menu = new Button("⋯");
        menu.getStyleClass().addAll("composer-icon-button", "task-card-menu");
        menu.setTooltip(new Tooltip("Đổi trạng thái"));
        menu.setOnAction(e -> showTaskStatusMenu(task, menu));
        HBox badges = new HBox(8, priority, kpi, menu);
        badges.setAlignment(Pos.CENTER_LEFT);

        Label deadline = new Label(taskDeadlineText(task));
        deadline.getStyleClass().add(isTaskOverdue(task) ? "task-deadline-danger" : "task-deadline");
        VBox card = new VBox(9, title, assigneeRow, badges, deadline);
        card.getStyleClass().add("task-card");
        return card;
    }

    private void showTaskStatusMenu(ChatTask task, Button owner) {
        ContextMenu menu = new ContextMenu();
        for (String status : List.of("TODO", "IN_PROGRESS", "REVIEW", "DONE")) {
            MenuItem item = new MenuItem(taskStatusText(status));
            item.setDisable(status.equals(task.status));
            item.setOnAction(e -> onStatusChange.accept(task, status));
            menu.getItems().add(item);
        }
        menu.show(owner, javafx.geometry.Side.BOTTOM, 0, 4);
    }

    private static Label taskMetric(String key, String value) {
        Label label = new Label(key + "\n" + value);
        label.getStyleClass().add("task-metric");
        label.setAlignment(Pos.CENTER);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        return label;
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

    private static Node emptyState(String title, String detail) {
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
}
