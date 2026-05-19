package com.xai.trident.config;

import com.xai.trident.model.User;
import com.xai.trident.repository.UserRepository;
import com.xai.trident.util.LogSanitizer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Stateless JWT-only auth (audit tech-debt #5; closes 1.3 and 1.4 by
 * elimination).
 *
 * <p>Single auth surface: clients exchange username + password at
 * {@code POST /api/auth/login} (see {@link com.xai.trident.controller.AuthController})
 * for a JWT, then attach it as {@code Authorization: Bearer <token>} on
 * subsequent requests. There are no HTTP sessions, no {@code JSESSIONID}
 * cookie, no form-login page, and no CSRF filter — none are needed in a
 * model where state lives entirely in the bearer token.
 *
 * <p>Why this shape:
 * <ul>
 *   <li>The previous hybrid (form login + JWT + sessions) gave us the worst
 *       of both worlds — CSRF had to be conditionally enforced based on
 *       whether a Bearer header was present (1.3), the form had to interpolate
 *       a CSRF token through Thymeleaf (1.4), and logout had to tear down two
 *       independent state mechanisms.</li>
 *   <li>Going stateless removes both findings by removing their preconditions:
 *       no sessions means no CSRF surface; no form means no CSRF token field.</li>
 *   <li>JWT logout is still meaningful: each token carries a {@code jti}
 *       registered in Redis with the same TTL as the token. The filter
 *       rejects any well-signed but unknown jti, and the {@code /logout}
 *       endpoint deletes the jti so subsequent uses of the token 401.</li>
 *   <li>The OAuth2 conditional wiring was removed. If OAuth2 comes back, it
 *       should be a side endpoint that mints a JWT on the OAuth callback,
 *       not a parallel session-creating filter.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
