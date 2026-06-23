package chatapp;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

final class MessageBubbleFactory {
    interface Callbacks {
        Node avatarNode(String displayName, String styleClass, String username);
        void applyIcon(Button button, String iconName);
        void animateMessageIfNew(Node node, long messageId);
        void showReactionDetails(ChatMessage msg);
        void reply(ChatMessage msg);
        void edit(ChatMessage msg);
        void recall(ChatMessage msg);
        void pin(ChatMessage msg);
        void forward(ChatMessage msg);
        void react(ChatMessage msg, String emoji);
        void removeReaction(ChatMessage msg);
        void createTask(ChatMessage msg);
        void loadPollOptions(ChatMessage msg, Consumer<List<PollOption>> onSuccess, Consumer<Exception> onError);
        void castPollVote(ChatMessage msg, PollOption option);
        void showToast(String message);
        void openAttachment(Attachment attachment);
        void playAudioAttachment(Attachment attachment, Button playButton);
        void openSalaryCard(ChatMessage msg);
        void decideWorkflow(ChatMessage msg, boolean approved);
    }

    private final CurrentUser currentUser;
    private final UserSettings userSettings;
    private final DateTimeFormatter messageTime;
    private final Callbacks callbacks;

    MessageBubbleFactory(CurrentUser currentUser, UserSettings userSettings, DateTimeFormatter messageTime, Callbacks callbacks) {
        this.currentUser = currentUser;
        this.userSettings = userSettings;
        this.messageTime = messageTime;
        this.callbacks = callbacks;
    }

    Node create(ChatMessage msg) {
        boolean mine = currentUser.username.equals(msg.senderUsername);
        VBox bubble = new VBox(7);
        bubble.getStyleClass().add(mine ? "bubble-mine" : "bubble-other");
        bubble.setMaxWidth(640);

        Label name = new Label(mine ? "Bạn" : msg.senderName);
        name.getStyleClass().add("message-sender");
        Label time = new Label(msg.createdAt.format(messageTime) + (msg.edited ? " - đã sửa" : ""));
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
            addMessageBody(msg, bubble);
            for (Attachment attachment : msg.attachments) {
                bubble.getChildren().add(attachmentNode(attachment));
            }
        }

        if (msg.reactionSummary != null && !msg.reactionSummary.isBlank()) {
            Label reactions = new Label(msg.reactionSummary);
            reactions.getStyleClass().add("reaction-summary");
            reactions.setOnMouseClicked(e -> callbacks.showReactionDetails(msg));
            bubble.getChildren().add(reactions);
        }

        if (mine && msg.seenCount > 0) {
            Label seen = new Label("Đã xem: " + msg.seenCount);
            seen.getStyleClass().add("seen-label");
            bubble.getChildren().add(seen);
        }

        ContextMenu menu = messageMenu(msg, mine);
        bubble.setOnContextMenuRequested(e -> menu.show(bubble, e.getScreenX(), e.getScreenY()));
        Button more = iconButton("⋯", "Tùy chọn tin nhắn");
        callbacks.applyIcon(more, "menu");
        more.getStyleClass().add("message-more-button");
        more.setOnAction(e -> menu.show(more, javafx.geometry.Side.BOTTOM, 0, 4));

