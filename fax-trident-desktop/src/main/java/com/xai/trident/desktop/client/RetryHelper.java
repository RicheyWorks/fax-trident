package com.xai.trident.desktop.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Tiny retry helper that replaces Spring Retry's {@code @Retryable} /
 * {@code @Recover} annotations on the desktop side.
 *
 * <p>Why hand-rolled? After the JavaFX / Spring Boot split (ADR-0001) the
 * desktop module is Spring-free. Spring Retry depends on Spring AOP, which
 * depends on a Spring context, which is exactly the dependency the ADR
 * removes. The retry semantics we actually rely on — N attempts, fixed
 * backoff, recover on exhaustion — fit in ~50 lines.
 *
 * <p>Used by {@link com.xai.trident.desktop.ui.PreviewPane} for the PDF
 * load path, which was previously {@code @Retryable(maxAttempts=3,
 * backoff=@Backoff(delay=1000), value=IOException.class)} with a
 * {@code @Recover} that updated the UI.
 *
 * <p>Calls block the calling thread between attempts. The PreviewPane
 * uses this from a background executor, so the JavaFX thread is never
 * blocked.
 */
public final class RetryHelper {

    private static final Logger logger = LoggerFactory.getLogger(RetryHelper.class);

    private RetryHelper() {
        // utility class
    }

    /**
     * Run {@code task} up to {@code maxAttempts} times, sleeping
     * {@code baseDelayMs} milliseconds (plus jitter) between attempts.
     * Returns the result of the first successful attempt.
     *
     * <p>If every attempt throws {@link IOException}, the most recent
     * exception is rethrown. Any other {@link RuntimeException} fails
     * immediately — we don't retry on programming errors.
     *
     * @param task          the unit of work; may throw IOException
     * @param maxAttempts   total attempts including the first; must be &ge; 1
     * @param baseDelayMs   fixed inter-attempt delay; 0 disables sleep
     * @param description   short label used in log messages ("loadDocument", "uploadPdf"...)
     */
    public static <T> T withRetry(Callable<T> task, int maxAttempts, long baseDelayMs, String description)
            throws IOException {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got " + maxAttempts);
        }
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T result = task.call();
                if (attempt > 1) {
                    logger.info("[{}] succeeded on attempt {}/{}", description, attempt, maxAttempts);
                }
                return result;
            } catch (IOException e) {
                lastFailure = e;
                logger.warn("[{}] attempt {}/{} failed: {}",
                        description, attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    break;
                }
                sleep(baseDelayMs);
            } catch (RuntimeException e) {
                // Don't retry on programming errors. Bubble up immediately.
                throw e;
            } catch (Exception e) {
                // Callable.call() declares `throws Exception`; we only
                // contract IOException retries. Anything else (checked
                // exceptions the caller chose to surface) is wrapped.
                throw new IOException("Non-IO checked exception in retried task '" + description + "'", e);
            }
        }
        // Out of attempts.
        logger.error("[{}] exhausted {} attempt(s); rethrowing last failure", description, maxAttempts);
        throw lastFailure != null
                ? lastFailure
                : new IOException("Retried task '" + description + "' failed with no captured exception");
    }

    /**
     * Sleep with small jitter (+/- 25%) so concurrent retries from
     * multiple call sites don't bunch up. Caller's interrupt is restored.
     */
    private static void sleep(long baseDelayMs) {
        if (baseDelayMs <= 0) {
            return;
        }
        long jitter = (long) (baseDelayMs * 0.25);
        long delay = baseDelayMs + ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        try {
            Thread.sleep(Math.max(0, delay));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
