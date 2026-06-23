package chatapp;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;

final class FileStorageService {
    private static final Set<String> BLOCKED = Set.of("exe", "bat", "cmd", "com", "scr", "ps1", "vbs", "js", "jar", "msi");
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Set<String> VIDEOS = Set.of("mp4", "mov", "avi", "mkv", "webm", "wmv");
    private static final Set<String> AUDIOS = Set.of("wav", "aiff", "aif", "au");
    private static final SecureRandom RANDOM = new SecureRandom();

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
        Path plainTarget = target;
        if (config.fileEncryptionEnabled) {
            if (config.fileEncryptionKey == null || config.fileEncryptionKey.isBlank()) {
                throw new IllegalStateException("Chưa cấu hình chat.file.encryption.key nên không thể mã hóa file.");
            }
            plainTarget = Files.createTempFile(dir, "plain-", ".tmp");
        }
        if (!compressImageIfUseful(source, plainTarget, ext, type, size)) {
            Files.copy(source.toPath(), plainTarget, StandardCopyOption.REPLACE_EXISTING);
        }
        String mimeType = Files.probeContentType(plainTarget);
        boolean encrypted = false;
        String ivBase64 = null;
        if (config.fileEncryptionEnabled) {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            encryptFile(plainTarget, target, iv);
            Files.deleteIfExists(plainTarget);
            encrypted = true;
            ivBase64 = Base64.getEncoder().encodeToString(iv);
        }
        long finalSize = Files.size(target);
        return new StoredFile(source.getName(), storedName, type, mimeType, finalSize, target.toAbsolutePath().toString(), encrypted, ivBase64);
    }

    File openableFile(Attachment attachment) throws Exception {
        if (attachment == null || attachment.sharedPath == null || attachment.sharedPath.isBlank()) {
            throw new IllegalArgumentException("File không hợp lệ.");
        }
        Path source = Path.of(attachment.sharedPath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("File không tồn tại: " + source.toAbsolutePath());
        }
        if (!attachment.encrypted) {
            return source.toFile();
        }
        if (attachment.cryptoIv == null || attachment.cryptoIv.isBlank()) {
            throw new IllegalStateException("File đã mã hóa nhưng thiếu IV.");
        }
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "CHAT_NOI_BO", "opened");
        Files.createDirectories(dir);
        String name = clean(attachment.originalName == null ? source.getFileName().toString() : attachment.originalName);
        Path target = dir.resolve(UUID.randomUUID() + "-" + name);
        decryptFile(source, target, Base64.getDecoder().decode(attachment.cryptoIv));
        target.toFile().deleteOnExit();
        return target.toFile();
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

    private void encryptFile(Path source, Path target, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = new CipherOutputStream(Files.newOutputStream(target), cipher)) {
            in.transferTo(out);
        }
    }

    private void decryptFile(Path source, Path target, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
        try (InputStream in = new CipherInputStream(Files.newInputStream(source), cipher);
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    private SecretKeySpec encryptionKey() throws Exception {
        String key = config.fileEncryptionKey == null ? "" : config.fileEncryptionKey.trim();
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException ignored) {
            raw = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        }
        if (raw.length != 32) {
            raw = MessageDigest.getInstance("SHA-256").digest(raw);
        }
        return new SecretKeySpec(raw, "AES");
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
        final boolean encrypted;
        final String cryptoIv;

        StoredFile(String originalName, String storedName, String fileType, String mimeType, long fileSize, String sharedPath, boolean encrypted, String cryptoIv) {
            this.originalName = originalName;
            this.storedName = storedName;
            this.fileType = fileType;
            this.mimeType = mimeType;
            this.fileSize = fileSize;
            this.sharedPath = sharedPath;
            this.encrypted = encrypted;
            this.cryptoIv = cryptoIv;
        }
    }
}
