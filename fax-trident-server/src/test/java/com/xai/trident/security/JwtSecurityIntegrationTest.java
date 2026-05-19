package com.xai.trident.security;

import com.xai.trident.config.SecurityConfig;
import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.ratelimit.RateLimitAspect;
import com.xai.trident.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end probe of the JWT filter chain.
 *
 * <p>Where the controller-slice tests use {@code @WithMockUser} to inject a
 * fake {@code Authentication} into the {@code SecurityContext} directly,
 * this test mints real signed JWTs with the application's
 * {@link JwtTokenProvider} and submits them through MockMvc. That's the
 * level at which the audit's 1.1 / 1.6 / 1.7 fixes actually live:
 *
 * <ul>
 *   <li><b>Bad signature</b> (forged with a different key) → 401. Documents
 *       the closure of audit 1.1.</li>
 *   <li><b>Expired token</b> → 401. The token parses but the {@code exp}
 *       claim has passed.</li>
 *   <li><b>Valid token + jti in Redis allowlist</b> → 200. The happy path.</li>
 *   <li><b>Valid signature but jti missing from allowlist</b> → 401. Documents
 *       the closure of audit 1.6 (logout invalidation).</li>
 *   <li><b>Missing Authorization header</b> → 401. The {@code HttpStatusEntryPoint}
 *       returns 401 for unauthenticated protected requests instead of redirecting
 *       to a login form (tech-debt #5).</li>
 * </ul>
 *
 * <p>The test uses a tiny in-test {@code @RestController} ({@code /test-protected})
 * rather than loading the real {@code FaxController} so the suite stays fast
 * and doesn't need a DB / repositories. The auth-rule under test —
 * {@code anyRequest().authenticated()} — applies uniformly across the
 * filter chain, so the protected endpoint can be anything.
 *
 * <p>DB / Redis / JPA / Flyway auto-configurations are explicitly excluded
 * via the @SpringBootConfiguration below so the test boots in well under a
 * second.
 */
@SpringBootTest(classes = JwtSecurityIntegrationTest.TestApp.class)
@AutoConfigureMockMvc
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "jwt.secret=integration-test-secret-thats-32-bytes-long-now",
        "jwt.validity=3600000"
})
public class JwtSecurityIntegrationTest {

    private static final String JTI_PREFIX = "jwt:jti:";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    // The whole point of mocking RedisTemplate here is to control the jti
    // allowlist deterministically. We don't need a real Redis to exercise
    // the filter chain.
    @MockBean(name = "redisTemplate") @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    // Other beans SecurityConfig transitively depends on.
    @MockBean private UserRepository userRepository;
    @MockBean private RateLimitAspect rateLimitAspect;

    private ValueOperations<String, Object> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wireRedisMock() {
        valueOps = org.mockito.Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Default: nothing is in the allowlist. Per-test overrides flip
        // specific keys to "present" as needed.
        when(redisTemplate.hasKey(any())).thenReturn(false);
    }

    @Test
    void missing_authorization_header_returns_401() throws Exception {
        mockMvc.perform(get("/test-protected"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void valid_token_with_jti_in_allowlist_returns_200() throws Exception {
        String token = jwtTokenProvider.createToken("alice", List.of("ROLE_USER"));
        // Anything we minted is "in the allowlist" — the production behavior
        // (set TTL'd entry on issue) is what we're emulating without Redis.
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.startsWith(JTI_PREFIX)))
                .thenReturn(true);

        mockMvc.perform(get("/test-protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("pong:alice"));
    }

    @Test
    void valid_signature_but_revoked_jti_returns_401() throws Exception {
        String token = jwtTokenProvider.createToken("alice", List.of("ROLE_USER"));
        // hasKey stays false (default from @BeforeEach), simulating a
        // revoked-via-logout scenario. The token is well-signed; the filter
        // still rejects it because the jti isn't allowlisted.
        mockMvc.perform(get("/test-protected").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forged_token_with_wrong_signing_key_returns_401() throws Exception {
        // Mint a token with a DIFFERENT secret. validateToken can't verify
        // the signature against the configured key, throws JwtException
        // (caught in validateToken), and the filter never authenticates.
        byte[] wrongKey = "different-secret-also-32-bytes-long-yes".getBytes(StandardCharsets.UTF_8);
        String forged = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", List.of("ROLE_USER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(wrongKey))
                .compact();

        mockMvc.perform(get("/test-protected").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expired_token_returns_401() throws Exception {
        // Mint a token with the CORRECT secret but an exp claim already in
        // the past. validateToken sees ExpiredJwtException from the parser,
        // returns false, filter doesn't authenticate.
        byte[] correctKey = "integration-test-secret-thats-32-bytes-long-now"
                .getBytes(StandardCharsets.UTF_8);
        long pastMs = System.currentTimeMillis() - 10_000;
        String expired = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", List.of("ROLE_USER"))
                .setIssuedAt(new Date(pastMs - 60_000))
                .setExpiration(new Date(pastMs))
                .signWith(Keys.hmacShaKeyFor(correctKey))
                .compact();

        mockMvc.perform(get("/test-protected").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    // ──────────────────────────────────────────────────────────────────
    // Minimal in-test application — no DB, no Redis auto-config.
    //
    // The exclusions are explicit because @SpringBootApplication's default
    // auto-config would pull in Hibernate, Flyway, Lettuce, etc., each of
    // which wants a real DataSource / Redis connection. None of that
    // matters for a JWT-filter test, and skipping the auto-config drops
    // the test boot from ~5s to <500ms.
    // ──────────────────────────────────────────────────────────────────
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

        // Declared as an explicit @Bean rather than relying on @ComponentScan
        // — TestApp deliberately omits @ComponentScan so we don't pull in
        // the entire `com.xai.trident` tree (controllers, services,
        // repositories) into this slice. RequestMappingHandlerMapping picks
        // up any bean annotated @Controller / @RestController regardless of
        // how it ended up in the context.
        @org.springframework.context.annotation.Bean
        public ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    public static class ProbeController {
        // Returns the authenticated principal's name so we can prove the
        // filter actually populated SecurityContext (not just "didn't 401").
        @GetMapping("/test-protected")
        public String ping(org.springframework.security.core.Authentication auth) {
            return "pong:" + auth.getName();
        }
    }
}
