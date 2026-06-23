package chatapp;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

final class HomePanel {
    private HomePanel() {
    }

    static Node build(ChatApp.HomeSummary summary, Runnable openUnread, Runnable openTasks, Runnable openWorkflows, Runnable openCalendar) {
        VBox metrics = new VBox(10,
                settingLine("Tin chưa đọc", String.valueOf(summary.unread())),
                settingLine("Task quá hạn", String.valueOf(summary.overdueTasks())),
                settingLine("Workflow chờ duyệt", String.valueOf(summary.pendingWorkflows())),
                settingLine("Deadline hôm nay", String.valueOf(summary.dueToday())),
                settingLine("Nhắc đến tôi", String.valueOf(summary.mentions())));
        metrics.getStyleClass().add("settings-section");

        Button unread = actionButton("chat", "Mở chat chưa đọc", openUnread);
        Button tasks = actionButton("tasks", "Việc của tôi", openTasks);
        Button workflows = actionButton("bell", "Workflow chờ duyệt", openWorkflows);
        Button calendar = actionButton("calendar", "Lịch tuần này", openCalendar);

        VBox quickActions = new VBox(10, unread, tasks, workflows, calendar);
        quickActions.getStyleClass().add("settings-section");

        VBox content = new VBox(12, metrics, quickActions);
        content.getStyleClass().add("sidebar-page-list");
        if (summary.isQuiet()) {
            content.getChildren().add(emptyState("Hôm nay khá yên tĩnh",
                    "Không có tin chưa đọc, task quá hạn hoặc deadline cần xử lý ngay."));
        }
        return content;
    }

    private static Button actionButton(String icon, String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("sidebar-action");
        button.setGraphic(svgIcon(icon, 15));
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static HBox settingLine(String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("settings-key");
        Label valueLabel = new Label(value == null || value.isBlank() ? "Chưa cập nhật" : value);
        valueLabel.getStyleClass().add("settings-value");
        HBox line = new HBox(12, keyLabel, valueLabel);
        line.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(keyLabel, Priority.ALWAYS);
        return line;
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

    private static SVGPath svgIcon(String name, double size) {
        SVGPath path = new SVGPath();
        path.getStyleClass().add("svg-icon");
        path.setContent(switch (name) {
            case "chat" -> "M4 5 H20 V16 H8 L4 20 Z";
            case "tasks" -> "M9 6 H20 M9 12 H20 M9 18 H20 M4 6 H5 M4 12 H5 M4 18 H5";
            case "bell" -> "M12 22 C13.1 22 14 21.1 14 20 H10 C10 21.1 10.9 22 12 22 M18 16 V11 C18 8 16.2 5.7 13.5 5.1 V4 C13.5 3.2 12.8 2.5 12 2.5 C11.2 2.5 10.5 3.2 10.5 4 V5.1 C7.8 5.7 6 8 6 11 V16 L4 18 H20 Z";
            case "calendar" -> "M7 2 V6 M17 2 V6 M4 9 H20 M5 4 H19 C20 4 21 5 21 6 V20 C21 21 20 22 19 22 H5 C4 22 3 21 3 20 V6 C3 5 4 4 5 4 Z";
            default -> "M12 2 L22 12 L12 22 L2 12 Z";
        });
        path.setScaleX(size / 24.0);
        path.setScaleY(size / 24.0);
        return path;
    }
}
