package com.xai.trident.controller;

import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.service.PdfProcessingService;
import com.xai.trident.upload.FaxUploadService;
import com.xai.trident.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);
    private final FaxEngineService faxEngineService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FaxLogRepository faxLogRepository;
    private final ContactRepository contactRepository;
    private final FaxMetadataRepository faxMetadataRepository;
    private final PdfProcessingService pdfProcessingService;
    private final FaxUploadService faxUploadService;

    @Autowired
    public AdminController(FaxEngineService faxEngineService,
                           RedisTemplate<String, Object> redisTemplate,
                           FaxLogRepository faxLogRepository,
                           ContactRepository contactRepository,
                           FaxMetadataRepository faxMetadataRepository,
                           PdfProcessingService pdfProcessingService,
                           FaxUploadService faxUploadService) {
        this.faxEngineService = faxEngineService;
        this.redisTemplate = redisTemplate;
        this.faxLogRepository = faxLogRepository;
        this.contactRepository = contactRepository;
        this.faxMetadataRepository = faxMetadataRepository;
        this.pdfProcessingService = pdfProcessingService;
        this.faxUploadService = faxUploadService;
        logger.info("AdminController initialized with all dependencies");
    }

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getDashboard(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {

        String username = auth.getName();
        logger.info("User '{}' fetching admin dashboard data...", username);
        Map<String, Object> dashboardData = new HashMap<>();

        Pageable pageable = PageRequest.of(page, size);

        long sentFaxes = faxLogRepository.findByStatus("sent", pageable).getTotalElements();
        long failedFaxes = faxLogRepository.findByStatus("failed", pageable).getTotalElements();
        long totalFaxes = faxLogRepository.count();

        // KEYS is O(N) and blocking; SCAN walks the keyspace in cursor pages
        // so a busy Redis stays responsive even when the dashboard runs.
        long inProgressFaxes = 0;
        for (String key : scanKeys("fax_*")) {
            Object raw = redisTemplate.opsForValue().get(key);
            // null-guard: keys discovered by SCAN can be evicted before the
            // GET arrives, and values may not be strings if a future caller
            // writes something structured under the same prefix.
            if (raw instanceof String status &&
                    ("sending".equals(status) || "processing".equals(status))) {
                inProgressFaxes++;
            }
        }

        // SQL aggregations replace findAll().stream().mapToInt(...).sum() —
        // previously every fax_metadata row was loaded into JVM memory just
        // to be summed. JPQL SUM returns null on an empty table; coalesce to
        // 0 here so the dashboard JSON shape is stable (2.9).
        Long totalPagesAgg = faxMetadataRepository.findTotalPageCount();
        Long totalSizeAgg = faxMetadataRepository.findTotalFileSize();
        long totalPages = totalPagesAgg == null ? 0L : totalPagesAgg;
        long totalSize = totalSizeAgg == null ? 0L : totalSizeAgg;

        dashboardData.put("sentFaxes", sentFaxes);
        dashboardData.put("failedFaxes", failedFaxes);
        dashboardData.put("inProgressFaxes", inProgressFaxes);
        dashboardData.put("totalFaxes", totalFaxes);
        dashboardData.put("totalPagesSent", totalPages);
        dashboardData.put("totalFileSize", totalSize);

        logger.info("Dashboard data retrieved for '{}': {}", username, dashboardData);
        return ResponseEntity.ok(dashboardData);
    }

    @PostMapping("/send-fax")
    // No @Transactional — controller delegates to async service; no DB work
    // here. The service call owns its own transaction. (2.18)
    public ResponseEntity<String> sendFax(
            @RequestParam String faxNumber,
            @RequestParam String uploadId,
            Authentication auth) {

        String username = auth.getName();
        logger.info("Admin '{}' requested fax to {} with upload {}",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(faxNumber),
                LogSanitizer.sanitize(uploadId));
        // Resolve uploadId → server-controlled path BEFORE the try-catch
        // so UploadNotFoundException can reach UploadExceptionHandler
        // (-> 404) instead of being collapsed into the generic 500 below.
        String resolvedPath = faxUploadService.resolveToString(uploadId);
        try {
            faxEngineService.sendFaxAsync(faxNumber, resolvedPath)
                    .thenRun(() -> logger.info("Fax send completed by '{}'",
                            LogSanitizer.sanitize(username)));
            return ResponseEntity.ok("Fax send request queued successfully");
        } catch (Exception e) {
            logger.error("Failed to send fax by '{}': {}",
                    LogSanitizer.sanitize(username),
                    LogSanitizer.sanitize(e.getMessage()));
            return ResponseEntity.status(500).body("Failed to send fax: " + e.getMessage());
        }
    }

    @GetMapping("/fax-status/{faxId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getFaxStatus(
            @PathVariable String faxId,
            Authentication auth) {

        String username = auth.getName();
        logger.info("User '{}' fetching status for fax ID: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(faxId));
        Map<String, Object> statusData = new HashMap<>();

        FaxLog log = faxLogRepository.findByFaxId(faxId, Pageable.unpaged()).stream().findFirst().orElse(null);
        String redisStatus = (String) redisTemplate.opsForValue().get(faxId + ":status");

        if (log == null && redisStatus == null) {
            logger.warn("No status found for fax ID: {}", LogSanitizer.sanitize(faxId));
            return ResponseEntity.status(404).body(Map.of("error", "Fax ID not found"));
        }

        statusData.put("status", log != null ? log.getStatus() : redisStatus);
        statusData.put("faxNumber", log != null ? log.getFaxNumber() : redisTemplate.opsForValue().get(faxId + ":number"));
        statusData.put("pages", redisTemplate.opsForValue().get(faxId + ":pages"));
        statusData.put("file", redisTemplate.opsForValue().get(faxId + ":file"));
        if (log != null && log.getErrorMessage() != null) {
            statusData.put("errorMessage", log.getErrorMessage());
        }

        logger.info("Fax status retrieved for '{}': {}", username, statusData);
        return ResponseEntity.ok(statusData);
    }

    @DeleteMapping("/clear-cache")
    // No @Transactional — Redis-only, no JPA. (2.18)
    public ResponseEntity<String> clearCache(Authentication auth) {
        String username = auth.getName();
        logger.info("Admin '{}' requested to clear Redis cache...", username);
        try {
            List<String> keys = scanKeys("fax_*");
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
                logger.info("User '{}' cleared {} keys from Redis cache",
                        LogSanitizer.sanitize(username), keys.size());
            } else {
                logger.info("No keys found to clear in Redis cache by '{}'",
                        LogSanitizer.sanitize(username));
            }
            return ResponseEntity.ok("Cache cleared successfully");
        } catch (Exception e) {
            logger.error("Failed to clear cache by '{}': {}", username, e.getMessage());
            return ResponseEntity.status(500).body("Failed to clear cache: " + e.getMessage());
        }
    }

    @GetMapping("/contacts")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Contact>> getContacts(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching all contacts...", username);
        return ResponseEntity.ok(contactRepository.findAll());
    }

    // FIX: findByCreatedAfter requires Pageable — use unpaged() and unwrap to List via getContent()
    @GetMapping("/contacts/recent")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Contact>> getRecentContacts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching contacts since {}", username, since);
        List<Contact> recent = contactRepository.findByCreatedAfter(since, Pageable.unpaged()).getContent();
        return ResponseEntity.ok(recent);
    }

    // FIX: findByStatus requires Pageable
    @GetMapping("/logs/failed")
    @Transactional(readOnly = true)
    public ResponseEntity<List<FaxLog>> getFailedLogs(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching failed logs...", username);
        List<FaxLog> failed = faxLogRepository.findByStatus("failed", Pageable.unpaged()).getContent();
        return ResponseEntity.ok(failed);
    }

    // FIX: findByTimestampBetween requires Pageable
    @GetMapping("/logs/analytics")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Long>> getLogAnalytics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching log analytics between {} and {}", username, start, end);
        List<FaxLog> logs = faxLogRepository.findByTimestampBetween(start, end, Pageable.unpaged()).getContent();
        Map<String, Long> analytics = new HashMap<>();
        analytics.put("total", (long) logs.size());
        analytics.put("sent", logs.stream().filter(l -> "sent".equals(l.getStatus())).count());
        analytics.put("failed", logs.stream().filter(l -> "failed".equals(l.getStatus())).count());
        return ResponseEntity.ok(analytics);
    }

    @GetMapping("/websocket-stats")
    // No @Transactional — reads an in-memory AtomicInteger, no JPA. (2.18)
    public ResponseEntity<Map<String, Integer>> getWebSocketStats(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching WebSocket stats...", username);
        Map<String, Integer> stats = new HashMap<>();
        stats.put("activeConnections", faxEngineService.getWebSocketConnectionCount());
        return ResponseEntity.ok(stats);
    }

    // FIX: findByStatus requires Pageable; getTotalElements() returns long — use directly
    @GetMapping("/fax-stats")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Long>> getFaxStats(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching fax stats...", username);
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalSent", faxLogRepository.findByStatus("sent", Pageable.unpaged()).getTotalElements());
        stats.put("totalFailed", faxLogRepository.findByStatus("failed", Pageable.unpaged()).getTotalElements());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/pdf-stats")
    // No @Transactional — filesystem-only call, no JPA. (2.18)
    public ResponseEntity<Map<String, Long>> getPdfStats(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching PDF stats...", username);
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalBarcodes", pdfProcessingService.getBarcodeCount());
        return ResponseEntity.ok(stats);
    }

    @DeleteMapping("/cleanup-barcodes")
    public ResponseEntity<String> cleanupBarcodes(Authentication auth) {
        String username = auth.getName();
        logger.info("Admin '{}' requested to clean up barcode files...", username);
        try {
            // Delegated to PdfProcessingService so the configured barcode
            // directory stays encapsulated. The previous implementation
            // scanned Paths.get(".") (JVM CWD) which usually had no barcode
            // files at all — and leaked the directory handle by not closing
            // the Stream returned by Files.list.
            long removed = pdfProcessingService.cleanupAllBarcodes();
            return ResponseEntity.ok("Cleaned up " + removed + " barcode file(s)");
        } catch (IOException e) {
            logger.error("Failed to clean up barcodes by '{}': {}", username, e.getMessage());
            return ResponseEntity.status(500).body("Failed to clean up barcodes: " + e.getMessage());
        }
    }

    // FIX: findByCreatedAfter on FaxMetadataRepository requires Pageable
    @GetMapping("/metadata/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getMetadataStats(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching metadata stats...", username);
        Map<String, Object> stats = new HashMap<>();
        stats.put("averagePageCount", faxMetadataRepository.findAveragePageCount());
        stats.put("recentFiles", faxMetadataRepository
                .findByCreatedAfter(LocalDateTime.now().minusDays(7), Pageable.unpaged())
                .getTotalElements());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/theme-stats")
    // No @Transactional — Redis-only, no JPA. (2.18)
    public ResponseEntity<Map<String, Long>> getThemeStats(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching theme stats...", username);
        Map<String, Long> stats = new HashMap<>();
        for (String key : scanKeys("theme:*")) {
            Object raw = redisTemplate.opsForValue().get(key);
            // Skip evicted-between-SCAN-and-GET values and any non-string
            // entries — the previous version threw NPE on either condition.
            if (raw instanceof String theme) {
                stats.merge(theme, 1L, Long::sum);
            }
        }
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/prediction-analytics")
    // No @Transactional — Redis-only, no JPA. (2.18)
    public ResponseEntity<Map<String, Object>> getPredictionAnalytics(Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching prediction analytics...", username);
        Map<String, Object> analytics = new HashMap<>();
        long successfulPredictions = 0;
        for (String key : scanKeys("predict:*")) {
            Object raw = redisTemplate.opsForValue().get(key);
            // The previous version cast Object -> String unconditionally,
            // which NPE'd whenever the value had been evicted between
            // KEYS and GET. Treat null / non-string as a non-success.
            if (raw instanceof String value && !value.startsWith("Prediction error")) {
                successfulPredictions++;
            }
        }
        analytics.put("successfulPredictions", successfulPredictions);
        return ResponseEntity.ok(analytics);
    }

    /**
     * Cursor-based key lookup. Equivalent to {@code redisTemplate.keys(pattern)}
     * but uses {@code SCAN} instead of the blocking {@code KEYS} command, so a
     * busy production Redis is not paused for the duration of a full keyspace
     * walk. The cursor is closed in a try-with-resources block — the previous
     * iterator-style usage of {@code keys()} returned a fully-materialized
     * {@code Set}, but {@code scan()} returns a cursor that leaks a Redis
     * connection if it isn't closed.
     *
     * <p>{@code COUNT} of 256 is a balance: small enough that one page round-
     * trips quickly, large enough that the overhead per key is dominated by
     * the GETs that follow, not by the SCAN itself.
     */
    private List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(256).build();
        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
}
