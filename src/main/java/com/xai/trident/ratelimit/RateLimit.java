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
 *   <li>the method's parameters (referenced by name, e.g. {@code #request}),</li>
 *   <li>{@code #authentication} — the current
 *       {@link org.springframework.security.core.Authentication}
 *       from {@link org.springframework.security.core.context.SecurityContextHolder}
 *       (may be {@code null} for anonymous calls).</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 *   @PostMapping("/send")
 *   @RateLimit(key = "fax:send:#{authentication.name}", rate = 10, period = 60)
 *   public ResponseEntity<?> send(...) { ... }
 * }</pre>
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
