package com.xai.trident.controller;

import com.xai.trident.config.SecurityConfig.JwtTokenProvider;
import com.xai.trident.ratelimit.RateLimit;
import com.xai.trident.util.LogSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Issues JWTs for username/password credentials. This is the single sanctioned
 * entry point into the auth system now that form login + sessions have been
 * removed (audit tech-debt #5; closes findings 1.3 and 1.4 by elimination).
 *
 * <p>Clients POST {@code {"username": "...", "password": "..."}} and get back
 * {@code {"token": "..."}}, then attach the token as
 * {@code Authorization: Bearer <token>} on subsequent requests. Logout is
 * {@code POST /logout} with the bearer header — the JWT's jti is removed from
 * the Redis allowlist and any further use of the token is rejected.
 *
 * <p>Brute-force protection: the endpoint is rate-limited per client IP via
 * {@link RateLimit} (10 attempts per 60s window). The aspect was extended to
 * expose {@code #ipAddress} for unauthenticated endpoints — see the
 * {@code RateLimit} javadoc for the X-Forwarded-For trust caveat. Operators
 * behind a load balancer must set {@code server.forward-headers-strategy:
 * native} so the rate-limit key actually keys on the originating client and
 * not the proxy.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenProvider jwtTokenProvider) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    @RateLimit(key = "auth:login:#{#ipAddress}", rate = 10, period = 60)
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));

            List<String> roles = authentication.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .toList();

            String token = jwtTokenProvider.createToken(authentication.getName(), roles);
            logger.info("Issued JWT for user '{}'", LogSanitizer.sanitize(authentication.getName()));
            return ResponseEntity.ok(Map.of("token", token));
        } catch (BadCredentialsException e) {
            // Don't surface "user not found" vs. "wrong password" to the
            // caller — that's a username-enumeration oracle.
            logger.warn("Login failed for '{}': bad credentials",
                    LogSanitizer.sanitize(request.username()));
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }
    }

    public record LoginRequest(
            @NotBlank(message = "username is required") String username,
            @NotBlank(message = "password is required") String password) {
    }
}
