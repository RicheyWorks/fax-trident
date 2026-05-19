package com.xai.trident.desktop.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin HTTP client for talking to the fax-trident server.
 *
 * <p>Before the JavaFX / Spring Boot split (ADR-0001) the desktop made
 * in-process calls into {@code FaxEngineService} and friends. After the
 * split, the desktop talks to the server through the same REST surface
 * any other client uses — documented in {@code README.md} under
 * "API surface."
 *
 * <p>Built on the JDK's {@link HttpClient} so the module avoids pulling
 * in OkHttp / Apache HttpComponents for ~5 endpoints. JSON is parsed via
 * Jackson (the same library the WS client already uses).
 *
 * <p>Token lifecycle: {@link #login(String, String)} stores the issued
 * JWT in an {@link AtomicReference}; subsequent requests attach it as
 * {@code Authorization: Bearer ...}. {@link #logout()} clears the
 * reference and notifies the server. Token is in-memory only — the ADR
 * defers on-disk / OS-keychain persistence as a follow-up.
 *
 * <p>Errors are surfaced as {@link IOException} with the HTTP status
 * baked into the message; callers don't have to learn a custom exception
 * hierarchy for the MVP.
 */
public final class FaxApiClient {

    private static final Logger logger = LoggerFactory.getLogger(FaxApiClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUrl;
    private final HttpClient http;
    private final ObjectMapper json;
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicReference<String> username = new AtomicReference<>();

    public FaxApiClient(String baseUrl, ObjectMapper json) {
        // Strip trailing slash so concatenation is predictable.
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.baseUrl = URI.create(trimmed);
        this.json = json;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        logger.info("FaxApiClient configured for base URL: {}", this.baseUrl);
    }

    /** True once {@link #login(String, String)} has succeeded and the token hasn't been cleared. */
    public boolean isAuthenticated() {
        return token.get() != null;
    }

    /** The username last passed to {@link #login(String, String)}, or null. */
    public String currentUsername() {
        return username.get();
    }

    /**
     * Exchange username/password for a JWT. On success, the token is
     * stored on this client and applied to subsequent calls. Throws
     * {@link IOException} on transport failure or non-200 response; the
     * 401 case carries "Invalid credentials" in the message so the UI
     * can show a clean "wrong password" message.
     */
    public void login(String user, String password) throws IOException {
        String body = json.writeValueAsString(Map.of("username", user, "password", password));
        HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("/api/auth/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 401) {
            throw new IOException("Invalid credentials");
        }
        if (resp.statusCode() == 429) {
            throw new IOException("Too many login attempts; try again in a minute.");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Login failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = json.readTree(resp.body());
        JsonNode tokenNode = node.get("token");
        if (tokenNode == null || tokenNode.asText().isBlank()) {
            throw new IOException("Login response missing 'token' field: " + resp.body());
        }
        this.token.set(tokenNode.asText());
        this.username.set(user);
        logger.info("Logged in as '{}'", user);
    }

    /**
     * Revoke the current token server-side and clear it locally. Idempotent —
     * safe to call when not logged in. Network failures are logged and
     * swallowed; the local state is cleared either way.
     */
    public void logout() {
        String t = token.getAndSet(null);
        username.set(null);
        if (t == null) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("/logout"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + t)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            send(req);
            logger.info("Logged out");
        } catch (IOException e) {
            logger.warn("Server-side logout failed (token cleared locally anyway): {}", e.getMessage());
        }
    }

    /**
     * Upload a local PDF and return the opaque {@code uploadId} the server
     * issued. The send-fax endpoint takes that ID — not the local path —
     * see audit finding 1.5 for why the old {@code filePath} parameter
     * is gone.
     */
    public String uploadPdf(Path pdf) throws IOException {
        requireAuth("uploadPdf");
        if (!Files.exists(pdf) || !Files.isRegularFile(pdf)) {
            throw new IOException("Upload source is not a regular file: " + pdf);
        }
        byte[] payload = Files.readAllBytes(pdf);
        String boundary = "----faxtrident-" + UUID.randomUUID();
        byte[] body = buildMultipart(boundary, "file", pdf.getFileName().toString(), "application/pdf", payload);

        HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("/api/fax/uploads"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Authorization", "Bearer " + token.get())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 413) {
            throw new IOException("Upload too large (server cap is 25 MiB)");
        }
        if (resp.statusCode() == 401) {
            throw new IOException("Not authorized (token may have expired); please log in again");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Upload failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = json.readTree(resp.body());
        JsonNode idNode = node.get("uploadId");
        if (idNode == null || idNode.asText().isBlank()) {
            throw new IOException("Upload response missing 'uploadId': " + resp.body());
        }
        return idNode.asText();
    }

    /**
     * Send a fax. Returns the server-assigned {@code faxId} (UUID-prefixed)
     * which the UI can correlate with subsequent {@code /fax-updates}
     * broadcasts.
     */
    public String sendFax(String faxNumber, String uploadId) throws IOException {
        requireAuth("sendFax");
        String body = json.writeValueAsString(Map.of(
                "faxNumber", faxNumber,
                "uploadId", uploadId));
        HttpRequest req = HttpRequest.newBuilder(baseUrl.resolve("/api/fax/send"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token.get())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(req);
        if (resp.statusCode() == 429) {
            throw new IOException("Rate limit exceeded (server allows 10 sends/min); try again shortly.");
        }
        if (resp.statusCode() == 401) {
            throw new IOException("Not authorized; please log in again");
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Send failed with HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode node = json.readTree(resp.body());
        JsonNode idNode = node.get("faxId");
        return idNode != null ? idNode.asText() : "";
    }

    /** Return the bearer token, or null if unauthenticated. Used by FaxUpdateClient to authenticate the WS handshake. */
    public String getToken() {
        return token.get();
    }

    private void requireAuth(String op) throws IOException {
        if (token.get() == null) {
            throw new IOException("Not authenticated; call login(...) before " + op);
        }
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted: " + req.uri(), ie);
        }
    }

    /**
     * Build a single-part {@code multipart/form-data} body. The fax-trident
     * upload endpoint takes exactly one file field named {@code file}; a
     * tiny purpose-built builder is plenty.
     *
     * <p>Field ordering inside the body matters less than the boundary
     * formatting — RFC 7578 requires {@code --<boundary>} before each
     * part and {@code --<boundary>--} as the closing delimiter, with
     * CRLF line endings throughout.
     */
    private static byte[] buildMultipart(String boundary, String fieldName, String filename,
                                         String contentType, byte[] payload) {
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "\r\n";
        String trailer = "\r\n--" + boundary + "--\r\n";
        byte[] hb = header.getBytes(StandardCharsets.UTF_8);
        byte[] tb = trailer.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[hb.length + payload.length + tb.length];
        System.arraycopy(hb, 0, out, 0, hb.length);
        System.arraycopy(payload, 0, out, hb.length, payload.length);
        System.arraycopy(tb, 0, out, hb.length + payload.length, tb.length);
        return out;
    }
}
