package com.xai.trident.upload;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Server-controlled storage for fax uploads. Before this service existed,
 * the API let callers supply an arbitrary {@code filePath} on disk and the
 * server dutifully opened it — see audit finding 1.5. The cure is to
 * (a) take the bytes from the caller as a multipart upload, (b) store them
 * under a path the server picks, and (c) hand back an opaque ID that
 * downstream endpoints can resolve safely.
 *
 * <p><b>Trust boundary.</b> Everything outside this service treats upload
 * IDs as untrusted strings. Internally we canonicalize, length-bound, and
 * pattern-match every ID against a UUID grammar before joining it with the
 * upload directory; resolution then re-canonicalizes the file path and
 * verifies it is still inside {@link #uploadsDir}. A traversal attempt
 * (e.g. {@code "../etc/passwd"}) fails at the UUID-pattern check, and a
 * legitimate-looking ID that somehow lands outside the directory (e.g. via
 * a symlink an operator inadvertently put inside the upload dir) fails at
 * the realpath check.
 *
 * <p><b>Content validation.</b> Right now we only accept PDFs, and we check
 * for the {@code %PDF-} magic bytes rather than trusting the client's
 * {@code Content-Type}. If we ever need to accept TIFF or other fax-able
 * formats the magic-byte table is the single place to extend.
 */
@Service
public class FaxUploadService {

    private static final Logger logger = LoggerFactory.getLogger(FaxUploadService.class);

    /**
     * Upload IDs are UUIDs only — no slashes, dots, or other path-meaningful
     * characters. The pattern-match check on resolve is the first line of
     * defense against path traversal; the realpath check below is the
     * second.
     */
    private static final Pattern UPLOAD_ID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** PDF magic bytes per ISO 32000-1 §7.5.2. */
    private static final byte[] PDF_MAGIC = new byte[] { '%', 'P', 'D', 'F', '-' };

    /** Maximum upload size in bytes — 25 MiB. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024L * 1024L;

    /**
     * Root directory for uploaded fax files. Defaults to {@code ./uploads/}
     * relative to the JVM's working directory; operators should set
     * {@code app.upload.dir} to an absolute path in production so the
     * location is unambiguous.
     */
    @Value("${app.upload.dir:./uploads/}")
    private String uploadDirConfig;

    private Path uploadsDir;

    @PostConstruct
    public void init() throws IOException {
        Path raw = Paths.get(uploadDirConfig);
        Files.createDirectories(raw);
        // toRealPath() resolves symlinks and gives us a canonical path
        // against which every resolved upload path is compared. Storing
        // the canonical form once at startup means the per-request check
        // is a cheap string-prefix comparison.
        this.uploadsDir = raw.toRealPath();
        logger.info("FaxUploadService initialized, upload dir: {}", this.uploadsDir);
    }

    /**
     * Stores the multipart body under a fresh upload ID and returns the ID.
     * The bytes are validated for size and PDF magic before they hit disk —
     * a hostile non-PDF body never lives in the upload dir at all.
     */
    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new InvalidUploadException("Upload is empty");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new InvalidUploadException(
                    "Upload exceeds max size of " + MAX_UPLOAD_BYTES + " bytes");
        }

        // Peek at the first bytes BEFORE writing to disk. file.getInputStream()
        // is allowed to be consumed once; we buffer the first few bytes for
        // the magic check then re-open the stream when we actually copy.
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(PDF_MAGIC.length);
            if (head.length < PDF_MAGIC.length) {
                throw new InvalidUploadException("Upload is shorter than PDF header");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (head[i] != PDF_MAGIC[i]) {
                    throw new InvalidUploadException(
                            "Upload does not appear to be a PDF (bad magic bytes)");
                }
            }
        }

        String uploadId = UUID.randomUUID().toString();
        Path target = uploadsDir.resolve(uploadId + ".pdf");
        // Defense in depth: the freshly-generated UUID can't collide and
        // can't traverse, but assert the realpath check anyway so future
        // refactors of the ID format don't open a regression.
        Path canonical = target.normalize();
        if (!canonical.startsWith(uploadsDir)) {
            throw new IllegalStateException(
                    "BUG: generated upload target escapes uploads dir: " + canonical);
        }

        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("Stored upload {} ({} bytes)", uploadId, file.getSize());
        return uploadId;
    }

    /**
     * Resolves a caller-supplied upload ID to a concrete on-disk path. Three
     * checks must all pass: the ID matches the UUID grammar, the resolved
     * path exists, and the canonical resolved path is still inside the
     * upload directory.
     *
     * @throws UploadNotFoundException for any failure — the same exception
     *         is used for "no such ID" and "traversal attempt" so the caller
     *         cannot probe for the existence of arbitrary IDs.
     */
    public Path resolve(String uploadId) {
        if (uploadId == null || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new UploadNotFoundException("Unknown upload");
        }
        Path candidate = uploadsDir.resolve(uploadId + ".pdf");
        if (!Files.isRegularFile(candidate)) {
            throw new UploadNotFoundException("Unknown upload");
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new UploadNotFoundException("Unknown upload");
        }
        if (!real.startsWith(uploadsDir)) {
            // Either a symlink inside the upload dir pointed outside, or
            // some other shenanigan. Treat as not-found to avoid leaking
            // the existence of the file.
            logger.warn("Upload resolution escaped uploads dir; refusing: {}", real);
            throw new UploadNotFoundException("Unknown upload");
        }
        return real;
    }

    /**
     * Convenience overload returning the resolved path as a String, which
     * is what the legacy {@code FaxEngineService.sendFax(String, String)}
     * signature wants. The internal {@link #resolve(String)} guarantees the
     * returned path is safe.
     */
    public String resolveToString(String uploadId) {
        return resolve(uploadId).toString();
    }
}
