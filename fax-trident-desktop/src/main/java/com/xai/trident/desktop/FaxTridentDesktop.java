package com.xai.trident.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.trident.desktop.client.FaxApiClient;
import com.xai.trident.desktop.config.DesktopPreferences;
import com.xai.trident.desktop.ui.FaxUpdateClient;
import com.xai.trident.desktop.ui.LoginDialog;
import com.xai.trident.desktop.ui.MainView;
import com.xai.trident.desktop.ui.PreviewPane;
import com.xai.trident.desktop.ui.ThemeManager;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * JavaFX entry point for the Fax Trident desktop client. New after
 * the JavaFX / Spring Boot split (ADR-0001); supersedes the old
 * {@code FaxTridentApplication} which played both roles.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #start(Stage)} loads preferences and shows the {@link LoginDialog}.</li>
 *   <li>On login success, the dialog returns a configured
 *       {@link FaxApiClient}. The rest of the UI is then wired up against
 *       that client.</li>
 *   <li>{@link FaxUpdateClient} connects to the server's WebSocket and
 *       starts its reconnect loop.</li>
 *   <li>A short splash fades into the main view.</li>
 *   <li>{@link #stop()} closes the WS client and revokes the JWT.</li>
 * </ol>
 *
 * <p>No Spring context. The desktop is now a plain JavaFX app; dependency
 * wiring is constructor injection by hand inside {@link #start(Stage)}.
 */
public class FaxTridentDesktop extends Application {

    private static final Logger logger = LoggerFactory.getLogger(FaxTridentDesktop.class);

    private static final int DEFAULT_WIDTH = 1200;
    private static final int DEFAULT_HEIGHT = 800;
    private static final String STARTUP_SOUND_PATH = "/sounds/trident-rise.wav";

    private DesktopPreferences prefs;
    private FaxApiClient apiClient;
    private FaxUpdateClient updateClient;
    private MediaPlayer startupSoundPlayer;

    public static void main(String[] args) {
        logger.info("Launching Fax Trident desktop...");
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        logger.info("Starting JavaFX UI");

        ObjectMapper json = new ObjectMapper();
        this.prefs = new DesktopPreferences();

        // 1. Login. If the user cancels, exit cleanly.
        LoginDialog login = new LoginDialog(prefs, json);
        Optional<FaxApiClient> loggedIn = login.showAndAwait();
        if (loggedIn.isEmpty()) {
            logger.info("Login cancelled by user; exiting.");
            Platform.exit();
            return;
        }
        this.apiClient = loggedIn.get();
        logger.info("Logged in as '{}'", apiClient.currentUsername());

        // 2. Wire up the UI. ThemeManager / PreviewPane / FaxUpdateClient
        //    used to be @Component beans; after the split they're plain
        //    classes constructed here.
        ThemeManager themeManager = new ThemeManager(prefs);
        String wsUrl = prefs.get(DesktopPreferences.KEY_SERVER_WS_URL,
                deriveWebSocketUrl(prefs.get(DesktopPreferences.KEY_SERVER_BASE_URL,
                        "http://localhost:8080")));
        // Method reference, not a captured string: FaxApiClient swaps the
        // token on re-login, and FaxUpdateClient re-reads the supplier on
        // every (re)connect attempt so the WS handshake always carries the
        // current bearer. (AUDIT follow-up: WebSocket bearer auth.)
        this.updateClient = new FaxUpdateClient(json, wsUrl, apiClient::getToken);
        PreviewPane previewPane = new PreviewPane(themeManager, updateClient);
        MainView mainView = new MainView(apiClient, previewPane, themeManager, updateClient);

        // Hook listeners are registered in the constructors above; now start
        // the actual WebSocket connection (was @EventListener(ApplicationReadyEvent.class)
        // in the in-process world; here it's an explicit lifecycle hook).
        updateClient.start();

        // 3. Set up the main scene with a short splash.
        Scene scene = new Scene(mainView.getView(), DEFAULT_WIDTH, DEFAULT_HEIGHT);
        themeManager.applyMermaidTheme(scene);

        StackPane splashPane = new StackPane(new Label("Fax Trident — signed in as " + apiClient.currentUsername()));
        splashPane.setStyle("-fx-background-color: #00ced1; -fx-alignment: center; -fx-font-size: 24px;");
        Scene splashScene = new Scene(splashPane, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        themeManager.applyMermaidTheme(splashScene);
        FadeTransition fadeOut = new FadeTransition(Duration.seconds(2), splashPane);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            primaryStage.setScene(scene);
            logger.info("Splash faded; main UI loaded.");
        });

        primaryStage.setTitle("Fax Trident — Desktop");
        primaryStage.setScene(splashScene);
        primaryStage.setOnCloseRequest(e -> {
            logger.info("User requested shutdown...");
            shutdown();
        });
        primaryStage.show();

        playStartupSound();
        fadeOut.play();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        logger.info("Shutting down Fax Trident desktop...");
        if (startupSoundPlayer != null) {
            try { startupSoundPlayer.stop(); startupSoundPlayer.dispose(); }
            catch (Exception e) { logger.debug("Sound shutdown: {}", e.getMessage()); }
        }
        if (updateClient != null) {
            try { updateClient.shutdown(); }
            catch (Exception e) { logger.debug("WS client shutdown: {}", e.getMessage()); }
        }
        if (apiClient != null) {
            try { apiClient.logout(); }
            catch (Exception e) { logger.debug("Logout: {}", e.getMessage()); }
        }
        Platform.exit();
    }

    private void playStartupSound() {
        try {
            String soundResource = Objects.requireNonNull(
                    getClass().getResource(STARTUP_SOUND_PATH),
                    "Sound file not found: " + STARTUP_SOUND_PATH).toExternalForm();
            Media media = new Media(soundResource);
            startupSoundPlayer = new MediaPlayer(media);
            startupSoundPlayer.setVolume(0.5);
            startupSoundPlayer.setOnError(() -> logger.warn("Startup sound error: {}",
                    startupSoundPlayer.getError().getMessage()));
            startupSoundPlayer.setOnEndOfMedia(() -> {
                startupSoundPlayer.dispose();
                logger.debug("Startup sound finished and disposed");
            });
            startupSoundPlayer.play();
        } catch (Exception e) {
            // Same behavior as before: the shipped wav is a 3-byte sentinel
            // placeholder; log and move on. The UI is the deliverable, not
            // the chime.
            logger.warn("Failed to play startup sound: {}", e.getMessage());
        }
    }

    /**
     * Derive the WS URL from the HTTP base URL by swapping the scheme and
     * appending {@code /fax-updates}. Operators who run the WS endpoint on
     * a different host can override via the {@code server.wsUrl} preference.
     */
    private static String deriveWebSocketUrl(String httpBaseUrl) {
        if (httpBaseUrl == null || httpBaseUrl.isBlank()) {
            return "ws://localhost:8080/fax-updates";
        }
        String trimmed = httpBaseUrl.endsWith("/")
                ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1)
                : httpBaseUrl;
        if (trimmed.startsWith("https://")) {
            return "wss://" + trimmed.substring("https://".length()) + "/fax-updates";
        }
        if (trimmed.startsWith("http://")) {
            return "ws://" + trimmed.substring("http://".length()) + "/fax-updates";
        }
        return trimmed + "/fax-updates";
    }
}
