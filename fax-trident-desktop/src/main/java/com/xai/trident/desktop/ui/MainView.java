package com.xai.trident.desktop.ui;

import com.xai.trident.desktop.client.FaxApiClient;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Main desktop view — fax number entry, file picker, preview, send.
 *
 * <p>Pre-split (ADR-0001) this {@code @Component} injected
 * {@code FaxEngineService} directly and called {@code sendFaxAsync(faxNumber,
 * filePath)} in-process. After the split it talks to the server through
 * {@link FaxApiClient}: upload the local PDF to {@code /api/fax/uploads}
 * for an opaque upload ID, then call {@code /api/fax/send} with the ID +
 * fax number. Two network round-trips per send instead of one in-process
 * call, but the contract is the documented REST API (audit 1.5).
 *
 * <p>{@code SecurityContextHolder} references are gone — "current user" is
 * the username the {@link FaxApiClient} authenticated as.
 */
public class MainView {

    private static final Logger logger = LoggerFactory.getLogger(MainView.class);
    private final BorderPane root;
    private final FaxApiClient apiClient;
    private final PreviewPane previewPane;
    private final ThemeManager themeManager;

    private TextField faxNumberField;
    private Label statusLabel;
    private String selectedFilePath;

    public MainView(FaxApiClient apiClient,
                    PreviewPane previewPane,
                    ThemeManager themeManager,
                    FaxUpdateClient faxUpdateClient) {
        this.apiClient = apiClient;
        this.previewPane = previewPane;
        this.themeManager = themeManager;
        this.root = new BorderPane();
        // Register for broadcast updates via the shared client. The listener
        // runs on the WS reader thread, so we marshal back onto the JavaFX
        // thread inside handleFaxUpdate. (Audit 2.14, ADR-0001.)
        faxUpdateClient.addListener(this::handleFaxUpdate);
        logger.info("MainView initialized for user '{}'", apiClient.currentUsername());
        initializeUI();
    }

    /**
     * Handles an inbound update from the shared {@link FaxUpdateClient}.
     * Always invoked on a background thread — marshal to FX before touching UI.
     */
    private void handleFaxUpdate(Map<String, String> update) {
        Platform.runLater(() -> {
            String status = update.get("status");
            String faxId = update.get("faxId");
            String msg = update.get("message");
            logger.info("Received WebSocket update: {}", update);
            statusLabel.setText(faxId != null ? "Fax " + faxId + ": " + status : msg);
            if (msg != null) {
                Tooltip.install(statusLabel, new Tooltip(msg));
            }
            FadeTransition fade = new FadeTransition(Duration.seconds(1), statusLabel);
            fade.setFromValue(0.0);
            fade.setToValue(1.0);
            fade.play();
        });
    }

    private void initializeUI() {
        // Single source of truth — the programmatic builder. (Audit 2.13.)
        buildProgrammaticUI();
        Platform.runLater(() -> themeManager.applyTheme(root.getScene(), themeManager.getSavedTheme()));
    }

