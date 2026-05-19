package com.xai.trident.desktop.ui;

import com.xai.trident.desktop.client.FaxApiClient;
import com.xai.trident.desktop.config.DesktopPreferences;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;

/**
 * Modal login prompt shown before {@link MainView} opens. New after the
 * JavaFX / Spring Boot split (ADR-0001): previously the desktop ran in
 * the same JVM as the server and inherited ambient auth via
 * {@code SecurityContextHolder}. Now it has to authenticate over HTTP
 * like any other client.
 *
 * <p>Returns the bound {@link FaxApiClient} on success, or {@link Optional#empty()}
 * on cancel. The caller is responsible for treating cancel as "exit the
 * application" — the desktop has nothing useful to do without a server
 * connection.
 *
 * <p>The server base URL is editable in the dialog so a desktop install
 * can point at different environments (dev, staging, prod). The choice is
 * persisted into {@link DesktopPreferences#KEY_SERVER_BASE_URL} on success.
 */
public final class LoginDialog {

    private static final Logger logger = LoggerFactory.getLogger(LoginDialog.class);

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    private final DesktopPreferences prefs;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public LoginDialog(DesktopPreferences prefs, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.prefs = prefs;
        this.json = json;
    }

    /**
     * Show the dialog and block (modal) until the user either cancels or
     * a login attempt succeeds. Returns the configured client.
     */
    public Optional<FaxApiClient> showAndAwait() {
        Dialog<FaxApiClient> dialog = new Dialog<>();
        dialog.setTitle("Sign in to Fax Trident");
        dialog.setHeaderText("Enter your server URL and credentials.");

        ButtonType signInButton = new ButtonType("Sign in", ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(signInButton, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        TextField serverField = new TextField(prefs.get(DesktopPreferences.KEY_SERVER_BASE_URL, DEFAULT_BASE_URL));
        serverField.setPromptText("http://host:port");
        serverField.setPrefColumnCount(28);

        TextField userField = new TextField(prefs.get(DesktopPreferences.KEY_LAST_USERNAME, ""));
        userField.setPromptText("Username");
        userField.setPrefColumnCount(28);

        PasswordField passField = new PasswordField();
        passField.setPromptText("Password");
        passField.setPrefColumnCount(28);

        Label statusLabel = new Label("");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(280);
        statusLabel.setStyle("-fx-text-fill: #b00020;");

        grid.add(new Label("Server:"), 0, 0);
        grid.add(serverField, 1, 0);
        grid.add(new Label("Username:"), 0, 1);
        grid.add(userField, 1, 1);
        grid.add(new Label("Password:"), 0, 2);
        grid.add(passField, 1, 2);
        grid.add(statusLabel, 0, 3, 2, 1);

        dialog.getDialogPane().setContent(grid);

        // Don't auto-close the dialog when "Sign in" is clicked — intercept
        // the OK button's action so we can run the network call and either
        // keep the dialog open on failure or close it with a result on
        // success.
        Button signInBtn = (Button) dialog.getDialogPane().lookupButton(signInButton);
        signInBtn.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume(); // prevent default close-with-null-result
            String url = serverField.getText().trim();
            String user = userField.getText().trim();
            String pass = passField.getText();
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                statusLabel.setText("Server URL, username, and password are all required.");
                return;
            }
            signInBtn.setDisable(true);
            statusLabel.setText("Signing in…");

            // Network call off the FX thread; result reported back via Platform.runLater.
            Thread worker = new Thread(() -> {
                FaxApiClient client = new FaxApiClient(url, json);
                try {
                    client.login(user, pass);
                    Platform.runLater(() -> {
                        prefs.set(DesktopPreferences.KEY_SERVER_BASE_URL, url);
                        prefs.set(DesktopPreferences.KEY_LAST_USERNAME, user);
                        dialog.setResult(client);
                        dialog.close();
                    });
                } catch (IOException ex) {
                    logger.warn("Login failed for '{}': {}", user, ex.getMessage());
                    Platform.runLater(() -> {
                        statusLabel.setText("Sign-in failed: " + ex.getMessage());
                        signInBtn.setDisable(false);
                    });
                } catch (RuntimeException ex) {
                    logger.error("Unexpected error during login: {}", ex.toString());
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR,
                                "Unexpected error: " + ex.getMessage());
                        alert.showAndWait();
                        signInBtn.setDisable(false);
                    });
                }
            }, "fax-trident-login");
            worker.setDaemon(true);
            worker.start();
        });

        dialog.setResultConverter(button -> button == signInButton ? dialog.getResult() : null);
        return dialog.showAndWait();
    }
}
