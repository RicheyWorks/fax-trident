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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Hybrid auth: session + form-login for the browser flow, plus JWT (Bearer
 * token) for programmatic and desktop clients. Both surfaces are hardened:
 *
 * <ul>
 *   <li><b>CSRF</b> — enforced on every state-changing {@code /api/**} call
 *       that arrives WITHOUT a {@code Authorization: Bearer …} header. Pure
 *       JWT calls have no ambient session cookie to ride, so CSRF is not
 *       applicable and is skipped. The token lives in an {@code XSRF-TOKEN}
 *       cookie (HttpOnly=false) so the form / SPA can read it.</li>
 *   <li><b>JWT logout</b> — every issued JWT carries a {@code jti} claim that
 *       is also written into Redis with the same TTL as the token. The
 *       filter rejects any well-signed but unknown {@code jti}. {@link
 *       LogoutHandler} below extracts the bearer from the logout request and
 *       deletes the jti, so a logout immediately invalidates the JWT.</li>
 *   <li><b>Role-claim parsing</b> — {@code claims.get("roles")} is validated
 *       to be a list of strings before use. Malformed tokens are rejected.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
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

    /**
     * Google OAuth2 client credentials. Both default to an empty string so
     * Spring can resolve the placeholder successfully; the application then
     * decides at startup whether OAuth login is enabled:
     *
     * <ul>
     *   <li>both set → Google OAuth login is wired into the filter chain;</li>
     *   <li>both unset → OAuth login is disabled (the {@code /login} page
     *       still works via form login + JWT);</li>
     *   <li>exactly one set → startup fails with a clear error. A half-
     *       configured client is almost certainly an operator mistake.</li>
     * </ul>
     *
     * <p>The previous defaults (`your-client-id` / `your-client-secret`) were
     * not just placeholders — they were valid-shape values that built a real
     * {@link ClientRegistration} and made the server attempt OAuth handshakes
     * against Google with literal placeholder strings. That's the failure
     * mode this fix removes.
     */
    @Value("${oauth2.google.client-id:}")
    private String googleClientId;

    @Value("${oauth2.google.client-secret:}")
    private String googleClientSecret;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtTokenProvider jwtTokenProvider) throws Exception {
        logger.info("Configuring Spring Security for Fax Trident...");

        // Partial-config check: catch the "set client-id but forgot the secret"
        // (or vice versa) case at startup with a clear message, instead of
        // letting Spring build a half-baked ClientRegistration.
        boolean idSet = googleClientId != null && !googleClientId.isBlank();
        boolean secretSet = googleClientSecret != null && !googleClientSecret.isBlank();
        if (idSet ^ secretSet) {
            throw new IllegalStateException(
                    "OAuth2 Google configuration is partial: " +
                    "oauth2.google.client-id is " + (idSet ? "set" : "missing") + ", " +
                    "oauth2.google.client-secret is " + (secretSet ? "set" : "missing") + ". " +
                    "Set both to enable Google OAuth login, or leave both unset to disable it.");
        }
        boolean oauthEnabled = idSet && secretSet;

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/static/**", "/logout").permitAll()
                .requestMatchers("/api/fax/**").hasRole("USER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/ws/**").authenticated()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtTokenFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/fax", true)
                .permitAll()
                .successHandler((request, response, authentication) -> {
                    String username = authentication.getName();
                    logger.info("User '{}' logged in successfully via form", LogSanitizer.sanitize(username));
                    String token = jwtTokenProvider.createToken(
                        username,
                        authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList()
                    );
                    response.addHeader("Authorization", "Bearer " + token);
                    response.sendRedirect("/fax");
                })
                .failureHandler((request, response, exception) -> {
                    logger.warn("Form login failed: {}", LogSanitizer.sanitize(exception.getMessage()));
                    response.sendRedirect("/login?error");
                })
            );

        if (oauthEnabled) {
            logger.info("OAuth2 Google login enabled (client-id ends with '...{}')",
                    googleClientId.substring(Math.max(0, googleClientId.length() - 4)));
            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/fax", true)
                .clientRegistrationRepository(googleClientRegistrationRepository())
                .successHandler((request, response, authentication) -> {
                    String username = authentication.getName();
                    logger.info("OAuth2 login successful for '{}'", LogSanitizer.sanitize(username));
                    String token = jwtTokenProvider.createToken(
                        username,
                        authentication.getAuthorities().stream().map(a -> a.getAuthority()).toList()
                    );
                    response.addHeader("Authorization", "Bearer " + token);
                    response.sendRedirect("/fax");
                })
            );
        } else {
            logger.info("OAuth2 Google login disabled (oauth2.google.client-id/client-secret not set). " +
                    "Form login and JWT bearer auth remain available.");
        }

        http
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                // JWT revocation runs BEFORE session invalidation so we can
                // still read the Authorization header from the original request.
                .addLogoutHandler(jwtRevocationLogoutHandler(jwtTokenProvider))
                .addLogoutHandler((request, response, authentication) -> {
                    String username = authentication != null ? authentication.getName() : "unknown";
                    logger.info("User '{}' logged out", LogSanitizer.sanitize(username));
                })
                .logoutSuccessHandler(logoutSuccessHandler())
            )
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false)
                .expiredUrl("/login?expired")
            )
            // CSRF: enforce on session-authenticated requests; bypass when a
            // valid-looking Bearer token is present. The hybrid model means
            // both surfaces exist — sessions on browser flows, JWTs on
            // programmatic / desktop flows — and CSRF only protects against
            // the former because that's the surface that rides an ambient
            // cookie. JWT requests, which clients must opt into by attaching
            // a header, are not vulnerable to classic CSRF.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .requireCsrfProtectionMatcher(csrfProtectionMatcher())
            );

        logger.info("Security configuration applied: role-based access, JWT, form login{}",
                oauthEnabled ? ", OAuth2 (Google)" : "");
        return http.build();
    }

    /**
     * CSRF is required for mutating HTTP methods (POST/PUT/DELETE/PATCH)
     * unless the caller presented an {@code Authorization: Bearer …} header.
     * Bearer-bearing requests are JWT-authenticated and immune to CSRF — a
     * cross-site form submission cannot attach the header.
     *
     * <p>Note: this matcher trusts the <i>presence</i> of the header, not its
     * validity. A forged Bearer header skips CSRF but is still rejected by
     * {@link JwtTokenFilter} downstream, which means the worst a CSRF
     * attacker can do is opt out of CSRF and then fail authentication. The
     * goal of CSRF protection is preventing actions on behalf of an
     * authenticated session, and the only way to get that here is via the
     * session cookie path — which we do check.
     */
    @Bean
    public RequestMatcher csrfProtectionMatcher() {
        return request -> {
            String method = request.getMethod();
            boolean mutating = "POST".equals(method)
                    || "PUT".equals(method)
                    || "DELETE".equals(method)
                    || "PATCH".equals(method);
            if (!mutating) {
                return false;
            }
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                return false;
            }
            return true;
        };
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

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return (request, response, authentication) -> {
            String username = authentication != null ? authentication.getName() : "unknown";
            logger.info("Logout successful for '{}', redirecting to login page", LogSanitizer.sanitize(username));
            response.sendRedirect("/login?logout");
        };
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
     * right behavior for logout (the session-cookie side will still be torn
     * down by the default session-invalidation handler).
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

    /**
     * Builds the Google ClientRegistrationRepository. Intentionally NOT a
     * {@code @Bean} — Spring would eagerly instantiate it at context startup
     * regardless of whether OAuth is configured, and {@code ClientRegistration.Builder.build()}
     * rejects blank client IDs. Keeping this as a private helper means the
     * repository is only constructed when {@link #securityFilterChain} has
     * already verified that both credentials are non-blank.
     */
    private ClientRegistrationRepository googleClientRegistrationRepository() {
        logger.info("Configuring OAuth2 client registration for Google...");
        ClientRegistration google = ClientRegistration.withRegistrationId("google")
                .clientId(googleClientId)
                .clientSecret(googleClientSecret)
                .scope("profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .build();
        return new InMemoryClientRegistrationRepository(google);
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

                        org.springframework.security.core.userdetails.User userDetails =
                                (org.springframework.security.core.userdetails.User)
                                        org.springframework.security.core.userdetails.User.builder()
                                                .username(username)
                                                .password("")
                                                .roles(roles.toArray(new String[0]))
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
