package com.xai.trident.desktop.ui;

import com.xai.trident.desktop.client.RetryHelper;
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

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Inline PDF preview pane.
 *
 * <p>Pre-split (ADR-0001) the load path was Spring-decorated:
 * {@code @Async} for off-thread execution, {@code @Retryable} for 3
 * attempts with 1s backoff, {@code @Recover} for the failure UI. With
 * Spring out of the desktop classpath, all three are replaced with
 * explicit Java:
 *
 * <ul>
 *   <li>{@code @Async} → a single-thread {@link Executor} owned by this
 *       pane (background thread, daemon).</li>
 *   <li>{@code @Retryable} / {@code @Recover} → {@link RetryHelper} +
 *       a regular catch block.</li>
 * </ul>
 *
 * <p>Behavior is identical: load on a background thread, retry up to 3
 * times with 1s backoff on {@link IOException}, render an error VBox if
 * all attempts fail.
 */
public class PreviewPane {

    private static final Logger logger = LoggerFactory.getLogger(PreviewPane.class);
    private static final int MAX_LOAD_ATTEMPTS = 3;
    private static final long LOAD_BACKOFF_MS = 1_000;

    private final ScrollPane preview;
    private final ThemeManager themeManager;
    private final Executor loadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "preview-pane-loader");
        t.setDaemon(true);
        return t;
    });

    private double zoomLevel = 1.0;
    private Label statusLabel;

    public PreviewPane(ThemeManager themeManager, FaxUpdateClient faxUpdateClient) {
        this.themeManager = themeManager;
        this.preview = new ScrollPane();
        preview.setFitToWidth(true);
        // Register on the shared client — replaces the per-component
        // WebSocketClient that PreviewPane used to spin up in @PostConstruct
        // (audit 2.14). Constructor-injected at desktop wire-up time
        // (FaxTridentDesktop.start) post-split.
        faxUpdateClient.addListener(this::handleFaxUpdate);
        logger.info("PreviewPane initialized");
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
     * Load and render a PDF for preview on a background thread.
     *
     * <p>The pre-split version was {@code @Async @Retryable(maxAttempts=3,
     * backoff=@Backoff(delay=1000), value=IOException.class)}. Now the same
     * semantics are produced explicitly: a single-thread executor runs the
     * load, {@link RetryHelper} handles the three-attempts-with-backoff
     * loop, and the failure-UI is in a plain catch block instead of a
     * separate {@code @Recover} method.
     *
     * <p>Returns a {@link CompletableFuture} so the caller (MainView) can
     * still chain {@code .thenRun} / {@code .exceptionally}.
     */
    public CompletableFuture<Void> loadDocumentAsync(String filePath) {
        return CompletableFuture.runAsync(() -> {
            try {
                RetryHelper.withRetry(() -> {
                    loadDocument(filePath);
                    return null;
                }, MAX_LOAD_ATTEMPTS, LOAD_BACKOFF_MS, "loadDocument");
            } catch (IOException e) {
                renderLoadFailure(filePath, e);
                throw new RuntimeException(e);
            }
        }, loadExecutor);
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

            int pageCount = document.getNumberOfPages();
            Platform.runLater(() -> {
                preview.setContent(pages);
                updateZoom();
                statusLabel.setText("Preview loaded: " + pageCount + " pages");
                logger.info("Document preview loaded successfully: {} pages", pageCount);
            });
        }
    }

    /**
     * Render an error UI after all retries are exhausted. Replaces the
     * pre-split {@code @Recover public CompletableFuture<Void> recoverFromLoadFailure(...)}.
     * Called from the catch block in {@link #loadDocumentAsync(String)}.
     */
    private void renderLoadFailure(String filePath, IOException e) {
        logger.error("Failed to load document '{}' after retries: {}", filePath, e.getMessage());
        Platform.runLater(() -> {
            VBox errorBox = new VBox(new Label("Error: Failed after retries - " + e.getMessage()));
            errorBox.setStyle("-fx-padding: 10; -fx-alignment: center;");
            preview.setContent(errorBox);
            statusLabel.setText("Preview failed after retries");
        });
    }

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
