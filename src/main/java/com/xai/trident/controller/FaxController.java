package com.xai.trident.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
import com.xai.trident.model.FaxMetadata;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.service.FaxEngineService;
import com.xai.trident.ratelimit.RateLimit;
import com.xai.trident.service.SmartAssistService;
import com.xai.trident.upload.FaxUploadService;
import com.xai.trident.util.LogSanitizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/fax")
@PreAuthorize("hasRole('USER')")
@Validated
public class FaxController {

    private static final Logger logger = LoggerFactory.getLogger(FaxController.class);
    private final FaxEngineService faxEngineService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FaxLogRepository faxLogRepository;
    private final ContactRepository contactRepository;
    private final FaxMetadataRepository faxMetadataRepository;
    private final SmartAssistService smartAssistService;
    private final ObjectMapper objectMapper;
    private final FaxUploadService faxUploadService;

    @Autowired
    public FaxController(FaxEngineService faxEngineService,
                         RedisTemplate<String, Object> redisTemplate,
                         FaxLogRepository faxLogRepository,
                         ContactRepository contactRepository,
                         FaxMetadataRepository faxMetadataRepository,
                         SmartAssistService smartAssistService,
                         ObjectMapper objectMapper,
                         FaxUploadService faxUploadService) {
        this.faxEngineService = faxEngineService;
        this.redisTemplate = redisTemplate;
        this.faxLogRepository = faxLogRepository;
        this.contactRepository = contactRepository;
        this.faxMetadataRepository = faxMetadataRepository;
        this.smartAssistService = smartAssistService;
        this.objectMapper = objectMapper;
        this.faxUploadService = faxUploadService;
        logger.info("FaxController initialized with all dependencies");
    }