    private void buildProgrammaticUI() {
        faxNumberField = new TextField();
        faxNumberField.setPromptText("Enter fax number (e.g., +12025550123)");
        faxNumberField.setPrefWidth(300);
        faxNumberField.setTooltip(new Tooltip("Enter a valid fax number"));
        faxNumberField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal.matches("^\\+?[1-9]\\d{0,14}$") && !newVal.isEmpty()) {
                faxNumberField.setStyle("-fx-border-color: red;");
            } else {
                faxNumberField.setStyle("");
            }
        });
        HBox topBox = new HBox(10, faxNumberField);
        topBox.setStyle("-fx-padding: 10; -fx-alignment: center;");

        statusLabel = new Label("Ready");
        statusLabel.setTooltip(new Tooltip("Current fax operation status"));
        VBox centerBox = new VBox(previewPane.getPreview(), statusLabel);
        centerBox.setStyle("-fx-background-color: #e0ffff; -fx-alignment: center; -fx-pref-height: 600; -fx-spacing: 10;");

        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));

        Button sendButton = new Button("Send Fax");
        sendButton.setPrefWidth(100);
        sendButton.setTooltip(new Tooltip("Send the fax to the entered number"));
        sendButton.setOnAction(e -> {
            String faxNumber = faxNumberField.getText();
            if (faxNumber.isEmpty() || selectedFilePath == null) {
                logger.warn("Send Fax clicked - missing fax number or file");
                statusLabel.setText("Error: Missing fax number or file");
            } else if (!faxNumber.matches("^\\+?[1-9]\\d{1,14}$")) {
                logger.warn("Send Fax clicked - invalid fax number: {}", faxNumber);
                statusLabel.setText("Error: Invalid fax number");
            } else {
                sendFax(faxNumber, selectedFilePath);
            }
        });

        Button previewButton = new Button("Preview");
        previewButton.setPrefWidth(100);
        previewButton.setTooltip(new Tooltip("Preview the selected PDF"));
        previewButton.setOnAction(e -> {
            if (selectedFilePath != null) {
                previewPane.loadDocumentAsync(selectedFilePath)
                    .thenRun(() -> logger.info("Preview loaded for file: {}", selectedFilePath))
                    .exceptionally(ex -> {
                        logger.error("Preview load failed asynchronously: {}", ex.getMessage());
                        return null;
                    });
                statusLabel.setText("Loading preview...");
            } else {
                logger.warn("Preview clicked - no file selected");
                statusLabel.setText("Error: No file selected");
            }
        });

        Button fileButton = new Button("Choose File");
        fileButton.setPrefWidth(100);
        fileButton.setTooltip(new Tooltip("Select a PDF file to fax"));
        fileButton.setOnAction(e -> {
            File file = fileChooser.showOpenDialog(root.getScene().getWindow());
            if (file != null) {
                selectedFilePath = file.getAbsolutePath();
                logger.info("Selected file: {}", selectedFilePath);
                statusLabel.setText("File selected: " + file.getName());
            }
        });

        Button themeToggle = new Button("Toggle Theme");
        themeToggle.setPrefWidth(100);
        themeToggle.setTooltip(new Tooltip("Switch between Mermaid and Dark themes"));
        themeToggle.setOnAction(e -> {
            String newTheme = themeManager.getCurrentTheme().equals("mermaid") ? "dark" : "mermaid";
            themeManager.applyTheme(root.getScene(), newTheme);
            logger.info("Theme toggled to: {}", newTheme);
            statusLabel.setText("Theme switched to " + newTheme);
        });

        HBox bottomBox = new HBox(10, sendButton, previewButton, fileButton, themeToggle);
        bottomBox.setStyle("-fx-padding: 10; -fx-alignment: center;");

        root.setTop(topBox);
        root.setCenter(centerBox);
        root.setBottom(bottomBox);

        logger.info("Programmatic UI built successfully");
    }

    /**
     * Upload the local PDF, then ask the server to send it. Runs entirely
     * off the FX thread; UI updates marshal back via Platform.runLater.
     *
     * <p>Pre-split this was one {@code faxEngineService.sendFaxAsync(faxNumber, filePath)}
     * call. Post-split it's two REST calls — upload then send — because
     * the API surface no longer accepts a server-local file path (audit
     * 1.5). The user-visible result is the same.
     */
    private void sendFax(String faxNumber, String filePath) {
        logger.info("User '{}' sending fax to {} from {}",
                apiClient.currentUsername(), faxNumber, filePath);
        statusLabel.setText("Uploading...");

        Thread worker = new Thread(() -> {
            try {
                String uploadId = apiClient.uploadPdf(Path.of(filePath));
                Platform.runLater(() -> statusLabel.setText("Sending fax..."));
                String faxId = apiClient.sendFax(faxNumber, uploadId);
                Platform.runLater(() -> statusLabel.setText(
                        "Fax queued to " + faxNumber + (faxId.isEmpty() ? "" : " (id=" + faxId + ")")));
            } catch (IOException ex) {
                logger.error("Send-fax HTTP flow failed: {}", ex.getMessage());
                Platform.runLater(() -> statusLabel.setText("Send failed: " + ex.getMessage()));
            } catch (RuntimeException ex) {
                logger.error("Unexpected error during send-fax: {}", ex.toString());
                Platform.runLater(() -> statusLabel.setText("Send failed (see logs)"));
            }
        }, "fax-trident-send");
        worker.setDaemon(true);
        worker.start();
    }

    public BorderPane getView() {
        return root;
    }
}
