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
    final LocalDateTime lastSeenAt;

    ChatUser(String username, String displayName, String role, String position) {
        this(username, displayName, role, position, null);
    }

    ChatUser(String username, String displayName, String role, String position, LocalDateTime lastSeenAt) {
        this.username = username;
        this.displayName = displayName == null || displayName.isBlank() ? username : displayName;
        this.role = role;
        this.position = position;
        this.lastSeenAt = lastSeenAt;
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
    String messageType;
    String metadataJson;
    String workflowStatus;
    Long replyToId;
    String replyPreview;
    boolean edited;
    boolean recalled;
    boolean pinned;
    String reactionSummary;
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

final class TaskTarget {
    final String employeeId;
    final String displayName;
    final String username;

    TaskTarget(String employeeId, String displayName, String username) {
        this.employeeId = employeeId;
        this.displayName = displayName == null || displayName.isBlank() ? employeeId : displayName;
        this.username = username;
    }

    @Override
    public String toString() {
        return employeeId + " - " + displayName;
    }
}

final class ChatTask {
    long id;
    long conversationId;
    String title;
    String description;
    String assigneeUsername;
    String assigneeName;
    String createdBy;
    String status;
    String priority;
    LocalDateTime deadline;
    int kpiPoints;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

final class MessageSearchCriteria {
    String keyword = "";
    String senderUsername = "";
    LocalDateTime from;
    LocalDateTime to;
    boolean onlyFiles;
    boolean onlyPinned;
    boolean onlyMentions;
    boolean onlyTasks;
    boolean includeArchive;
}

final class ScheduledMessage {
    long id;
    long conversationId;
    String conversationTitle;
    String senderUsername;
    String body;
    String status;
    LocalDateTime scheduledAt;
    LocalDateTime sentAt;
    LocalDateTime createdAt;
}

final class ChatReminder {
    long id;
    Long conversationId;
    String conversationTitle;
    String username;
    String title;
    String body;
    String status;
    LocalDateTime remindAt;
    LocalDateTime sentAt;
    LocalDateTime createdAt;
}

final class ReactionDetail {
    String emoji;
    String username;
    String displayName;
    LocalDateTime createdAt;
}

final class MentionItem {
    long messageId;
    long conversationId;
    String conversationTitle;
    String senderUsername;
    String senderName;
    String body;
    LocalDateTime createdAt;
}

final class PollOption {
    long pollId;
    long optionId;
    String optionText;
    int voteCount;
    boolean selectedByMe;
}
