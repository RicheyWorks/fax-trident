package com.xai.trident.upload;

/**
 * Thrown when a multipart upload is rejected at validation time — empty,
 * oversized, wrong content type, or the bytes don't look like a PDF. Mapped
 * to HTTP 400 by the controller layer.
 */
public class InvalidUploadException extends RuntimeException {
    public InvalidUploadException(String message) {
        super(message);
    }
}
