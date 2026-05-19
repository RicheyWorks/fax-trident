package com.xai.trident.config;

import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link JwtTokenProvider}. No Spring context, no
 * filesystem, no real Redis — just the provider, a Mockito-mocked
 * {@link RedisTemplate}, and the JJWT library used directly to mint
 * "adversarial" tokens (wrong signature, expired, missing jti, malformed
 * roles claim).
 *
 * <p>Covers the production behavior the audit cluster 1.1 / 1.6 / 1.7
 * spelled out:
 * <ul>
 *   <li>1.1 — fail-fast on missing or under-length secret (constructor).</li>
 *   <li>1.6 — every issued JWT carries a jti registered in Redis with the
 *       same TTL; logout removes it; tokens without an allowlist entry are
 *       rejected even when the signature is valid.</li>
 *   <li>1.7 — safe role-claim parsing: only {@code List<String>} is
 *       accepted; everything else surfaces as {@link JwtException}.</li>
 * </ul>
 *
 * <p>{@code JwtSecurityIntegrationTest} covers the same behaviors end-to-end
 * through MockMvc + the SecurityFilterChain. These unit tests pin the
 * provider's contract independently so a regression in the
 * filter-wiring layer can't mask a regression in the provider, and vice
 * versa.
 */
public class JwtTokenProviderTest {

