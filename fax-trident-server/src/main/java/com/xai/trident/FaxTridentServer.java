package com.xai.trident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entry point for the fax-trident server.
 *
 * <p>Replaces the pre-split {@code FaxTridentApplication}, which extended
 * {@code javafx.application.Application} and started both the Spring
 * context and the JavaFX UI in one JVM. After ADR-0001, the desktop UI
 * is in its own module ({@code fax-trident-desktop}) and talks to this
 * server over HTTP + WebSocket like any other client.
 *
 * <p>Consequences that fell out of the split:
 * <ul>
 *   <li>No more {@code extends Application}, no {@code init()}, no
 *       {@code start(Stage)}, no {@code stop()}. The class is a plain
 *       {@code @SpringBootApplication} with a one-line {@code main}.</li>
 *   <li>No more {@code springContext.getAutowireCapableBeanFactory()
 *       .autowireBean(this)} dance — the JavaFX-reflectively-instantiated
 *       instance problem (audit 2.2) is gone with the JavaFX coupling.</li>
 *   <li>The "Total faxes logged at startup" diagnostic that the
 *       pre-split {@code start(Stage)} emitted via the JavaFX-managed
 *       {@code FaxLogRepository} is dropped. It was nice-to-have and not
 *       worth resurrecting as an ApplicationReadyEvent listener.</li>
 *   <li>{@code java.awt.headless=true} on JVM args is no longer required
 *       for the server-only Docker container; nothing in the server
 *       module touches AWT or JavaFX.</li>
 * </ul>
 */
@SpringBootApplication
@EnableAsync
@EnableRetry
@EnableScheduling
@EnableAspectJAutoProxy
public class FaxTridentServer {

    private static final Logger logger = LoggerFactory.getLogger(FaxTridentServer.class);

    public static void main(String[] args) {
        logger.info("Launching fax-trident server (Spring Boot)...");
        SpringApplication.run(FaxTridentServer.class, args);
    }
}
