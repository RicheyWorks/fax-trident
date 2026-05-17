package com.xai.trident.util;

/**
 * Tiny helper that escapes CR / LF (and a few related control characters) in
 * strings before they are passed to a logger. The motivating attack is log
 * injection: a user-controlled field like a fax number or filename that
 * contains a literal {@code "\n[ERROR] forged-line"} can otherwise create a
 * fake log entry that confuses SIEM parsing or hides real activity.
 *
 * <p>This is a write-side sanitizer only — it does NOT change what is stored,
 * displayed back to the user, or otherwise processed. Apply at the point of
 * logging.
 *
 * <p>Usage:
 * <pre>{@code
 * logger.info("User '{}' sent fax to {}", LogSanitizer.sanitize(username),
 *                                         LogSanitizer.sanitize(faxNumber));
 * }</pre>
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    /**
     * Replaces {@code \r}, {@code \n}, and {@code \t} with their escaped
     * literal forms ({@code "\\r"}, {@code "\\n"}, {@code "\\t"}) so they
     * cannot terminate a log line or break tab-delimited log formats.
     * {@code null} in returns {@code null} out so callers can pass values
     * straight from getters that may return null.
     */
    public static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        // The common case is "no control chars" — short-circuit to avoid the
        // allocation cost of replace() chains on every log call.
        int len = input.length();
        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                return doSanitize(input);
            }
        }
        return input;
    }

    private static String doSanitize(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\r': sb.append("\\r"); break;
                case '\n': sb.append("\\n"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