// @EnableMethodSecurity activates @PreAuthorize on controllers (e.g.
// FaxController's hasRole('USER') and AdminController's hasRole('ADMIN')).
// Without this, those annotations are inert and the only access control
// is the URL-pattern matching below — which catches the same cases today
// but is redundant with the controller-level intent. Keep both: the URL
// pattern is the belt, @PreAuthorize is the braces. If a future endpoint
// is registered under a path the matcher doesn't cover, the controller
// annotation still gates access.
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Symmetric signing key for HS256 JWTs.
     *
     * MUST be supplied via configuration (env var `JWT_SECRET`, or
     * `jwt.secret` in application.yml). There is intentionally no default —
     * a missing or short value will fail application startup, which is
     * preferable to silently signing tokens with a known weak key.
     *
     * Minimum length is 32 bytes (256 bits) as required by RFC 7518 for HS256.
     * Generate one with: `openssl rand -base64 48` (drop the trailing '=').
     *
     * The default here is an EMPTY STRING (not a placeholder secret). That
     * lets Spring resolve the placeholder successfully so {@link JwtTokenProvider}
     * can throw its own descriptive error, instead of bombing out with a
     * generic "Could not resolve placeholder" message.
     */
    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${jwt.validity:3600000}")
    private long jwtValidityInMs;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwtTokenProvider) throws Exception {
        logger.info("Configuring Spring Security: stateless JWT-only");

        http
            .authorizeHttpRequests(auth -> auth
                // The single endpoint that doesn't require auth — it's what
                // you call to GET auth. /logout is also permitAll because we
                // want it to be safely callable with an already-expired token
                // (the revocation just becomes a no-op).
                .requestMatchers("/api/auth/login", "/logout").permitAll()
                // Actuator health check stays open for k8s/Docker probes.
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/api/fax/**").hasRole("USER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/ws/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtTokenFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
            // No session: every request must carry its own JWT.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // No CSRF: there's no ambient session cookie for an attacker to
            // ride. A cross-site request can't attach the Authorization
            // header, and that's the only way in.
            .csrf(csrf -> csrf.disable())
            // No form login, no HTTP Basic, no OAuth2 — the only auth path is
            // POST /api/auth/login, handled by AuthController above.
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            // Unauthenticated requests get 401, not a redirect to a login
            // page (there is no login page).
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .logout(logout -> logout
                .logoutUrl("/logout")
                // JSON 200 on logout — no redirect.
                .logoutSuccessHandler((request, response, authentication) -> {
                    String username = authentication != null ? authentication.getName() : "anonymous";
                    logger.info("User '{}' logged out", LogSanitizer.sanitize(username));
                    response.setStatus(HttpStatus.NO_CONTENT.value());
                })
                .addLogoutHandler(jwtRevocationLogoutHandler(jwtTokenProvider))
            );

        logger.info("Security configuration applied: stateless JWT, role-based access");
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(JpaUserDetailsService jpaUserDetailsService) {
        logger.info("Setting up JPA-based user details service...");
        return jpaUserDetailsService;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        logger.info("Initializing BCrypt password encoder...");
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes Spring's AuthenticationManager as a bean so {@link
     * com.xai.trident.controller.AuthController} can call {@code .authenticate()}
     * to validate username/password against {@link JpaUserDetailsService}.
     *
     * <p>Spring auto-wires the {@link UserDetailsService} and
     * {@link PasswordEncoder} beans (declared above) into the
     * {@link AuthenticationConfiguration}, so we just need to pull the
     * configured manager out.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public JwtTokenProvider jwtTokenProvider(RedisTemplate<String, Object> redisTemplate) {
        // Validation is performed inside the provider constructor so the
        // application fails fast at bean wiring if the secret is missing
        // or too short for HS256.
        return new JwtTokenProvider(jwtSecret, jwtValidityInMs, redisTemplate);
    }

    @Bean
    public Filter jwtTokenFilter(JwtTokenProvider jwtTokenProvider) {
        return new JwtTokenFilter(jwtTokenProvider);
    }

    /**
     * Pulls the Bearer token off the logout request and revokes its jti.
     * Idempotent — missing/invalid tokens are silently ignored, which is the
     * right behavior for logout (we don't want to leak "the token you
     * presented was already invalid" as a distinguishable response).
     */
    @Bean
    public LogoutHandler jwtRevocationLogoutHandler(JwtTokenProvider jwtTokenProvider) {
        return (request, response, authentication) -> {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    jwtTokenProvider.revokeToken(token);
                } catch (Exception e) {
                    logger.debug("Could not revoke JWT on logout (likely already invalid): {}",
                            LogSanitizer.sanitize(e.getMessage()));
                }
            }
        };
    }

    @Bean
    public JpaUserDetailsService jpaUserDetailsService(UserRepository userRepository) {
        return new JpaUserDetailsService(userRepository);
    }

    // ──────────────────────────────────────────────────────────────
    // JPA UserDetailsService
    // ──────────────────────────────────────────────────────────────
    public static class JpaUserDetailsService implements UserDetailsService {

        private final UserRepository userRepository;

        public JpaUserDetailsService(UserRepository userRepository) {
            this.userRepository = userRepository;
        }

        @Override
        public org.springframework.security.core.userdetails.UserDetails loadUserByUsername(String username) {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new org.springframework.security.core.userdetails.UsernameNotFoundException(
                            "User not found: " + username));

            return org.springframework.security.core.userdetails.User.builder()
                    .username(user.getUsername())
                    .password(user.getPassword())
                    .roles(user.getRoles().split(","))
                    .build();
        }
    }

    // User entity and UserRepository moved to com.xai.trident.model and
    // com.xai.trident.repository as part of audit finding 2.15.

    // ──────────────────────────────────────────────────────────────
    // JWT Provider & Filter
    // ──────────────────────────────────────────────────────────────
    public static class JwtTokenProvider {

        /**
         * RFC 7518 §3.2 requires HS256 keys to be at least as long as the
         * hash output, i.e. 256 bits / 32 bytes. We enforce that explicitly
         * here so the failure mode is a clear startup error rather than a
         * less obvious {@code WeakKeyException} thrown on first use.
         */
        private static final int MIN_SECRET_BYTES = 32;

        /** Prefix for jti allowlist entries in Redis. */
        private static final String JTI_KEY_PREFIX = "jwt:jti:";

        private final SecretKey signingKey;
        private final long validityInMs;
        private final RedisTemplate<String, Object> redisTemplate;

        public JwtTokenProvider(String secret, long validityInMs, RedisTemplate<String, Object> redisTemplate) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "jwt.secret is not configured. Set the JWT_SECRET env var (or jwt.secret property) " +
                        "to a random value of at least " + MIN_SECRET_BYTES + " bytes. " +
                        "Generate one with: openssl rand -base64 48");
            }
            byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException(
                        "jwt.secret is too short for HS256: got " + keyBytes.length +
                        " bytes, need at least " + MIN_SECRET_BYTES + ". " +
                        "Generate one with: openssl rand -base64 48");
            }
            this.signingKey = Keys.hmacShaKeyFor(keyBytes);
            this.validityInMs = validityInMs;
            this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        }

        /**
         * Issues a JWT and registers its {@code jti} in the Redis allowlist
         * with the same TTL. Tokens whose jti is missing from the allowlist
         * (because they were revoked, or expired and evicted) are rejected
         * by {@link #validateToken(String)} even if the signature checks out.
         */
        public String createToken(String username, List<String> roles) {
            String jti = UUID.randomUUID().toString();
            long nowMs = System.currentTimeMillis();
            String token = Jwts.builder()
                    .setId(jti)
                    .setSubject(username)
                    .claim("roles", roles)
                    .setIssuedAt(new Date(nowMs))
                    .setExpiration(new Date(nowMs + validityInMs))
                    .signWith(signingKey)
                    .compact();
            // Allowlist registration with same TTL as the token itself.
            // Value is the subject so an operator can audit who owns a jti.
            redisTemplate.opsForValue().set(
                    JTI_KEY_PREFIX + jti, username, validityInMs, TimeUnit.MILLISECONDS);
            return token;
        }

        /**
         * Removes the token's jti from the allowlist. After this call any
         * further use of the token is rejected by {@link #validateToken}.
         * Safe to call with a malformed token — logs and returns.
         */
        public void revokeToken(String token) {
            try {
                Claims claims = parseClaims(token);
                String jti = claims.getId();
                if (jti != null) {
                    redisTemplate.delete(JTI_KEY_PREFIX + jti);
                }
            } catch (JwtException | IllegalArgumentException e) {
                LoggerFactory.getLogger(JwtTokenProvider.class)
                        .debug("revokeToken: token not parseable, nothing to revoke");
            }
        }

        public String getUsername(String token) {
            return parseClaims(token).getSubject();
        }

        /**
         * Safe role-claim parsing. Returns the roles as a {@code List<String>}
         * or throws {@link JwtException} when the claim is missing, the
         * wrong type, or contains non-string elements. Callers that catch
         * {@link JwtException} (such as {@link JwtTokenFilter}) treat that
         * as authentication failure.
         */
        public List<String> getRoles(String token) {
            Object raw = parseClaims(token).get("roles");
            if (!(raw instanceof List<?> list)) {
                throw new JwtException("JWT 'roles' claim missing or not a list");
            }
            for (Object element : list) {
                if (!(element instanceof String)) {
                    throw new JwtException("JWT 'roles' claim contains non-string element");
                }
            }
            // Safe cast — checked above.
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) list;
            return roles;
        }

        public boolean validateToken(String token) {
            try {
                Claims claims = parseClaims(token);
                String jti = claims.getId();
                if (jti == null || jti.isBlank()) {
                    // Pre-revocation-feature tokens have no jti — reject them.
                    LoggerFactory.getLogger(JwtTokenProvider.class)
                            .warn("Rejecting JWT with missing jti");
                    return false;
                }
                Boolean present = redisTemplate.hasKey(JTI_KEY_PREFIX + jti);
                if (present == null || !present) {
                    LoggerFactory.getLogger(JwtTokenProvider.class)
                            .warn("Rejecting JWT with revoked or unknown jti");
                    return false;
                }
                return true;
            } catch (Exception e) {
                LoggerFactory.getLogger(JwtTokenProvider.class).error(
                        "Invalid JWT token: {}", LogSanitizer.sanitize(e.getMessage()));
                return false;
            }
        }

        private Claims parseClaims(String token) {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        }
    }

    public static class JwtTokenFilter extends HttpFilter {
        private final JwtTokenProvider jwtTokenProvider;

        public JwtTokenFilter(JwtTokenProvider jwtTokenProvider) {
            this.jwtTokenProvider = jwtTokenProvider;
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String header = ((HttpServletRequest) request).getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    try {
                        String username = jwtTokenProvider.getUsername(token);
                        List<String> roles = jwtTokenProvider.getRoles(token);

                        // The JWT's `roles` claim is built by AuthController
                        // from authentication.getAuthorities().getAuthority(),
                        // so it carries the full role string ("ROLE_USER",
                        // "ROLE_ADMIN", ...). Use .authorities(...) which
                        // accepts those verbatim — NOT .roles(...), which
                        // expects the bare role name and rejects strings
                        // that start with "ROLE_" ("ROLE_USER cannot start
                        // with ROLE_"). Surfaced by JwtSecurityIntegrationTest.
                        org.springframework.security.core.userdetails.User userDetails =
                                (org.springframework.security.core.userdetails.User)
                                        org.springframework.security.core.userdetails.User.builder()
                                                .username(username)
                                                .password("")
                                                .authorities(roles.toArray(new String[0]))
                                                .build();

                        org.springframework.security.core.Authentication auth =
                                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());

                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (JwtException e) {
                        // Roles claim was malformed — refuse authentication
                        // but don't 401; downstream authorization will reject
                        // the request when it inspects SecurityContext.
                        LoggerFactory.getLogger(JwtTokenFilter.class).warn(
                                "Discarding JWT with bad claims: {}",
                                LogSanitizer.sanitize(e.getMessage()));
                    }
                }
            }
            chain.doFilter(request, response);
        }
    }
}
