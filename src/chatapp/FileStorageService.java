package chatapp;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;

final class FileStorageService {
    private static final Set<String> BLOCKED = Set.of("exe", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "msi");
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Set<String> VIDEOS = Set.of("mp4", "mov", "avi", "mkv", "webm", "wmv");
    private static final Set<String> AUDIOS = Set.of("wav", "aiff", "aif", "au");

    private final AppConfig config;

    FileStorageService(AppConfig config) {
        this.config = config;
    }

    StoredFile store(File source, CurrentUser user, long conversationId) throws Exception {
        if (source == null || !source.isFile()) {
            throw new IllegalArgumentException("File không hợp lệ.");
        }
        String ext = extension(source.getName());
        if (BLOCKED.contains(ext)) {
            throw new IllegalArgumentException("File ." + ext + " bị chặn vì không an toàn.");
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
        if (!compressImageIfUseful(source, target, ext, type, size)) {
            Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        long finalSize = Files.size(target);
        return new StoredFile(source.getName(), storedName, type, Files.probeContentType(target), finalSize, target.toAbsolutePath().toString());
    }

    private boolean compressImageIfUseful(File source, Path target, String ext, String type, long size) {
        if (!"IMAGE".equals(type) || size < 2L * 1024L * 1024L || !Set.of("jpg", "jpeg", "png", "bmp").contains(ext)) {
            return false;
        }
        try {
            BufferedImage original = ImageIO.read(source);
            if (original == null) return false;
            int max = Math.max(original.getWidth(), original.getHeight());
            if (max <= 1600) return false;
            double scale = 1600.0 / max;
            int width = Math.max(1, (int) Math.round(original.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(original.getHeight() * scale));
            BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(original, 0, 0, width, height, null);
            g.dispose();
            String format = "jpeg".equals(ext) ? "jpg" : ext;
            return ImageIO.write(resized, format, target.toFile());
        } catch (Exception e) {
            AppLog.warn("Bỏ qua bước nén ảnh.", e);
            return false;
        }
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
        if (AUDIOS.contains(ext)) return "AUDIO";
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
