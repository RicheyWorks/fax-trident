package com.xai.trident.desktop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Per-machine preferences store for the desktop client.
 *
 * <p>Replaces the Redis-backed {@code theme:<username>} key that
 * {@link com.xai.trident.desktop.ui.ThemeManager} used to read and write
 * via the in-process {@code RedisTemplate} bean. After the JavaFX / Spring
 * Boot split (ADR-0001), the desktop has no Redis connection — preferences
 * live in {@code ${user.home}/.fax-trident/preferences.properties}.
 *
 * <p>Side effects of the move:
 * <ul>
 *   <li>Theme preference is now <b>per-machine</b>, not per-user-across-machines.
 *       Acceptable for a fax UI.</li>
 *   <li>The file is plain text — fine for a UI theme; do NOT persist anything
 *       sensitive here. The JWT explicitly lives in memory only (see
 *       {@link com.xai.trident.desktop.client.FaxApiClient}).</li>
 *   <li>If the file is unreadable / corrupt / missing, every read returns
 *       the supplied default. Failure modes are logged at WARN level once
 *       at startup and not propagated — preference loss is not worth a
 *       crash dialog.</li>
 * </ul>
 *
 * <p>Threading: a single instance is constructed at app startup and shared.
 * All public methods synchronize on the internal {@link Properties}, so it
 * is safe to read from the JavaFX thread and write from a background
 * thread (or vice versa).
 */
public final class DesktopPreferences {

    private static final Logger logger = LoggerFactory.getLogger(DesktopPreferences.class);

    /**
     * Directory under the user's home that holds the preferences file.
     * Overridable via the {@code fax.trident.config.dir} system property
     * for tests / CI.
     */
    private static final String CONFIG_DIR_PROP = "fax.trident.config.dir";
    private static final String DEFAULT_CONFIG_SUBDIR = ".fax-trident";
    private static final String FILE_NAME = "preferences.properties";

    /** Convenience key constants — keep in lockstep with whoever reads them. */
    public static final String KEY_THEME = "ui.theme";
    public static final String KEY_SERVER_BASE_URL = "server.baseUrl";
    public static final String KEY_SERVER_WS_URL = "server.wsUrl";
    public static final String KEY_LAST_USERNAME = "ui.lastUsername";

    private final Path file;
    private final Properties props = new Properties();

    public DesktopPreferences() {
        this.file = resolveFilePath();
        load();
    }

    private static Path resolveFilePath() {
        String override = System.getProperty(CONFIG_DIR_PROP);
        Path dir = (override != null && !override.isBlank())
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home", "."), DEFAULT_CONFIG_SUBDIR);
        return dir.resolve(FILE_NAME);
    }

    private void load() {
        synchronized (props) {
            if (!Files.exists(file)) {
                logger.info("Preferences file does not exist yet at {}; using defaults", file);
                return;
            }
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                logger.info("Loaded {} preference(s) from {}", props.size(), file);
            } catch (IOException e) {
                // Bad file. Fall back to in-memory defaults; do not delete the
                // file — operator may want to inspect it.
                logger.warn("Failed to read preferences from {}: {}. Using defaults.",
                        file, e.getMessage());
            }
        }
    }

    /**
     * Return the value of {@code key}, or {@code defaultValue} if unset or
     * the on-disk file is unreadable. Whitespace-only values are treated as
     * unset.
     */
    public String get(String key, String defaultValue) {
        synchronized (props) {
            String v = props.getProperty(key);
            return (v == null || v.isBlank()) ? defaultValue : v;
        }
    }

    /**
     * Set {@code key} to {@code value} and persist to disk synchronously.
     * IO failures are logged and swallowed — losing a preference write is
     * not worth a crash.
     */
    public void set(String key, String value) {
        synchronized (props) {
            if (value == null) {
                props.remove(key);
            } else {
                props.setProperty(key, value);
            }
            persist();
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "fax-trident desktop preferences — managed by DesktopPreferences");
            }
        } catch (IOException e) {
            logger.warn("Failed to persist preferences to {}: {}", file, e.getMessage());
        }
    }

    public Path getFile() {
        return file;
    }
}
