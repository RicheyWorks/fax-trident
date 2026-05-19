package com.xai.trident.desktop.ui;

import com.xai.trident.desktop.config.DesktopPreferences;
import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Loads the JavaFX stylesheets and remembers the user's choice.
 *
 * <p>Pre-split (ADR-0001) this read and wrote the active theme to a Redis
 * key (<code>theme:&lt;username&gt;</code>) via an in-process
 * {@code RedisTemplate} bean. After the split, the desktop has no Redis
 * connection, so the theme preference is persisted to
 * {@link DesktopPreferences} — a local file under {@code ${user.home}}.
 *
 * <p>Visible behavior changes vs. pre-split:
 * <ul>
 *   <li>The choice is now per-machine rather than per-user-across-machines.
 *       Acceptable trade-off (see ADR-0001).</li>
 *   <li>{@code getUserTheme(String)} no longer takes a username — the desktop
 *       runs as one user at a time. The new accessor is {@link #getSavedTheme()}.</li>
 *   <li>{@code SecurityContextHolder} references are gone — the desktop's
 *       view of "the current user" is the username returned by login, not
 *       a Spring Security context.</li>
 * </ul>
 */
public class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);

    /**
     * Classpath path to the optional theme metadata properties. Format:
     * <pre>
     *   mermaid=/css/mermaid-mode.css
     *   dark=/css/dark-mode.css
     * </pre>
     * Falls back to the two-theme default if absent.
     */
    private static final String THEMES_CONFIG = "/css/themes.properties";

    private final Map<String, String> themes;
    private final DesktopPreferences prefs;
    private String currentTheme;

    public ThemeManager(DesktopPreferences prefs) {
        this.prefs = prefs;
        this.themes = new HashMap<>();
        initializeThemes();
        this.currentTheme = getSavedTheme();
        logger.info("ThemeManager initialized; saved theme = {}", currentTheme);
    }

    private void initializeThemes() {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream(THEMES_CONFIG)) {
            if (is != null) {
                props.load(is);
                props.forEach((key, value) -> themes.put((String) key, (String) value));
            } else {
                themes.put("mermaid", "/css/mermaid-mode.css");
                themes.put("dark", "/css/dark-mode.css");
            }
        } catch (IOException e) {
            logger.warn("Failed to load themes config: {}; using defaults", e.getMessage());
            themes.put("mermaid", "/css/mermaid-mode.css");
            themes.put("dark", "/css/dark-mode.css");
        }
        logger.info("Registered themes: {}", themes.keySet());
    }

    public void applyTheme(Scene scene, String themeName) {
        if (scene == null) {
            logger.error("Cannot apply theme '{}' — Scene is null", themeName);
            return;
        }
        String themePath = themes.get(themeName);
        if (themePath == null || getClass().getResource(themePath) == null) {
            logger.warn("Theme '{}' not found or CSS missing; falling back to 'mermaid'", themeName);
            themeName = "mermaid";
            themePath = themes.get("mermaid");
        }
        scene.getStylesheets().clear();
        scene.getStylesheets().add(themePath);
        this.currentTheme = themeName;
        prefs.set(DesktopPreferences.KEY_THEME, themeName);
        logger.info("Applied theme: {}", themeName);
    }

    public void applyMermaidTheme(Scene scene) {
        applyTheme(scene, "mermaid");
    }

    public void applyDarkTheme(Scene scene) {
        applyTheme(scene, "dark");
    }

    public String getCurrentTheme() {
        return currentTheme;
    }

    /**
     * Read the persisted theme from preferences, or "mermaid" if none is
     * persisted or the persisted value isn't a known theme. Replaces the
     * pre-split {@code getUserTheme(String username)} which looked up a
     * Redis key by username.
     */
    public String getSavedTheme() {
        String saved = prefs.get(DesktopPreferences.KEY_THEME, "mermaid");
        return isThemeAvailable(saved) ? saved : "mermaid";
    }

    public boolean isThemeAvailable(String themeName) {
        return themes.containsKey(themeName) && getClass().getResource(themes.get(themeName)) != null;
    }
}
