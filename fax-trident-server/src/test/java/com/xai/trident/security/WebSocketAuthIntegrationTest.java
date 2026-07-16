package com.xai.trident.security;

import com.xai.trident.config.SecurityConfig;
import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.config.WebSocketConfig;
import com.xai.trident.ratelimit.RateLimitAspect;
import com.xai.trident.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

/**
 * End-to-end probe of the desktop↔server WebSocket seam — the one place
 * three post-split bugs hid (never-compiling client, missing bearer on the
 * upgrade, raw-WS-vs-SockJS endpoint mismatch). Unlike
 * {@link JwtSecurityIntegrationTest}, MockMvc can't exercise an HTTP
 * upgrade, so this boots a real servlet container on a random port and
 * performs actual RFC 6455 handshakes with Spring's standard client — the
 * same wire exchange the desktop's Java-WebSocket client produces.
 *
 * <ul>
 *   <li><b>Bearer + allowlisted jti</b> → handshake completes, welcome
 *       broadcast arrives. Proves /fax-updates accepts RAW upgrades (the
 *       SockJS-only registration regression) and that the JWT filter
 *       authenticates the handshake GET.</li>
 *   <li><b>No Authorization header</b> → handshake rejected. The leaked-URL
 *       subscription hole from the AUDIT follow-up stays closed.</li>
 *   <li><b>Valid signature, revoked jti</b> → handshake rejected. Logout
 *       revocation applies to the WS surface, not just REST.</li>
 * </ul>
 */
@SpringBootTest(classes = WebSocketAuthIntegrationTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({SecurityConfig.class, WebSocketConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-thats-32-bytes-long-now",
        "jwt.validity=3600000"
})
public class WebSocketAuthIntegrationTest {

    private static final String JTI_PREFIX = "jwt:jti:";
    private static final long HANDSHAKE_TIMEOUT_S = 10;

    @LocalServerPort
    private int port;

    @Autowired private JwtTokenProvider jwtTokenProvider;

    @MockBean(name = "redisTemplate") @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    // Beans SecurityConfig transitively depends on; same set as
    // JwtSecurityIntegrationTest.
    @MockBean private UserRepository userRepository;
    @MockBean private RateLimitAspect rateLimitAspect;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireRedisMock() {
        ValueOperations<String, Object> valueOps =
                org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(any())).thenReturn(false);
    }

    private URI wsUri() {
        return URI.create("ws://localhost:" + port + "/fax-updates");
    }

    /** Handler that resolves a future with the first text frame received. */
    private static final class FirstMessageHandler extends TextWebSocketHandler {
        final CompletableFuture<String> firstMessage = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            firstMessage.complete(message.getPayload());
        }
    }

    @Test
    void bearer_with_allowlisted_jti_completes_handshake_and_receives_welcome() throws Exception {
        String token = jwtTokenProvider.createToken("alice", List.of("ROLE_USER"));
        when(redisTemplate.hasKey(startsWith(JTI_PREFIX))).thenReturn(true);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + token);

        FirstMessageHandler handler = new FirstMessageHandler();
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, headers, wsUri())
                .get(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS);
        try {
            assertThat(session.isOpen()).isTrue();
            // FaxUpdateHandler greets every new connection; receiving it
            // proves the raw (non-SockJS) endpoint served the upgrade and
            // the handler is live end-to-end.
            String welcome = handler.firstMessage.get(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS);
            assertThat(welcome).contains("Connected to Fax Trident updates");
        } finally {
            session.close();
        }
    }

    @Test
    void missing_authorization_header_is_rejected_at_handshake() {
        FirstMessageHandler handler = new FirstMessageHandler();
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(handler, new WebSocketHttpHeaders(), wsUri())
                .get(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(handler.firstMessage).isNotCompleted();
    }

    @Test
    void valid_signature_with_revoked_jti_is_rejected_at_handshake() {
        // Token is well-signed, but hasKey stays false (default) — the
        // logout-revocation path. REST rejects this token; the WS upgrade
        // must too, or logout leaves a live broadcast subscription behind.
        String token = jwtTokenProvider.createToken("alice", List.of("ROLE_USER"));
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Authorization", "Bearer " + token);

        FirstMessageHandler handler = new FirstMessageHandler();
        assertThatThrownBy(() -> new StandardWebSocketClient()
                .execute(handler, headers, wsUri())
                .get(HANDSHAKE_TIMEOUT_S, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(handler.firstMessage).isNotCompleted();
    }

    // Same slim-boot rationale as JwtSecurityIntegrationTest.TestApp: skip
    // DB / Redis / JPA / Flyway auto-config entirely. Web auto-config stays
    // ON (unlike the MockMvc test) because a real container + upgrade
    // support is the point here.
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    public static class TestApp {
    }
}
