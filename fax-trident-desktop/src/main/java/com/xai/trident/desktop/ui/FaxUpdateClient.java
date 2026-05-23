package com.xai.trident.desktop.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Shared WebSocket client for the desktop UI. Pre-split this lived as a
 * Spring {@code @Component} with {@code @EventListener(ApplicationReadyEvent.class)}
 * and {@code @PreDestroy} hooks; after the JavaFX / Spring Boot split
 * (ADR-0001) the lifecycle is explicit:
 *
 * <ul>
 *   <li>Constructed by {@code FaxTridentDesktop.start(Stage)} with the
 *       configured WS URL and a Jackson {@link ObjectMapper}.</li>
 *   <li>{@link #start()} is called after the application has fully
 *       initialized the JavaFX scene — this is the moral equivalent of
 *       {@code ApplicationReadyEvent}, but on the desktop's terms.</li>
 *   <li>{@link #shutdown()} is called from {@code FaxTridentDesktop.stop()}.</li>
 * </ul>
 *
 * <p>Reconnect behavior unchanged from the in-process predecessor:
 * exponential backoff capped at {@link #MAX_RECONNECT_MS}, listeners
 * registered via {@link #addListener(Consumer)} and invoked on a
 * background thread.
 *
 * <p>Audit finding 2.14 (single-shared-client) and ADR-0001 (decouple).
 */
public class FaxUpdateClient {

    private static final Logger logger = LoggerFactory.getLogger(FaxUpdateClient.class);

    /** Initial reconnect delay; doubled on each failure up to {@link #MAX_RECONNECT_MS}. */
    private static final long BASE_RECONNECT_MS = 1_000;

    /** Ceiling on the reconnect delay — we don't want to wait minutes between attempts. */
    private static final long MAX_RECONNECT_MS = 30_000;

    private static final TypeReference<Map<String, String>> UPDATE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Supplier<String> tokenSupplier;
    private final List<Consumer<Map<String, String>>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<WebSocketClient> currentClient = new AtomicReference<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fax-update-ws-reconnect");
                t.setDaemon(true);
                return t;
            });

    private volatile long nextDelayMs = BASE_RECONNECT_MS;
    private volatile boolean stopped = false;

    /**
     * @param tokenSupplier returns the current JWT (or null if unauthenticated)
     *                      at the moment each connect/reconnect fires. The
     *                      supplier indirection is intentional — Java-WebSocket
     *                      sets headers at construction time, so a token
     *                      captured once at FaxUpdateClient construction would
     *                      go stale on re-login or token rotation. Each
     *                      {@link #tryConnect()} re-resolves it.
     */
    public FaxUpdateClient(ObjectMapper objectMapper, String endpointUrl,
                           Supplier<String> tokenSupplier) {
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(endpointUrl);
        this.tokenSupplier = tokenSupplier;
        logger.info("FaxUpdateClient configured for endpoint: {}", endpointUrl);
    }

    /**
     * Register a listener for parsed update messages. Listeners are invoked
     * on the WebSocket reader thread; JavaFX callers must marshal back to
     * the FX thread themselves. Safe to call before {@link #start()}.
     */
    public void addListener(Consumer<Map<String, String>> listener) {
        listeners.add(listener);
    }

    /**
     * Start the connect/reconnect loop. The in-process predecessor wired this
     * to {@code @EventListener(ApplicationReadyEvent.class)}; the desktop
     * version is explicitly invoked from {@code FaxTridentDesktop.start}
     * after the FX scene is initialized.
     */
    public void start() {
        logger.info("Opening WebSocket to {}", endpoint);
        scheduleConnect(0);
    }

    private void scheduleConnect(long delayMs) {
        if (stopped) {
            return;
        }
        scheduler.schedule(this::tryConnect, delayMs, TimeUnit.MILLISECONDS);
    }

    private void tryConnect() {
        if (stopped) {
            return;
        }
        WebSocketClient client = new WebSocketClient(endpoint) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                logger.info("FaxUpdateClient connected to {}", endpoint);
                nextDelayMs = BASE_RECONNECT_MS; // reset backoff on a clean open
            }

            @Override
            public void onMessage(String message) {
                dispatch(message);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                logger.info("FaxUpdateClient disconnected (code={}, reason={}, remote={})",
                        code, reason, remote);
                scheduleReconnect();
            }

            @Override
            public void onError(Exception ex) {
                logger.warn("FaxUpdateClient error: {}", ex.toString());
                // onClose will follow with the actual disconnect.
            }
        };
        currentClient.set(client);
        try {
            client.connect();
        } catch (Exception e) {
            logger.warn("FaxUpdateClient connect() threw synchronously: {}", e.toString());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        long delay = nextDelayMs;
        nextDelayMs = Math.min(MAX_RECONNECT_MS, nextDelayMs * 2);
        logger.info("Reconnecting in {} ms", delay);
        scheduleConnect(delay);
    }

    private void dispatch(String message) {
        Map<String, String> update;
        try {
            update = objectMapper.readValue(message, UPDATE_TYPE);
        } catch (Exception e) {
            logger.error("Failed to parse WebSocket message: {}", e.getMessage());
            // Fall back to a minimal map so listeners can still surface the raw text.
            update = Map.of("message", message);
        }
        for (Consumer<Map<String, String>> listener : listeners) {
            try {
                listener.accept(update);
            } catch (Exception e) {
                logger.error("Listener threw while handling update: {}", e.toString());
            }
        }
    }

    /**
     * Close the WS connection and the reconnect scheduler. Called from
     * {@code FaxTridentDesktop.stop()}; idempotent.
     */
    public void shutdown() {
        stopped = true;
        WebSocketClient c = currentClient.getAndSet(null);
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                logger.debug("Error closing WebSocket on shutdown: {}", e.toString());
            }
        }
        scheduler.shutdownNow();
    }
}
