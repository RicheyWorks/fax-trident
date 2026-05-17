package com.xai.trident.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared WebSocket client for the desktop UI. Previously {@link MainView} and
 * {@link PreviewPane} each constructed their own {@link WebSocketClient}
 * connecting to {@code /fax-updates} inside {@code @PostConstruct}, which:
 *
 * <ul>
 *   <li>opened two TCP connections (and counted as two clients on the server's
 *       handler) for every desktop run;</li>
 *   <li>fired before the embedded Tomcat was guaranteed to be accepting
 *       upgrades, so the first attempt frequently failed silently;</li>
 *   <li>had no reconnect logic — one transient failure and the UI was deaf
 *       to broadcasts for the rest of the session.</li>
 * </ul>
 *
 * <p>This class owns a single shared client, connects on
 * {@link ApplicationReadyEvent} (after Tomcat reports ready), and reconnects
 * with exponential backoff capped at {@link #MAX_RECONNECT_MS}. UI components
 * register listeners via {@link #addListener(Consumer)} and receive parsed
 * update maps on a background thread — callers that need the JavaFX thread
 * should wrap their handler in {@code Platform.runLater(...)}.
 *
 * <p>Audit finding 2.14.
 */
@Component
public class FaxUpdateClient {

    private static final Logger logger = LoggerFactory.getLogger(FaxUpdateClient.class);

    /** Initial reconnect delay; doubled on each failure up to {@link #MAX_RECONNECT_MS}. */
    private static final long BASE_RECONNECT_MS = 1_000;

    /** Ceiling on the reconnect delay — we don't want to wait minutes between attempts. */
    private static final long MAX_RECONNECT_MS = 30_000;

    /**
     * Reusable Jackson type token for the message shape. We deliberately
     * read the incoming JSON as {@code Map<String,String>} because the
     * legacy handlers in MainView / PreviewPane did the same — keeping
     * the shape unchanged means listeners don't need to be rewritten.
     */
    private static final TypeReference<Map<String, String>> UPDATE_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final URI endpoint;
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

    public FaxUpdateClient(
            ObjectMapper objectMapper,
            @Value("${app.websocket.client-url:ws://localhost:8080/fax-updates}") String endpointUrl) {
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(endpointUrl);
        logger.info("FaxUpdateClient configured for endpoint: {}", endpointUrl);
    }

    /**
     * Register a listener for parsed update messages. Listeners are invoked
     * on the WebSocket reader thread; JavaFX callers must marshal back to
     * the FX thread themselves. Safe to call before
     * {@link ApplicationReadyEvent} fires.
     */
    public void addListener(Consumer<Map<String, String>> listener) {
        listeners.add(listener);
    }

    /**
     * Connect once Spring reports the application context fully started.
     * {@code ApplicationReadyEvent} fires AFTER Tomcat begins accepting
     * connections, which is the property the original {@code @PostConstruct}
     * version was missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectOnReady() {
        logger.info("Application ready; opening WebSocket to {}", endpoint);
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

    @PreDestroy
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
