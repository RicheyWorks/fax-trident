package com.xai.trident.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a fixed-window rate limit on a controller (or any Spring-managed)
 * method. Enforced at runtime by {@link RateLimitAspect}, which counts hits
 * in Redis and rejects callers who exceed {@link #rate()} requests inside
 * {@link #period()} seconds.
 *
 * <p>The {@link #key()} value is a SpEL template (Spring's {@code #{ ... }}
 * syntax) evaluated against:
 * <ul>
 *   <li>the method's parameters (referenced by name, e.g. {@code #faxNumber}),</li>
 *   <li>{@code #authentication} — the current
 *       {@link org.springframework.security.core.Authentication}
 *       from {@link org.springframework.security.core.context.SecurityContextHolder}
 *       (may be {@code null} for anonymous calls),</li>
 *   <li>{@code #request} — the current {@code HttpServletRequest} (useful
 *       for raw access to headers / path),</li>
 *   <li>{@code #ipAddress} — convenience equivalent to
 *       {@code #request.remoteAddr}; use this for per-IP rate-limiting on
 *       unauthenticated endpoints like {@code /api/auth/login}.</li>
 * </ul>
 *
 * <p>Examples:
 * <pre>{@code
 *   // Authenticated, per-user
 *   @PostMapping("/send")
 *   @RateLimit(key = "fax:send:#{authentication.name}", rate = 10, period = 60)
 *   public ResponseEntity<?> send(...) { ... }
 *
 *   // Unauthenticated, per-IP (brute-force protection)
 *   @PostMapping("/login")
 *   @RateLimit(key = "auth:login:#{#ipAddress}", rate = 10, period = 60)
 *   public ResponseEntity<?> login(...) { ... }
 * }</pre>
 *
 * <p><b>X-Forwarded-For caveat:</b> {@code #ipAddress} resolves to
 * {@code HttpServletRequest.getRemoteAddr()}, which is the TCP peer's address
 * — i.e. the upstream proxy when one sits in front of the app. Deployments
 * behind a trusted load balancer should set
 * {@code server.forward-headers-strategy: native} (or {@code framework}) so
 * Spring Boot rewrites {@code getRemoteAddr()} based on the
 * {@code Forwarded} / {@code X-Forwarded-For} headers. The aspect deliberately
 * does NOT parse those headers itself — doing so without knowing where the
 * trust boundary sits would let any caller spoof an IP by setting the header.
 *
 * <p>Callers that exceed the limit get a {@link RateLimitExceededException},
 * which Spring serializes as an HTTP 429 (Too Many Requests).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface RateLimit {

    /**
     * SpEL template producing the bucket key, e.g.
     * {@code "fax:send:#{authentication.name}"}. The aspect prepends a
     * {@code "ratelimit:"} namespace before writing to Redis.
     */
    String key();

    /** Maximum number of permitted invocations per {@link #period()}. Must be {@code > 0}. */
    int rate();

    /** Window length in seconds. Must be {@code > 0}. */
    int period();
}
