package chatapp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class FileStorageService {
    private static final Set<String> BLOCKED = Set.of("exe", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "msi");
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Set<String> VIDEOS = Set.of("mp4", "mov", "avi", "mkv", "webm", "wmv");

    private final AppConfig config;

    FileStorageService(AppConfig config) {
        this.config = config;
    }

    StoredFile store(File source, CurrentUser user, long conversationId) throws Exception {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("File khong hop le.");
        }
        String ext = extension(source.getName());
        if (BLOCKED.contains(ext)) {
            throw new IllegalArgumentException("File ." + ext + " bi chan vi khong an toan.");
        }
        String type = fileType(ext);
        long size = source.length();
        int limitMb = "IMAGE".equals(type) ? config.maxImageMb : "VIDEO".equals(type) ? config.maxVideoMb : config.maxFileMb;
        long limitBytes = limitMb * 1024L * 1024L;
        if (size > limitBytes) {
            throw new IllegalArgumentException("File vuot qua gioi han " + limitMb + "MB.");
        }

        LocalDate now = LocalDate.now();
        Path dir = config.filesRoot
                .resolve(clean(user.companyOwner))
                .resolve(String.valueOf(conversationId))
                .resolve(String.valueOf(now.getYear()))
                .resolve(String.format("%02d", now.getMonthValue()));
        Files.createDirectories(dir);

        String storedName = UUID.randomUUID() + (ext.isBlank() ? "" : "." + ext);
        Path target = dir.resolve(storedName);
        Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        return new StoredFile(source.getName(), storedName, type, Files.probeContentType(target), size, target.toAbsolutePath().toString());
    }

    boolean deleteQuietly(String path) {
        try {
            return path != null && Files.deleteIfExists(Path.of(path));
        } catch (Exception ignored) {
            return false;
        }
    }

    static String extension(String name) {
        int idx = name == null ? -1 : name.lastIndexOf('.');
        return idx < 0 ? "" : name.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    static String fileType(String ext) {
        if (IMAGES.contains(ext)) return "IMAGE";
        if (VIDEOS.contains(ext)) return "VIDEO";
        return "FILE";
    }

    private static String clean(String value) {
        return value == null ? "company" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static final class StoredFile {
        final String originalName;
        final String storedName;
        final String fileType;
        final String mimeType;
        final long fileSize;
        final String sharedPath;

        StoredFile(String originalName, String storedName, String fileType, String mimeType, long fileSize, String sharedPath) {
            this.originalName = originalName;
            this.storedName = storedName;
            this.fileType = fileType;
            this.mimeType = mimeType;
            this.fileSize = fileSize;
            this.sharedPath = sharedPath;
        }
    }
}
