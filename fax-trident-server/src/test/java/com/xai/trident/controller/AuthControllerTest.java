package com.xai.trident.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.trident.config.SecurityConfig;
import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link AuthController}. Covers the single auth surface
 * (POST /api/auth/login) the JWT-only model exposes (audit tech-debt #5).
 *
 * <p>Why this exists, not just a happy-path integration test: AuthController
 * delegates to {@link AuthenticationManager} and {@link JwtTokenProvider};
 * mocking both lets us assert response shape, status code, and validation
 * behavior without spinning up a real database or Redis.
 *
 * <p>Coverage:
 * <ul>
 *   <li>200 + {"token":"..."} on valid credentials.</li>
 *   <li>401 + {"error":"Invalid credentials"} on bad credentials (no
 *       username-enumeration via differential responses — audit 1.x
 *       cluster spirit).</li>
 *   <li>400 on validation failures (blank username / blank password).</li>
 * </ul>
 *
 * <p>Not covered here — exercised by {@code JwtSecurityIntegrationTest}:
 * the full SecurityFilterChain (URL-pattern role gating, JWT filter,
 * jti allowlist behavior). Rate limiting on /api/auth/login is also
 * outside the slice — the AOP aspect isn't loaded in @WebMvcTest.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        // SecurityConfig fails fast on missing/<32-byte secret. Provide a
        // deterministic test value so the bean wires up — JwtTokenProvider
        // itself is @MockBean'd so the value is never actually signed with.
        "jwt.secret=test-secret-must-be-at-least-32-bytes-long-yes-it-is"
})
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthenticationManager authenticationManager;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    // IMPORTANT: do NOT @MockBean RateLimitAspect here. Mocking the aspect
    // registers it as a bean, Spring's @EnableAspectJAutoProxy then wires
    // the mock into the proxy chain, and the mock's @Around returns null
    // without calling pjp.proceed() — so the controller method never runs
    // and you get an inscrutable 200-with-empty-body response.
    //
    // @WebMvcTest's default slice doesn't load @Component / @Aspect beans,
    // so the real RateLimitAspect simply isn't in the context and
    // @RateLimit becomes inert metadata. That's exactly what we want
    // here — testing the controller's logic, not the aspect's.
    //
    // The aspect itself should have its own targeted test if/when its
    // behavior needs verification beyond the smoke test that
    // JwtSecurityIntegrationTest provides.

    // SecurityConfig's JpaUserDetailsService needs a UserRepository bean.
    // The actual user table is irrelevant for these tests — the
    // AuthenticationManager is mocked.
    @MockBean
    private UserRepository userRepository;

    // SecurityConfig.jwtTokenProvider would normally construct from secret +
    // RedisTemplate. The bean is mocked above, but the RedisTemplate slot
    // still needs to exist for any other consumer (e.g. controller
    // injection paths in slices that share this config).
    @MockBean(name = "redisTemplate")
    @SuppressWarnings("rawtypes")
    private RedisTemplate redisTemplate;

    // All POST tests use .with(csrf()). SecurityConfig.csrf().disable() should
    // already turn the filter off, but Spring Security's MockMvc auto-config
    // sometimes installs a CSRF filter on top regardless — and a missing
    // token in that case silently rejects the request with status 200 and an
    // empty body, which is the world's most confusing failure mode. Adding
    // csrf() is a no-op when the filter is truly off, and the fix when it
    // isn't. Same applies to FaxControllerTest.

    @Test
    void login_withValidCredentials_returns200AndToken() throws Exception {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "alice", "irrelevant",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        // Use eq + any rather than literal List.of("ROLE_USER"): the
        // controller produces the roles list via Stream.toList(), and the
        // resulting immutable list type's .equals(...) interaction with
        // Mockito's matcher comparison is fragile enough not to rely on.
        when(jwtTokenProvider.createToken(eq("alice"), any()))
                .thenReturn("the.expected.jwt");

        String body = objectMapper.writeValueAsString(
                Map.of("username", "alice", "password", "hunter2"));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("the.expected.jwt"));
    }

    @Test
    void login_withBadCredentials_returns401WithGenericError() throws Exception {
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("ignored — controller surfaces a generic message"));

        String body = objectMapper.writeValueAsString(
                Map.of("username", "alice", "password", "wrong"));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                // The generic message exists by design — leaking "user not found" vs
                // "wrong password" would let attackers enumerate usernames.
                .andExpect(jsonPath("$.error").value("Invalid credentials"));
    }

    @Test
    void login_withBlankUsername_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "", "password", "hunter2"));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_withBlankPassword_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", "alice", "password", ""));

        mockMvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