        HBox wrapper = new HBox(10);
        wrapper.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        if (!mine) {
            wrapper.getChildren().add(callbacks.avatarNode(msg.senderName, "message-avatar", msg.senderUsername));
            wrapper.getChildren().add(bubble);
            wrapper.getChildren().add(more);
        } else {
            wrapper.getChildren().add(more);
            wrapper.getChildren().add(bubble);
            wrapper.getChildren().add(callbacks.avatarNode(currentUser.displayName, "message-avatar", currentUser.username));
        }
        callbacks.animateMessageIfNew(wrapper, msg.id);
        return wrapper;
    }

    private void addMessageBody(ChatMessage msg, VBox bubble) {
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
    }

    private ContextMenu messageMenu(ChatMessage msg, boolean mine) {
        ContextMenu menu = new ContextMenu();
        MenuItem reply = new MenuItem("Trả lời");
        reply.setOnAction(e -> callbacks.reply(msg));
        MenuItem edit = new MenuItem("Sửa tin");
        edit.setDisable(!mine || msg.recalled);
        edit.setOnAction(e -> callbacks.edit(msg));
        MenuItem recall = new MenuItem("Thu hồi");
        recall.setDisable(!mine || msg.recalled);
        recall.setOnAction(e -> callbacks.recall(msg));
        MenuItem pin = new MenuItem(msg.pinned ? "Bỏ ghim tin" : "Ghim tin");
        pin.setOnAction(e -> callbacks.pin(msg));
        MenuItem forward = new MenuItem("Chuyển tiếp");
        forward.setDisable(msg.recalled);
        forward.setOnAction(e -> callbacks.forward(msg));

        Menu react = new Menu("Cảm xúc");
        for (String emoji : List.of("👍", "❤️", "😂", "😮", "😢", "✅")) {
            MenuItem item = new MenuItem(emoji);
            item.setOnAction(e -> callbacks.react(msg, emoji));
            react.getItems().add(item);
        }
        MenuItem removeReaction = new MenuItem("Bỏ cảm xúc");
        removeReaction.setOnAction(e -> callbacks.removeReaction(msg));
        react.getItems().add(new SeparatorMenuItem());
        react.getItems().add(removeReaction);

        MenuItem reactionDetails = new MenuItem("Xem ai đã thả cảm xúc");
        reactionDetails.setOnAction(e -> callbacks.showReactionDetails(msg));
        MenuItem task = new MenuItem("Giao thành công việc (KPI Task)");
        task.setDisable(msg.recalled);
        task.setOnAction(e -> callbacks.createTask(msg));
        menu.getItems().addAll(reply, edit, recall, pin, forward, react, reactionDetails, new SeparatorMenuItem(), task);
        return menu;
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
        Label title = new Label(parts.length == 0 ? "Bình chọn" : parts[0].trim());
        title.getStyleClass().add("vote-title");
        VBox box = new VBox(8, title);
        box.getStyleClass().add("vote-card");
        callbacks.loadPollOptions(msg, options -> {
            box.getChildren().setAll(title);
            for (PollOption pollOption : options) {
                Button option = new Button(pollOption.optionText + "  (" + pollOption.voteCount + ")");
                option.getStyleClass().add("vote-option");
                if (pollOption.selectedByMe) {
                    option.getStyleClass().add("vote-option-selected");
                }
                option.setMaxWidth(Double.MAX_VALUE);
                option.setOnAction(e -> callbacks.castPollVote(msg, pollOption));
                box.getChildren().add(option);
            }
            if (options.isEmpty()) {
                box.getChildren().add(label("Chưa có lựa chọn.", "settings-note"));
            }
        }, e -> box.getChildren().add(label("Không tải được kết quả vote.", "settings-note")));
        return box;
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
                callbacks.showToast("Đã chọn tạm thời: " + optionText);
            });
            box.getChildren().add(option);
        }
        if (box.getChildren().size() == 1) {
            box.getChildren().add(label("Chưa có lựa chọn.", "settings-note"));
        }
        return box;
    }

    private Node attachmentNode(Attachment attachment) {
        File file = new File(attachment.sharedPath);
        boolean skipPreview = userSettings.bandwidthSaving && attachment.fileSize > 1024L * 1024L;
        if (!attachment.encrypted && "IMAGE".equals(attachment.fileType) && file.isFile() && !skipPreview) {
            VBox box = new VBox(6);
            ImageView view = new ImageView(new Image(file.toURI().toString(), 360, 240, true, true, true));
            view.getStyleClass().add("image-preview");
            view.setOnMouseClicked(e -> callbacks.openAttachment(attachment));
            Label caption = new Label(attachment.originalName);
            caption.getStyleClass().add("attachment-caption");
            box.getChildren().addAll(view, caption);
            return box;
        }
        if ("AUDIO".equals(attachment.fileType) && file.isFile()) {
            return audioAttachmentNode(attachment, file);
        }
        Label icon = new Label("VIDEO".equals(attachment.fileType) ? "Video" : "File");
        icon.getStyleClass().add("file-icon");
        Label name = new Label(attachment.originalName + (attachment.encrypted ? "  • mã hóa" : ""));
        name.getStyleClass().add("file-name");
        Label size = new Label(formatSize(attachment.fileSize));
        size.getStyleClass().add("file-size");
        VBox info = new VBox(2, name, size);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button open = new Button("Mở");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> callbacks.openAttachment(attachment));
        HBox card = new HBox(10, icon, info, open);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("file-card");
        return card;
    }

    private Node audioAttachmentNode(Attachment attachment, File file) {
        Label icon = new Label("Audio");
        icon.getStyleClass().add("file-icon");
        Label name = new Label(attachment.originalName + (attachment.encrypted ? "  • mã hóa" : ""));
        name.getStyleClass().add("file-name");
        String duration = attachment.encrypted ? "--:--" : audioDurationText(file);
        Label meta = new Label(formatSize(attachment.fileSize) + " • " + duration + " • Phiên âm: đang chờ");
        meta.getStyleClass().add("file-size");
        VBox info = new VBox(2, name, meta);
        HBox.setHgrow(info, Priority.ALWAYS);
        Button play = new Button("Phát");
        play.getStyleClass().add("file-open-button");
        play.setOnAction(e -> callbacks.playAudioAttachment(attachment, play));
        Button open = new Button("Mở");
        open.getStyleClass().add("file-open-button");
        open.setOnAction(e -> callbacks.openAttachment(attachment));
        HBox card = new HBox(10, icon, info, play, open);
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
        open.setOnAction(e -> callbacks.openSalaryCard(msg));
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
            approve.setOnAction(e -> callbacks.decideWorkflow(msg, true));
            Button reject = new Button("Từ chối");
            reject.getStyleClass().add("file-open-button");
            reject.setOnAction(e -> callbacks.decideWorkflow(msg, false));
            actions.getChildren().addAll(approve, reject);
        }
        VBox card = new VBox(8, title, body, status, actions);
        card.getStyleClass().add("vote-card");
        return card;
    }

    private Button iconButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("composer-icon-button");
        Tooltip.install(button, new Tooltip(tooltip));
        return button;
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
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

    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        double kb = size / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        return String.format("%.1f MB", mb);
    }
}

