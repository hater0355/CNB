package chatapp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

final class CurrentUser {
    final String username;
    final String displayName;
    final String role;
    final String companyOwner;
    final String employeeId;
    final String department;
    final String position;

    CurrentUser(String username, String displayName, String role, String companyOwner, String employeeId, String department, String position) {
        this.username = username;
        this.displayName = displayName == null || displayName.isBlank() ? username : displayName;
        this.role = role;
        this.companyOwner = companyOwner;
        this.employeeId = employeeId;
        this.department = department;
        this.position = position;
    }

    boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    boolean canManageGroups() {
        return isAdmin() || Texts.normalize(position).contains("truong phong");
    }
}

final class ChatUser {
    final String username;
    final String displayName;
    final String role;
    final String position;

    ChatUser(String username, String displayName, String role, String position) {
        this.username = username;
        this.displayName = displayName == null || displayName.isBlank() ? username : displayName;
        this.role = role;
        this.position = position;
    }

    @Override
    public String toString() {
        return displayName + " (" + username + ")";
    }
}

final class Conversation {
    long id;
    String type;
    String title;
    String companyOwner;
    String createdBy;
    boolean pinned;
    int unreadCount;
    String lastMessage;
    LocalDateTime updatedAt;

    @Override
    public String toString() {
        String unread = unreadCount > 0 ? " (" + unreadCount + ")" : "";
        String pin = pinned ? "[Ghim] " : "";
        return pin + title + unread;
    }
}

final class ChatMessage {
    long id;
    long conversationId;
    String senderUsername;
    String senderName;
    String body;
    Long replyToId;
    String replyPreview;
    boolean edited;
    boolean recalled;
    boolean pinned;
    LocalDateTime createdAt;
    int seenCount;
    final List<Attachment> attachments = new ArrayList<>();
}

final class Attachment {
    long id;
    long messageId;
    String originalName;
    String fileType;
    String mimeType;
    long fileSize;
    String sharedPath;
}
