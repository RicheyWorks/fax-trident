package com.xai.trident.ratelimit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.expression.Expression;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * Enforces {@link RateLimit} using a fixed-window counter stored in Redis.
 *
 * <p>Algorithm (per call):
 * <ol>
 *   <li>Resolve the SpEL {@code key} template against the join point's
 *       arguments and the current {@link Authentication}.</li>
 *   <li>{@code INCR ratelimit:<resolved-key>} in Redis.</li>
 *   <li>If the returned counter is {@code 1}, set the key's TTL to
 *       {@code period} seconds (first hit of a new window).</li>
 *   <li>If the counter exceeds {@code rate}, throw
 *       {@link RateLimitExceededException} — Spring maps it to HTTP 429.</li>
 * </ol>
 *
 * <p><b>Trade-off:</b> a fixed window allows up to {@code 2 * rate} requests
 * across the boundary of two adjacent windows. That's the classic accuracy
 * vs. simplicity trade-off and matches the original annotation's intent
 * (which named {@code rate} and {@code period}, not "burst" and "refill").
 * Swap in a sliding-log or token-bucket later if you need stricter limits.
 *
 * <p><b>Order:</b> set to {@link Ordered#LOWEST_PRECEDENCE} so Spring
 * Security's method-level interceptors (e.g. {@code @PreAuthorize}) run
 * <em>before</em> us — which means the {@code Authentication} is already
 * resolved when we evaluate {@code #authentication}, and unauthorized
 * callers are rejected before consuming any rate-limit budget.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class RateLimitAspect {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitAspect.class);

    /** Namespace prefix for every key we write, so rate-limit data is easy to scan/purge. */
    private static final String KEY_NAMESPACE = "ratelimit:";

    /** Treats the {@code key} value as a SpEL template: literal text with {@code #{...}} interpolations. */
    private static final ParserContext SPEL_TEMPLATE_CTX = new TemplateParserContext("#{", "}");

    /**
     * SpEL parsers are thread-safe and the docs encourage reuse. Expressions
     * themselves could be cached per-method too, but the controllers we apply
     * this to are not hot enough to make that complexity worthwhile yet.
     */
    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    private final RedisTemplate<String, Object> redisTemplate;

    public RateLimitAspect(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Around("@annotation(rateLimit)")
    public Object enforce(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        if (rateLimit.rate() <= 0 || rateLimit.period() <= 0) {
            // Misconfiguration — fail loudly so the operator sees it during the first request.
            throw new IllegalStateException(
                    "@RateLimit on " + joinPoint.getSignature().toShortString()
                            + " has invalid rate=" + rateLimit.rate()
                            + " or period=" + rateLimit.period() + " (both must be > 0)");
        }

        String resolvedKey = resolveKey(joinPoint, rateLimit.key());
        String redisKey = KEY_NAMESPACE + resolvedKey;

        Long count;
        try {
            count = redisTemplate.opsForValue().increment(redisKey);
            // Set TTL only on the first hit of a window — re-applying it on every
            // call would keep extending the window and break the rate limit.
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(rateLimit.period()));
            }
        } catch (Exception e) {
            // Fail-open: if Redis is unavailable we'd rather serve the request than
            // 500 every API call. The error is logged so it's visible in dashboards.
            logger.error("Rate-limit check skipped for '{}' due to Redis error: {}", resolvedKey, e.getMessage());
            return joinPoint.proceed();
        }

        if (count != null && count > rateLimit.rate()) {
            logger.warn("Rate limit exceeded for '{}': {} hits in current {}s window (max {})",
                    resolvedKey, count, rateLimit.period(), rateLimit.rate());
            throw new RateLimitExceededException(resolvedKey, rateLimit.rate(), rateLimit.period());
        }

        return joinPoint.proceed();
    }

    /**
     * Evaluates the SpEL template against the join point's arguments and
     * the current SecurityContext. Falls back to the literal template (with
     * a warning) if anything in evaluation throws — better to over-rate-limit
     * a single shared bucket than crash the endpoint.
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, String keyTemplate) {
        MethodSignature sig = (MethodSignature) joinPoint.getSignature();
        Method method = sig.getMethod();

        StandardEvaluationContext ctx = new StandardEvaluationContext();

        // Method args by name: e.g. @RateLimit(key = "...#{request.faxNumber}...")
        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                ctx.setVariable(paramNames[i], args[i]);
            }
        }

        // Spring Security principal — the most common variable used in rate-limit keys.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ctx.setVariable("authentication", auth);

        try {
            Expression expr = parser.parseExpression(keyTemplate, SPEL_TEMPLATE_CTX);
            Object value = expr.getValue(ctx);
            return value == null ? "anonymous" : value.toString();
        } catch (Exception e) {
            logger.warn("Failed to evaluate @RateLimit key '{}' on {}: {} — falling back to literal key",
                    keyTemplate, sig.toShortString(), e.getMessage());
            return keyTemplate;
        }
    }
}
