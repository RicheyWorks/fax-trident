package com.xai.trident.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class PreviewPane {

    private static final Logger logger = LoggerFactory.getLogger(PreviewPane.class);
    private final ScrollPane preview;
    private final ThemeManager themeManager;
    private double zoomLevel = 1.0;
    private Label statusLabel;

    @Autowired
    public PreviewPane(ThemeManager themeManager,
                       FaxUpdateClient faxUpdateClient) {
        this.themeManager = themeManager;
        this.preview = new ScrollPane();
        preview.setFitToWidth(true);
        // Register on the shared client — replaces the per-component
        // WebSocketClient that PreviewPane used to spin up in @PostConstruct
        // (audit 2.14).
        faxUpdateClient.addListener(this::handleFaxUpdate);
        // FaxUpdateHandler field removed (was injected but unused after 2.14).
        logger.info("PreviewPane initialized with ThemeManager and shared FaxUpdateClient");
        initializePreview();
    }

    /**
     * Update handler for messages from the shared {@link FaxUpdateClient}.
     * Invoked off the JavaFX thread; the body must marshal back via
     * {@link Platform#runLater(Runnable)} before touching UI nodes.
     */
    private void handleFaxUpdate(Map<String, String> update) {
        String msg = update.get("message");
        if (msg == null) {
            return;
        }
        Platform.runLater(() -> {
            logger.info("PreviewPane received update: {}", update);
            statusLabel.setText(msg);
            Tooltip.install(statusLabel, new Tooltip(msg));
            FadeTransition fade = new FadeTransition(Duration.seconds(1), statusLabel);
            fade.setFromValue(0.0);
            fade.setToValue(1.0);
            fade.play();
        });
    }

    private void initializePreview() {
        VBox content = new VBox(10);
        content.setStyle("-fx-padding: 10; -fx-alignment: center;");

        Button zoomIn = new Button("Zoom In");
        zoomIn.setTooltip(new Tooltip("Increase zoom level"));
        zoomIn.setOnAction(e -> {
            zoomLevel += 0.1;
            updateZoom();
            logger.debug("Zoomed in to level: {}", zoomLevel);
        });

        Button zoomOut = new Button("Zoom Out");
        zoomOut.setTooltip(new Tooltip("Decrease zoom level"));
        zoomOut.setOnAction(e -> {
            zoomLevel = Math.max(0.5, zoomLevel - 0.1);
            updateZoom();
            logger.debug("Zoomed out to level: {}", zoomLevel);
        });

        statusLabel = new Label("Ready");
        statusLabel.setTooltip(new Tooltip("Preview status"));

        HBox controls = new HBox(10, zoomIn, zoomOut, statusLabel);
        controls.setStyle("-fx-padding: 5; -fx-alignment: center;");
        content.getChildren().add(controls);
        preview.setContent(content);

        Platform.runLater(() -> themeManager.applyTheme(preview.getScene(), "mermaid"));
        logger.info("PreviewPane initialized with zoom controls and placeholder content");
    }

    /**
     * Loads and renders a PDF for preview, off the JavaFX UI thread.
     *
     * <p>The previous implementation had {@code @Async} on this method
     * <em>and</em> wrapped the body in {@code CompletableFuture.runAsync(...)} —
     * a double-async pattern. The inner CompletableFuture returned
     * immediately on the @Async worker thread, and any {@link IOException}
     * was thrown from the inner thread that nobody was observing, so
     * {@code @Retryable} could never see it. Now {@code @Async} alone owns
     * the threading and exceptions propagate normally so the retry/recover
     * machinery actually works.
     */
    @Async
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000), value = IOException.class)
    public CompletableFuture<Void> loadDocumentAsync(String filePath) throws IOException {
        loadDocument(filePath);
        return CompletableFuture.completedFuture(null);
    }

    public void loadDocument(String filePath) throws IOException {
        logger.info("Loading document for preview: {}", filePath);
        Platform.runLater(() -> statusLabel.setText("Loading..."));

        try (PDDocument document = PDDocument.load(new File(filePath))) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            VBox pages = new VBox(10);
            pages.setStyle("-fx-padding: 10; -fx-alignment: center;");

            for (int page = 0; page < document.getNumberOfPages(); page++) {
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 150);
                Image fxImage = convertToFxImage(bim);

                if (fxImage == null) {
                    throw new IOException("Failed to convert page " + (page + 1) + " to image");
                }

                ImageView imageView = new ImageView(fxImage);
                imageView.setPreserveRatio(true);
                imageView.setFitWidth(800 * zoomLevel);

                Label pageLabel = new Label("Page " + (page + 1));
                pageLabel.setTooltip(new Tooltip("Page " + (page + 1) + " of document"));
                pages.getChildren().add(new VBox(pageLabel, imageView));

                logger.debug("Rendered page {} of {}", page + 1, document.getNumberOfPages());
            }

            // Note: we still need a final to capture for the JavaFX lambda.
            int pageCount = document.getNumberOfPages();
            Platform.runLater(() -> {
                preview.setContent(pages);
                updateZoom();
                statusLabel.setText("Preview loaded: " + pageCount + " pages");
                logger.info("Document preview loaded successfully: {} pages", pageCount);
            });
        }
        // IOException intentionally propagates — @Retryable on the async
        // caller will catch it, retry up to 3 times with 1s backoff, then
        // hand off to recoverFromLoadFailure(...) below.
    }

    /**
     * @Recover for {@link #loadDocumentAsync(String)} once retries are
     * exhausted. Return type must match the @Retryable method's return
     * (CompletableFuture&lt;Void&gt;) — the previous {@code void} return
     * silently never matched, so even if retries had fired, the recovery
     * UI wouldn't have been shown.
     */
    @Recover
    public CompletableFuture<Void> recoverFromLoadFailure(IOException e, String filePath) {
        logger.error("Failed to load document '{}' after retries: {}", filePath, e.getMessage());
        Platform.runLater(() -> {
            VBox errorBox = new VBox(new Label("Error: Failed after retries - " + e.getMessage()));
            errorBox.setStyle("-fx-padding: 10; -fx-alignment: center;");
            preview.setContent(errorBox);
            statusLabel.setText("Preview failed after retries");
        });
        return CompletableFuture.completedFuture(null);
    }

    // WebSocket initialization removed — replaced by the shared FaxUpdateClient
    // bean (audit 2.14). PreviewPane subscribes via addListener in its constructor.

    private void updateZoom() {
        VBox content = (VBox) preview.getContent();
        if (content != null) {
            content.getChildren().stream()
                    .filter(node -> node instanceof VBox)
                    .map(node -> (VBox) node)
                    .flatMap(vbox -> vbox.getChildren().stream())
                    .filter(node -> node instanceof ImageView)
                    .forEach(node -> ((ImageView) node).setFitWidth(800 * zoomLevel));
            logger.debug("Zoom level updated to: {}", zoomLevel);
        }
    }

    private Image convertToFxImage(BufferedImage bufferedImage) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(bufferedImage, "png", baos);
            return new Image(new ByteArrayInputStream(baos.toByteArray()));
        } catch (IOException e) {
            logger.error("Failed to convert BufferedImage to FX Image: {}", e.getMessage());
            return null;
        }
    }

    public ScrollPane getPreview() {
        return preview;
    }
}
