package com.xai.trident.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configures the executor that backs Spring's {@link
 * org.springframework.scheduling.annotation.Async @Async} methods.
 *
 * <p>Two problems we are fixing here:
 *
 * <ol>
 *   <li><b>SecurityContext propagation.</b> Spring's default async executor
 *       ({@code SimpleAsyncTaskExecutor}) does <em>not</em> copy
 *       {@link org.springframework.security.core.context.SecurityContextHolder}
 *       into the worker thread. As a result, every @Async fax operation —
 *       and every {@code @PrePersist} hook that reads the current principal
 *       — sees {@code Authentication == null} and the audit trail records
 *       the call as {@code "system"} instead of the real user.
 *
 *       <p>We wrap a real {@link ThreadPoolTaskExecutor} in a
 *       {@link DelegatingSecurityContextAsyncTaskExecutor}, which snapshots
 *       the current {@code SecurityContext} on the calling thread at task
 *       submission and restores it on the worker thread for the duration of
 *       the task. This is preferred over
 *       {@code MODE_INHERITABLETHREADLOCAL} because pooled threads survive
 *       across many requests and would otherwise leak the principal of the
 *       caller who happened to warm them.</li>
 *
 *   <li><b>Backpressure.</b> The default executor spawns a fresh thread per
 *       task with no upper bound. Under a flood of {@code sendFaxAsync}
 *       calls that's a path to OOM. A bounded queue plus
 *       {@link ThreadPoolExecutor.CallerRunsPolicy} pushes work back onto
 *       the caller when the system is overloaded, which is the right
 *       behavior for fax send — slow down, don't drop.</li>
 * </ol>
 *
 * <p>The pool sizes below are conservative defaults sized for a single-node
 * dev/test deployment. Production should externalize them via
 * {@code @ConfigurationProperties} once load characteristics are known.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    /** Always-on worker count. Sized to match common small-server CPU counts. */
    private static final int CORE_POOL_SIZE = 4;

    /** Hard cap on workers. Reached only when the queue is full. */
    private static final int MAX_POOL_SIZE = 16;

    /** How many submitted-but-not-yet-running tasks we will buffer before applying back-pressure. */
    private static final int QUEUE_CAPACITY = 100;

    /** Prefix used for thread names — helpful in stack traces and `jstack` output. */
    private static final String THREAD_NAME_PREFIX = "fax-async-";

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(CORE_POOL_SIZE);
        pool.setMaxPoolSize(MAX_POOL_SIZE);
        pool.setQueueCapacity(QUEUE_CAPACITY);
        pool.setThreadNamePrefix(THREAD_NAME_PREFIX);

        // CallerRunsPolicy: if the queue is full, the submitting thread runs
        // the task itself. Slower than offloading, but never drops a fax.
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Wait for in-flight tasks during graceful shutdown so we don't kill
        // a send mid-transaction.
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);

        pool.initialize();

        logger.info("Configured async executor: core={}, max={}, queue={}, prefix='{}'",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY, THREAD_NAME_PREFIX);

        // The wrapper is what actually fixes the SecurityContext propagation
        // bug. It delegates submit/execute through to `pool` after capturing
        // the caller's SecurityContext for the worker thread.
        return new DelegatingSecurityContextAsyncTaskExecutor(pool);
    }

    /**
     * Spring silently swallows exceptions thrown by {@code void}-returning
     * @Async methods. Wiring up a handler turns those into log entries so
     * they actually show up in incident postmortems.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> logger.error(
                "Uncaught exception from @Async method {}.{}({}): {}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                Arrays.toString(params),
                ex.getMessage(),
                ex);
    }
}
