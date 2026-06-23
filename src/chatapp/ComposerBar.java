package chatapp;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

final class ComposerBar {
    interface Callbacks {
        void applyIcon(Button button, String iconName);
        void showPlusMenu(Button owner);
        void clearFiles();
        void showEmojiMenu(Button owner);
        void toggleVoiceRecording();
        void sendCurrentMessage();
        void publishTypingIfNeeded();
        void updateMentionSuggestions();
    }

    private final Label replyLabel = new Label();
    private final Label attachmentLabel = new Label();
    private final TextArea input = new TextArea();
    private final Button voiceButton;
    private final StackPane root;

    ComposerBar(Callbacks callbacks) {
        replyLabel.getStyleClass().add("reply-composer");
        replyLabel.setVisible(false);
        replyLabel.setManaged(false);

        attachmentLabel.getStyleClass().add("attachment-label");
        attachmentLabel.setVisible(false);
        attachmentLabel.setManaged(false);

        input.setPromptText("Nhập tin nhắn, Ctrl+Enter để gửi");
        input.getStyleClass().add("composer-input");
        input.setPrefRowCount(2);
        input.setWrapText(true);
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER && e.isControlDown()) {
                callbacks.sendCurrentMessage();
                e.consume();
            }
        });
        input.textProperty().addListener((obs, old, value) -> callbacks.publishTypingIfNeeded());
        input.addEventHandler(KeyEvent.KEY_RELEASED, e -> callbacks.updateMentionSuggestions());

        Button plus = composerIconButton("+", "Mở thêm tùy chọn");
        callbacks.applyIcon(plus, "plus");
        plus.setOnAction(e -> callbacks.showPlusMenu(plus));

        Button clearFiles = composerIconButton("🗑", "Xóa file đang chọn");
        callbacks.applyIcon(clearFiles, "trash");
        clearFiles.setOnAction(e -> callbacks.clearFiles());

        Button icons = composerIconButton("😊", "Chèn icon cảm xúc");
        callbacks.applyIcon(icons, "smile");
        icons.setOnAction(e -> callbacks.showEmojiMenu(icons));

        voiceButton = composerIconButton("🎙", "Ghi âm voice message");
        callbacks.applyIcon(voiceButton, "mic");
        voiceButton.setOnAction(e -> callbacks.toggleVoiceRecording());

        Button send = composerIconButton("➤", "Gửi tin nhắn");
        callbacks.applyIcon(send, "send");
        send.getStyleClass().add("send-icon-button");
        send.setOnAction(e -> callbacks.sendCurrentMessage());

        HBox inputRow = new HBox(10, plus, clearFiles, icons, voiceButton, input, send);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);

        VBox composer = new VBox(8, replyLabel, attachmentLabel, inputRow);
        composer.getStyleClass().add("composer");
        root = new StackPane(composer);
        root.getStyleClass().add("composer-shell");
    }

    Node node() {
        return root;
    }

    TextArea input() {
        return input;
    }

    Label replyLabel() {
        return replyLabel;
    }

    Label attachmentLabel() {
        return attachmentLabel;
    }

    Button voiceButton() {
        return voiceButton;
    }

    private Button composerIconButton(String icon, String tooltip) {
        Button button = new Button(icon);
        button.getStyleClass().add("composer-icon-button");
        Tooltip.install(button, new Tooltip(tooltip));
        return button;
    }
}
