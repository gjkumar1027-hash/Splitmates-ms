package com.splitmates.expense;

import com.splitmates.common.ApiException;
import com.splitmates.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

/**
 * Private receipt storage on local disk. Files are never served statically — only through an
 * authorised API call (NFR-08). Swap for S3 + pre-signed URLs in production.
 */
@Component
public class ReceiptStorage {
    private static final Map<String, String> TYPES = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/heic", ".heic", "image/heif", ".heic");
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Path root;

    public ReceiptStorage(AppProperties props) {
        this.root = Paths.get(props.uploads().dir()).toAbsolutePath().normalize();
    }

    public String store(Long groupId, MultipartFile file) {
        String ext = TYPES.get(file.getContentType() == null ? "" : file.getContentType().toLowerCase());
        if (ext == null) throw ApiException.badRequest("Receipt must be a JPG, PNG or HEIC image");
        if (file.getSize() > MAX_BYTES) throw ApiException.badRequest("Receipt must be 5 MB or smaller");
        String rel = groupId + "/" + UUID.randomUUID() + ext;
        Path target = root.resolve(rel).normalize();
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not store receipt", e);
        }
        return rel;
    }

    public Path resolve(String rel) {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || !Files.exists(p)) throw ApiException.notFound("Receipt");
        return p;
    }

    public void delete(String rel) {
        if (rel == null) return;
        try {
            Path p = root.resolve(rel).normalize();
            if (p.startsWith(root)) Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
