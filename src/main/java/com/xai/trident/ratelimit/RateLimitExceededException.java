package com.xai.trident.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link RateLimitAspect} when the caller exceeds the configured
 * window. The {@link ResponseStatus} mapping causes Spring MVC's default
 * exception resolver to return HTTP 429 (Too Many Requests) with the
 * exception message in the body.
 *
 * <p>If you wire up a custom {@code @ControllerAdvice} later, this is the
 * class to look for to add a {@code Retry-After} header.
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {

    private final String resolvedKey;
    private final int rate;
    private final int periodSeconds;

    public RateLimitExceededException(String resolvedKey, int rate, int periodSeconds) {
        super("Rate limit exceeded for '" + resolvedKey + "': maximum "
                + rate + " requests per " + periodSeconds + " seconds");
        this.resolvedKey = resolvedKey;
        this.rate = rate;
        this.periodSeconds = periodSeconds;
    }

    public String getResolvedKey() {
        return resolvedKey;
    }

    public int getRate() {
        return rate;
    }

    public int getPeriodSeconds() {
        return periodSeconds;
    }
}
