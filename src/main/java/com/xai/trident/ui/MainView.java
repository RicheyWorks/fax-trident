package com.xai.trident.ui;

import com.xai.trident.service.FaxEngineService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@Component
public class MainView {

    private static final Logger logger = LoggerFactory.getLogger(MainView.class);
    private final BorderPane root;
    private final FaxEngineService faxEngineService;
    private final PreviewPane previewPane;
    private final ThemeManager themeManager;

    private TextField faxNumberField;
    private Label statusLabel;
    private String selectedFilePath;

    @Autowired
    public MainView(FaxEngineService faxEngineService,
                    PreviewPane previewPane,
                    ThemeManager themeManager,
                    FaxUpdateClient faxUpdateClient) {
        this.faxEngineService = faxEngineService;
        this.previewPane = previewPane;
        this.themeManager = themeManager;
        this.root = new BorderPane();
        // Register for broadcast updates via the shared client. The listener
        // runs on the WS reader thread, so we marshal back onto the JavaFX
        // thread inside handleFaxUpdate. (2.14)
        faxUpdateClient.addListener(this::handleFaxUpdate);
        // FaxUpdateHandler field removed — was injected but never used after
        // the 2.14 fix pulled WS handling into FaxUpdateClient (2.20).
        logger.info("MainView initialized with FaxEngineService, PreviewPane, ThemeManager, and shared FaxUpdateClient");
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
        // Single source of truth — the programmatic builder. The previous
        // code attempted FXMLLoader.load("/fxml/main.fxml") first and only
        // fell back to buildProgrammaticUI() on failure, but the FXML
        // referenced onAction handlers (#handleSendFax, #handlePreview)
        // that didn't exist anywhere and provided no fx:id for statusLabel,
        // so a successful load would have left core UI either broken at
        // click time or missing entirely. Audit finding 2.13.
        buildProgrammaticUI();
        Platform.runLater(() -> {
            String username = getCurrentUsername();
            themeManager.applyTheme(root.getScene(), themeManager.getUserTheme(username));
        });
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
                try {
                    previewPane.loadDocumentAsync(selectedFilePath)
                        .thenRun(() -> logger.info("Preview loaded for file: {}", selectedFilePath))
                        // Real async failures land here (after @Retryable retries and @Recover).
                        .exceptionally(ex -> {
                            logger.error("Preview load failed asynchronously: {}", ex.getMessage());
                            return null;
                        });
                    statusLabel.setText("Loading preview...");
                } catch (IOException ex) {
                    // Required by Java's checked-exception rules because
                    // loadDocumentAsync declares `throws IOException` (so the
                    // @Retryable proxy on the body can observe IOException and
                    // retry). Under @Async, the proxy returns the future
                    // synchronously and never actually reaches this branch —
                    // real async failures are handled by .exceptionally(...) above.
                    logger.error("Unexpected synchronous IOException from async preview call: {}", ex.getMessage());
                    statusLabel.setText("Failed to start preview");
                }
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
            String username = getCurrentUsername();
            String newTheme = themeManager.getCurrentTheme().equals("mermaid") ? "dark" : "mermaid";
            themeManager.applyTheme(root.getScene(), newTheme);
            logger.info("User '{}' toggled theme to: {}", username, newTheme);
            statusLabel.setText("Theme switched to " + newTheme);
        });

        HBox bottomBox = new HBox(10, sendButton, previewButton, fileButton, themeToggle);
        bottomBox.setStyle("-fx-padding: 10; -fx-alignment: center;");

        root.setTop(topBox);
        root.setCenter(centerBox);
        root.setBottom(bottomBox);

        logger.info("Programmatic UI built successfully");
    }

    // WebSocket initialization removed — replaced by the shared FaxUpdateClient
    // bean (audit 2.14). MainView subscribes via addListener in its constructor.

    private void sendFax(String faxNumber, String filePath) {
        String username = getCurrentUsername();
        logger.info("User '{}' sending fax to {} from {}", username, faxNumber, filePath);
        statusLabel.setText("Sending fax...");
        faxEngineService.sendFaxAsync(faxNumber, filePath)
            .thenRun(() -> Platform.runLater(() -> statusLabel.setText("Fax queued to " + faxNumber)));
    }

    public BorderPane getView() {
        return root;
    }

    private String getCurrentUsername() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null ?
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName() : "system";
    }
}
