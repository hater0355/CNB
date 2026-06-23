package chatapp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class SidebarCells {
    private SidebarCells() {
    }

    static final class ConversationCell extends ListCell<Conversation> {
        private static final DateTimeFormatter CONVERSATION_TIME = DateTimeFormatter.ofPattern("dd/MM");

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

    static final class UserCell extends ListCell<ChatUser> {
        private final Function<ChatUser, String> presenceFormatter;

        UserCell(Function<ChatUser, String> presenceFormatter) {
            this.presenceFormatter = presenceFormatter;
        }

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
            Label detail = new Label(item.username + roleSuffix(item) + " · " + presenceFormatter.apply(item));
            detail.getStyleClass().add("conversation-last");
            HBox row = new HBox(10, avatar, new VBox(4, name, detail));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("dialog-user-row");
            setGraphic(row);
        }
    }

    private static Label avatar(String text, String styleClass) {
        Label avatar = new Label(initials(text));
        avatar.getStyleClass().add(styleClass);
        return avatar;
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
        return (parts[0].substring(0, 1) + parts[parts.length - 1].substring(0, 1)).toUpperCase();
    }

    private static String roleSuffix(ChatUser user) {
        if (user.position == null || user.position.isBlank()) {
            return "";
        }
        return " (" + user.position + ")";
    }
}
