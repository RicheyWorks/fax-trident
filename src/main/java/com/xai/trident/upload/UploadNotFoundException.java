package com.xai.trident.upload;

/**
 * Thrown when a caller references an upload ID that does not resolve to a
 * stored file in the upload directory — either it never existed, the file
 * was cleaned up, or the supplied ID was structured in a way that would
 * have escaped the upload directory (path traversal attempt). Mapped to
 * HTTP 404 by the controller layer.
 *
 * <p>The same exception type is intentionally used for "never existed" and
 * "traversal attempt" so that a probe cannot distinguish the two. Any other
 * response would leak information about valid upload IDs.
 */
public class UploadNotFoundException extends RuntimeException {
    public UploadNotFoundException(String message) {
        super(message);
    }
}
