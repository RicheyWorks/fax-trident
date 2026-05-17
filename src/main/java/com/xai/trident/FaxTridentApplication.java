package com.xai.trident;

import com.xai.trident.config.WebSocketConfig.FaxUpdateHandler;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.ui.MainView;
import com.xai.trident.ui.ThemeManager;
import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@SpringBootApplication
@EnableAsync
@EnableRetry
@EnableScheduling
@EnableAspectJAutoProxy
public class FaxTridentApplication extends Application {

    private static final Logger logger = LoggerFactory.getLogger(FaxTridentApplication.class);
    private static ConfigurableApplicationContext springContext;
    private static String[] args;
    private MediaPlayer startupSoundPlayer;

    // Configuration properties
    @Value("${app.sound.startup:/sounds/trident-rise.wav}")
    private String startupSoundPath;

    @Value("${app.window.width:1200}")
    private int windowWidth;

    @Value("${app.window.height:800}")
    private int windowHeight;

    // Autowired dependencies
    @Autowired
    private MainView mainView;

    @Autowired
    private ThemeManager themeManager;

    @Autowired
    private FaxUpdateHandler faxUpdateHandler;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private FaxLogRepository faxLogRepository;

    @Autowired
    private FaxEngineService faxEngineService;

    public static void main(String[] args) {
        FaxTridentApplication.args = args;
        logger.info("Launching Fax Trident application...");
        launch(args);
    }

    @Override
    public void init() throws Exception {
        logger.info("Initializing Spring Boot context...");
        springContext = SpringApplication.run(FaxTridentApplication.class, args);

        // ---------------------------------------------------------------
        // CRITICAL: JavaFX's Application.launch() constructs THIS class
        // itself via reflection — Spring never sees that instance, so the
        // @Autowired fields below (mainView, themeManager, faxUpdateHandler,
        // redisTemplate, faxLogRepository, faxEngineService) would be null
        // when start(Stage) ran. Spring DOES separately create a
        // FaxTridentApplication bean (because @SpringBootApplication implies
        // @Configuration/@Component), but that bean is a different object
        // than the one JavaFX is about to call.
        //
        // autowireBean(this) populates the @Autowired members on the
        // JavaFX-managed instance using the Spring context we just built.
        // Without this line the defensive null-checks in start(Stage) trip
        // every time and the app refuses to boot.
        // ---------------------------------------------------------------
        springContext.getAutowireCapableBeanFactory().autowireBean(this);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        logger.info("Starting JavaFX UI with Mermaid Mode...");

        // Verify critical beans
        try {
            if (mainView == null) throw new IllegalStateException("MainView bean is null");
            if (themeManager == null) throw new IllegalStateException("ThemeManager bean is null");
            if (faxUpdateHandler == null) throw new IllegalStateException("FaxUpdateHandler bean is null");
            if (redisTemplate == null) throw new IllegalStateException("RedisTemplate bean is null");
            if (faxLogRepository == null) throw new IllegalStateException("FaxLogRepository bean is null");
            if (faxEngineService == null) throw new IllegalStateException("FaxEngineService bean is null");
        } catch (BeansException | IllegalStateException e) {
            logger.error("Failed to retrieve critical beans: {}", e.getMessage());
            throw new IllegalStateException("Application initialization failed", e);
        }

        // Health check for Redis
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            logger.info("Redis connection healthy");
        } catch (Exception e) {
            logger.error("Redis connection failed: {}", e.getMessage());
            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Redis unavailable: " + e.getMessage()).showAndWait());
        }

        // Set up the main scene
        Scene scene = new Scene(mainView.getView(), windowWidth, windowHeight);
        themeManager.applyMermaidTheme(scene);

        // Splash screen with branding
        StackPane splashPane = new StackPane(new Label("Fax Trident - Powered by xAI"));
        splashPane.setStyle("-fx-background-color: #00ced1; -fx-alignment: center; -fx-font-size: 24px;");
        Scene splashScene = new Scene(splashPane, windowWidth, windowHeight);
        themeManager.applyMermaidTheme(splashScene);
        FadeTransition fadeOut = new FadeTransition(Duration.seconds(2), splashPane);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> {
            primaryStage.setScene(scene);
            logger.info("Splash screen faded out, main UI loaded");
        });

        // Configure the stage
        primaryStage.setTitle("Fax Trident - Powered by JuneBug");
        primaryStage.setScene(splashScene);
        primaryStage.setOnCloseRequest(e -> {
            logger.info("User requested shutdown...");
            shutdown();
        });
        primaryStage.show();

        // Play startup sound and start splash
        playStartupSound(startupSoundPath);
        fadeOut.play();

        // Start WebSocket and log startup stats
        CompletableFuture.runAsync(() -> faxUpdateHandler.broadcast("Fax Trident started"));
        long totalFaxes = faxLogRepository.count();
        logger.info("Application started - Total faxes logged: {}", totalFaxes);
    }

    @Override
    public void stop() throws Exception {
        shutdown();
    }

    private void shutdown() {
        logger.info("Shutting down Fax Trident...");
        if (startupSoundPlayer != null) {
            startupSoundPlayer.stop();
            startupSoundPlayer.dispose();
            logger.debug("Startup sound player stopped and disposed");
        }
        Platform.exit();
        if (springContext != null) {
            springContext.close();
            logger.info("Spring context closed");
        }
        System.exit(0);
    }

    private void playStartupSound(String soundPath) {
        try {
            String soundResource = Objects.requireNonNull(getClass().getResource(soundPath), 
                "Sound file not found: " + soundPath).toExternalForm();
            Media media = new Media(soundResource);
            startupSoundPlayer = new MediaPlayer(media);
            startupSoundPlayer.setVolume(0.5);
            startupSoundPlayer.setOnError(() -> logger.error("Error playing startup sound: {}", 
                startupSoundPlayer.getError().getMessage()));
            startupSoundPlayer.setOnEndOfMedia(() -> {
                startupSoundPlayer.dispose();
                logger.debug("Startup sound finished and disposed");
            });
            startupSoundPlayer.play();
            logger.info("Playing startup sound: {}", soundPath);
        } catch (Exception e) {
            logger.warn("Failed to play startup sound at {}: {}", soundPath, e.getMessage());
        }
    }
}
