package com.xai.trident.ui;

import javafx.scene.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Component
public class ThemeManager {

    private static final Logger logger = LoggerFactory.getLogger(ThemeManager.class);
    private final Map<String, String> themes;
    private String currentTheme;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${themes.config:/static/css/themes.properties}")
    private String themesConfig;

    @Autowired
    public ThemeManager(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.themes = new HashMap<>();
        logger.info("Initializing ThemeManager with RedisTemplate...");
        initializeThemes();
        this.currentTheme = "mermaid"; // Default theme
    }

    private void initializeThemes() {
        Properties props = new Properties();
        try (InputStream is = getClass().getResourceAsStream(themesConfig)) {
            if (is != null) {
                props.load(is);
                props.forEach((key, value) -> themes.put((String) key, (String) value));
            } else {
                logger.warn("Themes config file '{}' not found, using defaults", themesConfig);
                themes.put("mermaid", "/static/css/mermaid-mode.css");
                themes.put("dark", "/static/css/dark-mode.css");
            }
        } catch (IOException e) {
            logger.error("Failed to load themes from {}: {}", themesConfig, e.getMessage());
            themes.put("mermaid", "/static/css/mermaid-mode.css");
            themes.put("dark", "/static/css/dark-mode.css");
        }
        logger.info("Registered themes: {}", themes.keySet());
    }

    public void applyTheme(Scene scene, String themeName) {
        String username = getCurrentUsername();
        if (scene == null) {
            logger.error("User '{}' cannot apply theme - Scene is null", username);
            return;
        }

        String themePath = themes.get(themeName);
        if (themePath == null || getClass().getResource(themePath) == null) {
            logger.warn("User '{}' requested theme '{}' not found or CSS missing, falling back to 'mermaid'", username, themeName);
            themePath = themes.get("mermaid");
            themeName = "mermaid";
        }

        scene.getStylesheets().clear();
        scene.getStylesheets().add(themePath);
        currentTheme = themeName;
        redisTemplate.opsForValue().set("theme:" + username, themeName, 7, TimeUnit.DAYS);
        logger.info("User '{}' applied theme: {}", username, themeName);
    }

    public void applyMermaidTheme(Scene scene) {
        logger.info("Applying Mermaid Mode theme...");
        applyTheme(scene, "mermaid");
    }

    public void applyDarkTheme(Scene scene) {
        logger.info("Applying Dark Mode theme...");
        applyTheme(scene, "dark");
    }

    public String getCurrentTheme() {
        return currentTheme;
    }

    public String getUserTheme(String username) {
        String theme = (String) redisTemplate.opsForValue().get("theme:" + username);
        return theme != null && isThemeAvailable(theme) ? theme : "mermaid";
    }

    public boolean isThemeAvailable(String themeName) {
        return themes.containsKey(themeName) && getClass().getResource(themes.get(themeName)) != null;
    }

    // Helper method to get current username
    private String getCurrentUsername() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null ?
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName() : "system";
    }
}
