package com.xai.trident.service;

import com.xai.trident.config.WebSocketConfig.FaxUpdateHandler;
import com.xai.trident.model.Contact;
import com.xai.trident.model.FaxLog;
import com.xai.trident.model.FaxMetadata;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import com.xai.trident.repository.FaxMetadataRepository;
import com.xai.trident.util.LogSanitizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class FaxEngineService {

    private static final Logger logger = LoggerFactory.getLogger(FaxEngineService.class);

    private final RedisTemplate<String, Object> redisTemplate;
    private final FaxUpdateHandler faxUpdateHandler;
    private final ContactRepository contactRepository;
    private final FaxLogRepository faxLogRepository;
    private final FaxMetadataRepository faxMetadataRepository;
    private final PdfProcessingService pdfProcessingService;
    private final ObjectMapper objectMapper;

    public FaxEngineService(RedisTemplate<String, Object> redisTemplate,
                            FaxUpdateHandler faxUpdateHandler,
                            ContactRepository contactRepository,
                            FaxLogRepository faxLogRepository,
                            FaxMetadataRepository faxMetadataRepository,
                            PdfProcessingService pdfProcessingService,
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.faxUpdateHandler = faxUpdateHandler;
        this.contactRepository = contactRepository;
        this.faxLogRepository = faxLogRepository;
        this.faxMetadataRepository = faxMetadataRepository;
        this.pdfProcessingService = pdfProcessingService;
        this.objectMapper = objectMapper;
        logger.info("FaxEngineService initialized with all dependencies");
    }

    /**
     * Result of {@link #processInput(String)} — the generated fax ID and any
     * text extracted from the input file. Returned to the caller so the
     * controller doesn't have to guess which Redis key the service wrote to.
     *
     * <p>{@code extractedText} is the empty string (never null) when the
     * input wasn't a readable PDF or no text could be pulled from it.
     */
    public record ProcessInputResult(String faxId, String extractedText) {}

    /**
     * Processes a new fax input file (used by inbound listener or manual upload).
     *
     * <p>Returns the generated {@code faxId} and any extracted PDF text so
     * callers can stop trying to guess the service-internal Redis key —
     * the previous void return forced the controller to make up its own
     * unrelated {@code faxId} and read from a key that was never written.
     */
    @Transactional
    public ProcessInputResult processInput(String input) {
        String username = getCurrentUsername();
        logger.info("User '{}' processing input: {}",
                LogSanitizer.sanitize(username), LogSanitizer.sanitize(input));

        String faxId = "fax_" + UUID.randomUUID();
        String extractedText = "";

        try {
            FaxLog processingLog = new FaxLog(faxId, "processing", null, null);
            processingLog.setCreatedBy(username);
            faxLogRepository.save(processingLog);

            redisTemplate.opsForValue().set(faxId + ":status", "processing", 1, TimeUnit.HOURS);
            broadcastStatus(faxId, "processing", "Processing new fax input: " + input);

            File inputFile = new File(input);
            if (inputFile.exists() && input.endsWith(".pdf")) {
                extractedText = pdfProcessingService.extractTextFromPdf(input);
                redisTemplate.opsForValue().set(faxId + ":extractedText", extractedText, 1, TimeUnit.HOURS);
            }

            // (Previously: Thread.sleep(1000) to "simulate processing." Removed —
            // it held the @Transactional JPA connection open for a full second
            // per call, contributing to connection-pool exhaustion under load.
            // When a real processing step lands here, do its waiting outside
            // the transaction.)

            redisTemplate.opsForValue().set(faxId + ":status", "processed", 1, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(faxId + ":input", input, 1, TimeUnit.HOURS);

            FaxLog processedLog = new FaxLog(faxId, "processed", null, null);
            processedLog.setCreatedBy(username);
            faxLogRepository.save(processedLog);

            broadcastStatus(faxId, "processed", "Input processed" +
                    (extractedText.isEmpty() ? "" : " - Extracted text: " + extractedText));

            logger.info("Input processed successfully by '{}': {}", username, input);
        } catch (Exception e) {
            handleFailure(faxId, "failed", null, null, "Processing error: " + e.getMessage(), username);
        }

        return new ProcessInputResult(faxId, extractedText);
    }

    @Async
    @Transactional
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public CompletableFuture<Void> sendFaxAsync(String faxNumber, String filePath) {
        sendFax(faxNumber, filePath);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Main method to send a fax (called synchronously by tests and async wrapper).
     */
    @Transactional
    public void sendFax(String faxNumber, String filePath) {
        String username = getCurrentUsername();
        logger.info("User '{}' sending fax to {} from file: {}",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(faxNumber),
                LogSanitizer.sanitize(filePath));

        String faxId = "fax_" + UUID.randomUUID();
        String barcodePath = null;

        try {
            if (faxNumber == null || faxNumber.trim().isEmpty()) {
                throw new IllegalArgumentException("Fax number cannot be empty");
            }

            Contact contact = findOrCreateContact(faxNumber);

            // Redis: store contact ID (Long value)
            redisTemplate.opsForValue().set(faxId + ":contactId", contact.getId(), 1, TimeUnit.HOURS);

            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                throw new IOException("File not found or unreadable: " + filePath);
            }

            FaxLog sendingLog = new FaxLog(faxId, "sending", faxNumber, filePath);
            sendingLog.setContact(contact);
            sendingLog.setCreatedBy(username);
            faxLogRepository.save(sendingLog);

            redisTemplate.opsForValue().set(faxId + ":status", "sending", 1, TimeUnit.HOURS);
            redisTemplate.opsForValue().set(faxId + ":number", faxNumber, 1, TimeUnit.HOURS);
            broadcastStatus(faxId, "sending", "Sending fax to " + contact.getName() + " (" + faxNumber + ")");

            barcodePath = pdfProcessingService.generateBarcode(faxNumber + " " + faxId);
            redisTemplate.opsForValue().set(faxId + ":barcodePath", barcodePath, 1, TimeUnit.HOURS);

            try (PDDocument document = PDDocument.load(file)) {
                int pages = document.getNumberOfPages();
                logger.debug("Fax document has {} pages", pages);

                // (Previously: Thread.sleep(2000) to "simulate fax transmission." Removed —
                // see processInput for the same rationale. Real transmission should happen
                // outside @Transactional once it exists.)

                redisTemplate.opsForValue().set(faxId + ":pages", pages, 1, TimeUnit.HOURS);

                FaxMetadata metadata = new FaxMetadata(file.getName(), pages, "PDF", file.length());
                metadata.setFaxLog(sendingLog);
                metadata.setCreatedBy(username);
                faxMetadataRepository.save(metadata);

                // Redis: store metadata ID (Long value) – this is the second "anyLong" call the test sees
                redisTemplate.opsForValue().set(faxId + ":metadataId", metadata.getId(), 1, TimeUnit.HOURS);

                FaxLog sentLog = new FaxLog(faxId, "sent", faxNumber, filePath);
                sentLog.setContact(contact);
                sentLog.setCreatedBy(username);
                faxLogRepository.save(sentLog);

                redisTemplate.opsForValue().set(faxId + ":status", "sent", 1, TimeUnit.HOURS);
                broadcastStatus(faxId, "sent", "Fax sent to " + contact.getName() + " (" + faxNumber + ")");

                logger.info("Fax sent successfully by '{}' to {} from {}", username, faxNumber, filePath);
            }
        } catch (IOException | IllegalArgumentException e) {
            handleFailure(faxId, "failed", faxNumber, filePath, e.getMessage(), username, barcodePath);
        }
    }

    /**
     * Polls for (currently: simulates) an inbound fax. NOT scheduled directly
     * — the {@code @Scheduled} trigger lives in {@code InboundFaxSimulator},
     * which is only wired up under the {@code dev} profile. Production
     * deployments should never invoke this method until the simulation
     * (random {@code Math.random() > 0.8} branch below) is replaced with a
     * real listener.
     */
    @Transactional
    public void listenForInboundFax() {
        // ... (unchanged – your simulation logic is fine)
        String username = getCurrentUsername();
        logger.info("User '{}' starting inbound fax listener...", username);
        String faxId = "inbound_" + UUID.randomUUID();
        String barcodePath = null;
        try {
            FaxLog listeningLog = new FaxLog(faxId, "listening", null, null);
            listeningLog.setCreatedBy(username);
            faxLogRepository.save(listeningLog);
            redisTemplate.opsForValue().set(faxId + ":status", "listening", 1, TimeUnit.HOURS);
            broadcastStatus(faxId, "listening", "Listening for inbound faxes");

            if (Math.random() > 0.8) {
                String simulatedFile = "inbound_fax_" + UUID.randomUUID() + ".pdf";
                String simulatedFaxNumber = "+1202555" + (int)(Math.random() * 10000);
                Contact contact = contactRepository.findByFaxNumber(simulatedFaxNumber)
                        .orElseGet(() -> {
                            Contact newContact = new Contact("Inbound Contact", simulatedFaxNumber);
                            contactRepository.save(newContact);
                            logger.info("Created new contact for inbound fax: {}", simulatedFaxNumber);
                            return newContact;
                        });

                FaxLog receivedLog = new FaxLog(faxId, "received", simulatedFaxNumber, simulatedFile);
                receivedLog.setContact(contact);
                receivedLog.setCreatedBy(username);
                faxLogRepository.save(receivedLog);

                barcodePath = pdfProcessingService.generateBarcode(simulatedFaxNumber + " " + faxId);
                redisTemplate.opsForValue().set(faxId + ":barcodePath", barcodePath, 1, TimeUnit.HOURS);

                FaxMetadata metadata = new FaxMetadata(simulatedFile, 1, "PDF", 1024L);
                metadata.setFaxLog(receivedLog);
                metadata.setCreatedBy(username);
                faxMetadataRepository.save(metadata);

                redisTemplate.opsForValue().set(faxId + ":metadataId", metadata.getId(), 1, TimeUnit.HOURS);
                redisTemplate.opsForValue().set(faxId + ":status", "received", 1, TimeUnit.HOURS);
                redisTemplate.opsForValue().set(faxId + ":file", simulatedFile, 1, TimeUnit.HOURS);
                redisTemplate.opsForValue().set(faxId + ":number", simulatedFaxNumber, 1, TimeUnit.HOURS);
                redisTemplate.opsForValue().set(faxId + ":contactId", contact.getId(), 1, TimeUnit.HOURS);

                broadcastStatus(faxId, "received", "Inbound fax received from " + contact.getName() + " (" + simulatedFaxNumber + "): " + simulatedFile);
                logger.info("Simulated inbound fax received by '{}': {} from {}", username, simulatedFile, simulatedFaxNumber);
            }
        } catch (Exception e) {
            handleFailure(faxId, "stopped", null, null, "Inbound listener error: " + e.getMessage(), username, barcodePath);
        }
    }

    /**
     * Centralized get-or-create for a Contact addressed by fax number.
     * Replaces the two divergent get-or-create patterns the system used to
     * carry (one in {@link com.xai.trident.controller.FaxController#sendFax}
     * that pre-emptively wrote a {@code "Unknown"} placeholder, and one
     * inside {@link #sendFax(String, String)} itself). The duplicate write
     * could race on {@code Contact.faxNumber}'s unique constraint and the
     * pre-emptive {@code "Unknown"} write could clobber a real name set
     * elsewhere — audit finding 2.12.
     *
     * <p>If two threads call this concurrently for the same fax number, one
     * of them loses the unique-constraint race and {@code save()} throws a
     * {@link org.springframework.dao.DataIntegrityViolationException}. The
     * surrounding {@link Retryable} on {@link #sendFaxAsync(String, String)}
     * picks that up and retries, at which point the second call sees the
     * row from the first and the lookup succeeds.
     *
     * @param faxNumber the E.164-style number; never null/blank (validated upstream).
     * @return the existing or freshly-created {@link Contact}, always non-null.
     */
    @Transactional
    public Contact findOrCreateContact(String faxNumber) {
        return contactRepository.findByFaxNumber(faxNumber)
                .orElseGet(() -> {
                    Contact created = contactRepository.save(new Contact("Unknown", faxNumber));
                    logger.info("Created new contact for fax number: {}",
                            LogSanitizer.sanitize(faxNumber));
                    return created;
                });
    }

    @Transactional
    public void saveContact(String name, String faxNumber) {
        // ... (unchanged – your logic is correct)
        String username = getCurrentUsername();
        logger.info("User '{}' saving contact: {} - {}",
                LogSanitizer.sanitize(username),
                LogSanitizer.sanitize(name),
                LogSanitizer.sanitize(faxNumber));
        try {
            Contact contact = contactRepository.findByFaxNumber(faxNumber)
                    .orElseGet(() -> new Contact(name, faxNumber));
            if (!contact.getName().equals(name)) {
                contact.setName(name);
            }
            contact.setCreatedBy(username);
            contactRepository.save(contact);

            redisTemplate.opsForValue().set("contact:" + faxNumber, contact, 1, TimeUnit.HOURS);

            FaxLog contactLog = new FaxLog("contact_" + UUID.randomUUID(), "saved", faxNumber, null);
            contactLog.setContact(contact);
            contactLog.setCreatedBy(username);
            faxLogRepository.save(contactLog);

            broadcastStatus(contactLog.getFaxId(), "saved", "Contact saved: " + name + " (" + faxNumber + ")");
            logger.info("Contact saved successfully by '{}': {}", username, contact);
        } catch (Exception e) {
            handleFailure("contact_" + UUID.randomUUID(), "failed", faxNumber, null, e.getMessage(), username);
        }
    }

    public int getWebSocketConnectionCount() {
        return faxUpdateHandler.getConnectionCount();
    }

    private String getCurrentUsername() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null ?
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName() : "system";
    }

    private void broadcastStatus(String faxId, String status, String message) {
        Map<String, Object> update = new HashMap<>();
        update.put("faxId", faxId);
        update.put("status", status);
        update.put("message", message);
        update.put("timestamp", LocalDateTime.now().toString());
        faxUpdateHandler.broadcast(update);
    }

    private void handleFailure(String faxId, String status, String faxNumber, String filePath, String errorMessage, String username) {
        handleFailure(faxId, status, faxNumber, filePath, errorMessage, username, null);
    }

    private void handleFailure(String faxId, String status, String faxNumber, String filePath, String errorMessage, String username, String barcodePath) {
        FaxLog failedLog = new FaxLog(faxId, status, faxNumber, filePath, errorMessage);
        failedLog.setCreatedBy(username);
        Contact contact = faxNumber != null ? contactRepository.findByFaxNumber(faxNumber).orElse(null) : null;
        failedLog.setContact(contact);
        faxLogRepository.save(failedLog);

        redisTemplate.opsForValue().set(faxId + ":status", status, 1, TimeUnit.HOURS);
        broadcastStatus(faxId, status, "Operation " + status + ": " + errorMessage);

        logger.error("Operation '{}' failed by '{}': {}", status, username, errorMessage);
        if (barcodePath != null) pdfProcessingService.cleanupBarcode(barcodePath);
    }
}