    /**
     * Multipart upload endpoint — the new entrypoint for any send-fax flow.
     * Returns an opaque upload ID that subsequent calls
     * ({@link #sendFax}, {@link #autoSendFax}, {@link #processInput}, and
     * the admin send endpoint) reference instead of an on-disk path.
     *
     * <p>Together with the server-side resolution in
     * {@link FaxUploadService}, this closes audit finding 1.5 (user-
     * controlled file paths).
     */
    @PostMapping(value = "/uploads", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, String>> uploadFax(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws IOException {
        String username = auth.getName();
        logger.info("User '{}' uploading fax (filename={}, size={} bytes)",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(file.getOriginalFilename()),
                file.getSize());
        String uploadId = faxUploadService.store(file);
        return ResponseEntity.ok(Map.of("uploadId", uploadId));
    }

    // Upload-error → HTTP status mapping lives in
    // com.xai.trident.upload.UploadExceptionHandler (a @RestControllerAdvice),
    // so AdminController's send endpoint gets the same 4xx behavior without
    // duplicating handlers across controllers.

    @GetMapping("/status")
    // No @Transactional — Redis ping only, no JPA. (2.18)
    public ResponseEntity<String> getSystemStatus() {
        logger.info("Checking fax system status...");
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return ResponseEntity.ok("Fax system online!");
        } catch (Exception e) {
            logger.error("Fax system status check failed: {}", e.getMessage());
            return ResponseEntity.status(503).body("Fax system offline: " + e.getMessage());
        }
    }

    /**
     * Send-fax request body. Previously this carried a raw {@code filePath}
     * the server would open directly — which let a caller name any file on
     * the server's disk. Now it carries an opaque {@code uploadId} produced
     * by {@code POST /api/fax/uploads} which the server resolves through
     * {@link FaxUploadService} to a path inside the server-controlled upload
     * directory.
     */
    public static class FaxRequestDTO {
        @NotBlank(message = "Fax number cannot be blank")
        private String faxNumber;
        @NotBlank(message = "Upload ID cannot be blank")
        private String uploadId;

        public String getFaxNumber() { return faxNumber; }
        public void setFaxNumber(String faxNumber) { this.faxNumber = faxNumber; }
        public String getUploadId() { return uploadId; }
        public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    }

    @PostMapping("/send")
    @RateLimit(key = "fax:send:#{authentication.name}", rate = 10, period = 60)
    // No @Transactional — controller writes nothing; the async service call
    // owns its own transaction. The previous wrapper held a JPA connection
    // for the duration of the (synchronous) request just to delegate. (2.18)
    public ResponseEntity<Map<String, String>> sendFax(
            @Valid @RequestBody FaxRequestDTO request,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' requested fax to {} with upload {}",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(request.getFaxNumber()),
                LogSanitizer.sanitize(request.getUploadId()));
        Map<String, String> response = new HashMap<>();
        String faxId = "fax_" + UUID.randomUUID();
        // Resolve the opaque ID to a server-controlled path. Bad IDs
        // surface as UploadNotFoundException -> 404 (mapped by the
        // UploadExceptionHandler @RestControllerAdvice), never as a path
        // the service might open. This is the audit-1.5 chokepoint.
        // Deliberately OUTSIDE the try-catch below so upload errors are
        // not swallowed and remapped to 500.
        String resolvedPath = faxUploadService.resolveToString(request.getUploadId());
        try {
            // Get-or-create for the Contact is owned by FaxEngineService.sendFax
            // now (via findOrCreateContact). Previously the controller wrote a
            // "Unknown" placeholder here *before* the async send fired, and
            // sendFax did the same lookup-or-create internally — two competing
            // writes that could race on the unique fax_number constraint and
            // could also clobber a real Contact name with "Unknown". See
            // audit finding 2.12.
            faxEngineService.sendFaxAsync(request.getFaxNumber(), resolvedPath)
                .thenRun(() -> logger.info("Fax send completed for ID: {}", faxId));
            response.put("faxId", faxId);
            response.put("message", "Fax send request queued successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("faxId", faxId);
            response.put("error", "Failed to send fax: " + e.getMessage());
            logger.error("Fax send failed for ID {}: {}", faxId, e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    // FIX: findByFaxId requires Pageable — use unpaged() and unwrap to List
    @GetMapping("/status/{faxId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getFaxStatus(@PathVariable String faxId, Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching status for fax ID: {}", username, faxId);
        Map<String, Object> statusData = new HashMap<>();

        String status = (String) redisTemplate.opsForValue().get(faxId + ":status");
        List<FaxLog> logs = faxLogRepository.findByFaxId(faxId, Pageable.unpaged()).getContent();

        if (status == null && logs.isEmpty()) {
            logger.warn("No status or logs found for fax ID: {}", faxId);
            return ResponseEntity.status(404).body(Map.of("error", "Fax ID not found"));
        }
        if (status == null && !logs.isEmpty()) {
            status = logs.get(0).getStatus();
            statusData.put("errorMessage", logs.get(0).getErrorMessage());
            if (logs.get(0).getContact() != null) {
                statusData.put("contactName", logs.get(0).getContact().getName());
            }
        } else {
            Long contactId = (Long) redisTemplate.opsForValue().get(faxId + ":contactId");
            if (contactId != null) {
                contactRepository.findById(contactId).ifPresent(contact ->
                    statusData.put("contactName", contact.getName()));
            }
        }

        statusData.put("status", status);
        statusData.put("faxNumber", redisTemplate.opsForValue().get(faxId + ":number"));
        statusData.put("pages", redisTemplate.opsForValue().get(faxId + ":pages"));
        statusData.put("file", redisTemplate.opsForValue().get(faxId + ":file"));
        statusData.put("logHistory", logs);

        Long metadataId = (Long) redisTemplate.opsForValue().get(faxId + ":metadataId");
        if (metadataId != null) {
            faxMetadataRepository.findById(metadataId).ifPresent(metadata -> {
                statusData.put("fileName", metadata.getFileName());
                statusData.put("fileType", metadata.getFileType());
                statusData.put("fileSize", metadata.getFileSize());
            });
        }

        logger.info("Fax status retrieved for user '{}': {}", username, statusData);
        return ResponseEntity.ok(statusData);
    }

    @PostMapping("/process-input")
    // No @Transactional — controller delegates to FaxEngineService.processInput
    // which is itself @Transactional; no DB work happens here. (2.18)
    public ResponseEntity<Map<String, String>> processInput(
            @RequestParam @NotBlank(message = "uploadId cannot be blank") String uploadId,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' processing upload: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(uploadId));
        Map<String, String> response = new HashMap<>();
        // Resolve to a server-controlled path before the try-catch — same
        // audit-1.5 chokepoint as /send. UploadNotFoundException must
        // escape so UploadExceptionHandler can return 404.
        String resolvedPath = faxUploadService.resolveToString(uploadId);
        try {
            // The controller used to generate its own faxId and then read
            // `<faxId>:extractedText` from Redis — but the service generated
            // an entirely different faxId, so the read always returned null.
            // Now the service returns the data it produced and we surface it.
            FaxEngineService.ProcessInputResult result = faxEngineService.processInput(resolvedPath);
            response.put("faxId", result.faxId());
            response.put("message", "Input processing started");
            if (result.extractedText() != null && !result.extractedText().isEmpty()) {
                response.put("extractedText", result.extractedText());
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Failed to process input: " + e.getMessage());
            logger.error("Input processing failed: {}", LogSanitizer.sanitize(e.getMessage()));
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/logs/by-number/{faxNumber}")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<FaxLog>> getFaxLogsByNumber(
            @PathVariable String faxNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching logs for fax number: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(faxNumber));
        Pageable pageable = PageRequest.of(page, size);
        Page<FaxLog> logs = faxLogRepository.findByFaxNumber(faxNumber, pageable);
        // Empty page is a valid result — not a missing resource. Return 200
        // with an empty content array so clients deserializing Page<FaxLog>
        // don't have to special-case a null body. 404 stays reserved for
        // "this URL doesn't address a known resource".
        return ResponseEntity.ok(logs);
    }

    // FIX: findByTimestampBetween requires Pageable
    @GetMapping("/logs/recent")
    @Transactional(readOnly = true)
    public ResponseEntity<List<FaxLog>> getRecentLogs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching logs between {} and {}", username, start, end);
        List<FaxLog> logs = faxLogRepository.findByTimestampBetween(start, end, Pageable.unpaged()).getContent();
        // Empty result range is a valid answer, not a missing resource — 200 OK.
        return ResponseEntity.ok(logs);
    }

    @GetMapping("/predict-contact")
    // No @Transactional — SmartAssistService.predictContact has its own
    // @Transactional(readOnly = true). (2.18)
    public ResponseEntity<Map<String, String>> predictContact(
            @RequestParam @NotBlank(message = "Partial input cannot be blank") String partialInput,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' requesting contact prediction for: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(partialInput));
        Map<String, String> response = new HashMap<>();
        try {
            String predictedFaxNumber = smartAssistService.predictContact(partialInput);
            response.put("predictedFaxNumber", predictedFaxNumber);
            response.put("status", predictedFaxNumber.startsWith("Prediction error") ||
                                   predictedFaxNumber.equals("No matching contact found") ? "warning" : "success");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", "Prediction failed: " + e.getMessage());
            logger.error("Prediction failed for input '{}': {}",
                    LogSanitizer.sanitize(partialInput), LogSanitizer.sanitize(e.getMessage()));
            return ResponseEntity.status(500).body(response);
        }
    }

    @PostMapping("/auto-send")
    @RateLimit(key = "fax:auto-send:#{authentication.name}", rate = 5, period = 60)
    // No @Transactional — SmartAssistService.autoSendFaxAsync owns its own
    // transaction; controller does no DB work. (2.18)
    public ResponseEntity<Map<String, String>> autoSendFax(
            @RequestParam @NotBlank(message = "Partial input cannot be blank") String partialInput,
            @RequestParam @NotBlank(message = "uploadId cannot be blank") String uploadId,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' requesting auto-send with partial input: {} and upload: {}",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(partialInput),
                LogSanitizer.sanitize(uploadId));
        Map<String, String> response = new HashMap<>();
        String faxId = "fax_" + UUID.randomUUID();
        // See /send for why resolveToString lives outside the try-catch.
        String resolvedPath = faxUploadService.resolveToString(uploadId);
        try {
            smartAssistService.autoSendFaxAsync(partialInput, resolvedPath)
                .thenRun(() -> logger.info("Auto-send completed for ID: {}", faxId));
            response.put("faxId", faxId);
            response.put("message", "Auto-send request queued successfully");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("faxId", faxId);
            response.put("error", "Auto-send failed: " + e.getMessage());
            logger.error("Auto-send failed for ID {}: {}", faxId, LogSanitizer.sanitize(e.getMessage()));
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/contacts/search")
    @Transactional(readOnly = true)
    public ResponseEntity<Page<Contact>> searchContacts(
            @RequestParam String name,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' searching contacts by name: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(name));
        Pageable pageable = PageRequest.of(page, size);
        Page<Contact> contacts = contactRepository.findByNameContainingIgnoreCase(name, pageable);
        // Empty search result is a 200, not a 404. See getFaxLogsByNumber above.
        return ResponseEntity.ok(contacts);
    }

    @GetMapping("/metadata/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<FaxMetadata> getMetadata(@PathVariable Long id, Authentication auth) {
        String username = auth.getName();
        logger.info("User '{}' fetching metadata for ID: {}", username, id);
        return faxMetadataRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> {
                logger.warn("No metadata found for ID: {}", id);
                return ResponseEntity.status(404).body(null);
            });
    }
}