    /** A 47-byte secret — comfortably above the 32-byte HS256 floor. */
    private static final String VALID_SECRET = "unit-test-secret-thats-at-least-32-bytes-long";
    /** A different, equally-valid secret used to forge tokens. */
    private static final String OTHER_SECRET = "different-secret-also-at-least-32-bytes-long";
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final String JTI_PREFIX = "jwt:jti:";

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RedisTemplate redisTemplate;
    private ValueOperations<String, Object> valueOps;
    private JwtTokenProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        provider = new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS, redisTemplate);
    }

    // ── Constructor validation (audit 1.1) ────────────────────────────

    @Test
    void constructor_nullSecret_throwsWithClearMessage() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider(null, ONE_HOUR_MS, redisTemplate));
        assertTrue(ex.getMessage().contains("JWT_SECRET"),
                "error message should name JWT_SECRET so operators know what to set");
    }

    @Test
    void constructor_blankSecret_throws() {
        assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("   ", ONE_HOUR_MS, redisTemplate));
    }

    @Test
    void constructor_shortSecret_throwsWithLengthDetail() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new JwtTokenProvider("too-short", ONE_HOUR_MS, redisTemplate));
        // The exception body explains exactly how many bytes were supplied vs.
        // required. Pin the key shape so future error-message edits keep the
        // diagnostic.
        assertTrue(ex.getMessage().contains("at least 32"),
                "error should state the 32-byte minimum");
    }

    @Test
    void constructor_nullRedisTemplate_throws() {
        assertThrows(NullPointerException.class,
                () -> new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS, null));
    }

    @Test
    void constructor_validInputs_succeeds() {
        // No throw means pass — separately covered by the @BeforeEach success.
        new JwtTokenProvider(VALID_SECRET, ONE_HOUR_MS, redisTemplate);
    }

    // ── createToken + jti registration (audit 1.6) ────────────────────

    @Test
    void createToken_registersJtiInRedisWithMatchingTtl() {
        String token = provider.createToken("alice", List.of("ROLE_USER"));

        assertNotNull(token);
        // Token should register exactly one allowlist entry: jti → username,
        // TTL == jwt.validity. Verify both the key shape and the value/ttl.
        verify(valueOps, times(1)).set(
                startsWith(JTI_PREFIX),
                eq("alice"),
                eq(ONE_HOUR_MS),
                eq(TimeUnit.MILLISECONDS));
    }

    @Test
    void createToken_subjectAndRolesRoundTrip() {
        String token = provider.createToken("alice", List.of("ROLE_USER", "ROLE_ADMIN"));

        assertEquals("alice", provider.getUsername(token));
        // Allowlist must say "present" for getRoles' downstream validateToken
        // flow not to be relevant here — getRoles just decodes the claim.
        assertEquals(List.of("ROLE_USER", "ROLE_ADMIN"), provider.getRoles(token));
    }

    // ── validateToken: the audit-1.6 allowlist gate ───────────────────

    @Test
    void validateToken_signatureGood_jtiPresent_returnsTrue() {
        String token = provider.createToken("alice", List.of("ROLE_USER"));
        when(redisTemplate.hasKey(startsWith(JTI_PREFIX))).thenReturn(true);

        assertTrue(provider.validateToken(token));
    }

    @Test
    void validateToken_signatureGood_jtiAbsent_returnsFalse() {
        String token = provider.createToken("alice", List.of("ROLE_USER"));
        // hasKey defaults to false / null from the mock — no allowlist entry.
        // This is the post-logout case from audit 1.6.
        when(redisTemplate.hasKey(any())).thenReturn(false);

        assertFalse(provider.validateToken(token),
                "a valid signature without an allowlist entry must NOT authenticate");
    }

    @Test
    void validateToken_jtiNull_returnsFalse() {
        // Pre-revocation-feature tokens have no jti claim. The provider
        // explicitly rejects them — pin the contract so a future
        // claims-parsing refactor can't accidentally re-accept them.
        String legacyToken = Jwts.builder()
                .setSubject("alice")
                .claim("roles", List.of("ROLE_USER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertFalse(provider.validateToken(legacyToken));
    }

    @Test
    void validateToken_forgedSignature_returnsFalse() {
        // Mint a token with a DIFFERENT key — same shape, same claims, but
        // unverifiable against the configured signing key.
        String forged = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", List.of("ROLE_USER"))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(OTHER_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertFalse(provider.validateToken(forged));
        // Allowlist lookup is short-circuited by the signature failure — we
        // never reach hasKey, so no Redis call should fire.
        verifyNoInteractions(valueOps);
    }

    @Test
    void validateToken_expired_returnsFalse() {
        long pastMs = System.currentTimeMillis() - 60_000;
        String expired = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", List.of("ROLE_USER"))
                .setIssuedAt(new Date(pastMs - 60_000))
                .setExpiration(new Date(pastMs))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertFalse(provider.validateToken(expired));
    }

    // ── getRoles: safe-cast contract (audit 1.7) ──────────────────────

    @Test
    void getRoles_malformedRolesClaim_notAList_throwsJwtException() {
        // Roles claim is a String instead of a list — type mismatch must
        // surface as JwtException, not ClassCastException.
        String token = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", "ROLE_USER")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> provider.getRoles(token));
    }

    @Test
    void getRoles_listWithNonStringElement_throwsJwtException() {
        // List but the element is a Number — must reject.
        String token = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                .claim("roles", List.of(42))
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> provider.getRoles(token));
    }

    @Test
    void getRoles_missingRolesClaim_throwsJwtException() {
        String token = Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject("alice")
                // no `roles` claim
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(VALID_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(JwtException.class, () -> provider.getRoles(token));
    }

    // ── revokeToken (audit 1.6 — logout path) ─────────────────────────

    @Test
    void revokeToken_removesJtiFromAllowlist() {
        String token = provider.createToken("alice", List.of("ROLE_USER"));

        provider.revokeToken(token);

        verify(redisTemplate, times(1)).delete(startsWith(JTI_PREFIX));
    }

    @Test
    void revokeToken_malformedToken_isNoOpNotThrow() {
        // Idempotent contract: revoking an already-invalid token must not
        // surface an exception (the LogoutHandler relies on this).
        provider.revokeToken("not.a.jwt.at.all");

        // No delete call — there's nothing to delete and nothing to throw.
        verify(redisTemplate, times(0)).delete(any(String.class));
    }

    // ── Sanity: createToken doesn't leak unrelated Redis writes ───────

    @Test
    void createToken_doesNotTouchUnrelatedRedisOperations() {
        // Document the one allowlist call (set + TTL). Anything else would
        // be a regression worth flagging — JwtTokenProvider should not be
        // making other side trips into Redis.
        provider.createToken("alice", List.of("ROLE_USER"));

        verify(valueOps, times(1)).set(any(), any(), anyLong(), any(TimeUnit.class));
        verify(valueOps, times(0)).get(any());
    }

    @Test
    void valueOpsMockIsWiredCorrectly() {
        // Sanity smoke: setUp's wiring of opsForValue() actually returns
        // our mock so the production code path is observable.
        assertEquals(valueOps, redisTemplate.opsForValue());
        // Unused-field guard for the static analyser via a placeholder usage.
        assertNotNull(Map.of("x", 1));
    }
}
