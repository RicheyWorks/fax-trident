package com.xai.trident.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * Maps upload-related exceptions to HTTP responses across every controller.
 *
 * <ul>
 *   <li>{@link InvalidUploadException} → {@code 400 Bad Request} with the
 *       caller-visible reason ("Upload is empty", "bad magic bytes", etc.).</li>
 *   <li>{@link UploadNotFoundException} → {@code 404 Not Found} with a
 *       deliberately generic message. Both "never existed" and "traversal
 *       attempt" map here; differentiating would leak the existence of
 *       valid upload IDs to anyone who can probe the endpoint.</li>
 *   <li>{@link MaxUploadSizeExceededException} → {@code 413 Payload Too
 *       Large}. Spring throws this when a multipart request exceeds the
 *       servlet container's {@code max-file-size}; without an explicit
 *       handler the default 500 page hides what's going on.</li>
 * </ul>
 */
@RestControllerAdvice
public class UploadExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(UploadExceptionHandler.class);

    @ExceptionHandler(InvalidUploadException.class)
    public ResponseEntity<Map<String, String>> handleInvalidUpload(InvalidUploadException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UploadNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUploadNotFound(UploadNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", "Unknown upload"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(MaxUploadSizeExceededException e) {
        logger.warn("Upload rejected — too large: {}", e.getMessage());
        return ResponseEntity.status(413).body(Map.of("error", "Upload too large"));
    }
}
