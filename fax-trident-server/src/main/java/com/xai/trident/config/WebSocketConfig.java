package com.xai.trident.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableWebSocket
// @EnableScheduling already declared on FaxTridentServer — duplicate here
// was redundant (audit 2.20). The @Scheduled session cleanup below still
// runs because Spring's scheduler is active app-wide.
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketConfig.class);

    /** Profile name that gates "real" deployment behavior. */
    private static final String PROD_PROFILE = "prod";

    /** Dev convenience default when {@code app.websocket.allowed-origins} is unset. */
    private static final String DEV_DEFAULT_ORIGIN = "http://localhost:8080";

    @Autowired
    private ObjectMapper objectMapper; // For structured JSON messages

    @Autowired
    private Environment env;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        logger.info("Registering WebSocket handlers for Fax Trident...");
        String[] origins = resolveAllowedOrigins();
        registry.addHandler(faxUpdateHandler(), "/fax-updates")
            .addInterceptors(new WebSocketSecurityInterceptor())
            .setAllowedOrigins(origins)
            .withSockJS(); // Fallback for browsers
        logger.info("WebSocket handler registered at '/fax-updates' with allowed origins {}",
                (Object) origins);
    }

    /**
     * Resolves the allowed-origin list from {@code app.websocket.allowed-origins}
     * (comma-separated). If the property is unset:
     * <ul>
     *   <li>in the {@code prod} profile, startup fails with a clear message so
     *       a misconfigured deployment doesn't silently accept connections
     *       from anywhere or, worse, reject every real client (the previous
     *       hardcoded {@code http://localhost:8080} did the latter);</li>
     *   <li>otherwise, the dev default {@value #DEV_DEFAULT_ORIGIN} is used.</li>
     * </ul>
     *
     * <p>The trim + filter pass tolerates stray whitespace in env-var-style
     * config like {@code "https://a.example, https://b.example"}.
     */
    private String[] resolveAllowedOrigins() {
        String raw = env.getProperty("app.websocket.allowed-origins");
        boolean isProd = Arrays.asList(env.getActiveProfiles()).contains(PROD_PROFILE);

        if (raw == null || raw.isBlank()) {
            if (isProd) {
                throw new IllegalStateException(
                        "app.websocket.allowed-origins must be set in the prod profile " +
                        "(no default is safe for production). Provide a comma-separated " +
                        "list, e.g. app.websocket.allowed-origins=https://app.example.com,https://admin.example.com");
            }
            logger.warn("app.websocket.allowed-origins not set; using dev default {}",
                    DEV_DEFAULT_ORIGIN);
            return new String[] { DEV_DEFAULT_ORIGIN };
        }

        String[] parsed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        if (parsed.length == 0) {
            throw new IllegalStateException(
                    "app.websocket.allowed-origins is set but parses to an empty list: '" + raw + "'");
        }
        return parsed;
    }

    @Bean
    public FaxUpdateHandler faxUpdateHandler() {
        return new FaxUpdateHandler(objectMapper);
    }

    // Inner class for handling fax updates
    public static class FaxUpdateHandler extends TextWebSocketHandler {
        private final ConcurrentHashMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        private final AtomicInteger connectionCount = new AtomicInteger(0);
        private final ObjectMapper objectMapper;

        public FaxUpdateHandler(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            sessions.put(session.getId(), session);
            int count = connectionCount.incrementAndGet();
            logger.info("New WebSocket connection: ID={}, User={}, Total={}", 
                        session.getId(), session.getPrincipal() != null ? session.getPrincipal().getName() : "anonymous", count);
            session.sendMessage(new TextMessage("Connected to Fax Trident updates - " + count + " active clients"));
        }

        @Override
        public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            logger.debug("Received message from {}: {}", session.getId(), payload);
            // Echo back as JSON for testing; replace with specific logic if needed
            Map<String, String> response = Map.of("userId", session.getId(), "message", payload);
            broadcast(response);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
            sessions.remove(session.getId());
            int count = connectionCount.decrementAndGet();
            logger.info("WebSocket closed: ID={}, Status={}, Remaining={}", session.getId(), status, count);
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            logger.error("Transport error for session {}: {}", session.getId(), exception.getMessage());
            sessions.remove(session.getId());
            connectionCount.decrementAndGet();
        }

        // Broadcast updates to all connected clients as JSON
        public void broadcast(Object message) {
            try {
                String jsonMessage = objectMapper.writeValueAsString(message);
                logger.info("Broadcasting message to {} clients: {}", sessions.size(), jsonMessage);
                sessions.values().forEach(session -> {
                    try {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(jsonMessage));
                        }
                    } catch (IOException e) {
                        logger.error("Failed to send to session {}: {}", session.getId(), e.getMessage());
                    }
                });
            } catch (JsonProcessingException e) {
                logger.error("Failed to serialize message: {}", e.getMessage());
            }
        }

        // Get connection count for metrics
        public int getConnectionCount() {
            return connectionCount.get();
        }

        @Scheduled(fixedRate = 60000) // Every minute
        public void cleanupStaleSessions() {
            int initialSize = sessions.size();
            sessions.entrySet().removeIf(entry -> !entry.getValue().isOpen());
            int removed = initialSize - sessions.size();
            if (removed > 0) {
                logger.info("Cleaned up {} stale WebSocket sessions, remaining: {}", removed, sessions.size());
            }
        }
    }

    // Security interceptor for WebSocket authentication
    public static class WebSocketSecurityInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                       org.springframework.http.server.ServerHttpResponse response,
                                       org.springframework.web.socket.WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
            org.springframework.security.core.Authentication auth = 
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated() || 
                auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
                logger.warn("Unauthorized WebSocket connection attempt");
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put("username", auth.getName());
            logger.info("WebSocket handshake allowed for user: {}", auth.getName());
            return true;
        }

        @Override
        public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                   org.springframework.http.server.ServerHttpResponse response,
                                   org.springframework.web.socket.WebSocketHandler wsHandler,
                                   Exception exception) {
            if (exception != null) {
                logger.error("WebSocket handshake failed: {}", exception.getMessage());
            }
        }
    }
}
