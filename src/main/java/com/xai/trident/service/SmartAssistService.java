package com.xai.trident.service;

import com.xai.trident.config.WebSocketConfig.FaxUpdateHandler;
import com.xai.trident.model.Contact;
import com.xai.trident.repository.ContactRepository;
import com.xai.trident.repository.FaxLogRepository;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@Validated
public class SmartAssistService {

    private static final Logger logger = LoggerFactory.getLogger(SmartAssistService.class);

    /**
     * Maximum number of candidate contacts considered for the heuristic
     * prediction. Picked to be generous for any realistic directory while
     * still giving us a bounded query and predictable latency. Bigger
     * numbers move the bottleneck from "tablefull memory load" to "trigram
     * index would help" — see audit finding 2.9.
     */
    private static final int CANDIDATE_LIMIT = 200;

    private final ContactRepository contactRepository;
    private final FaxLogRepository faxLogRepository;
    private final FaxEngineService faxEngineService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FaxUpdateHandler faxUpdateHandler;

    @Autowired
    public SmartAssistService(ContactRepository contactRepository,
                              FaxLogRepository faxLogRepository,
                              FaxEngineService faxEngineService,
                              RedisTemplate<String, Object> redisTemplate,
                              FaxUpdateHandler faxUpdateHandler) {

        this.contactRepository = contactRepository;
        this.faxLogRepository = faxLogRepository;
        this.faxEngineService = faxEngineService;
        this.redisTemplate = redisTemplate;
        this.faxUpdateHandler = faxUpdateHandler;

        logger.info("SmartAssistService initialized with all dependencies");
    }

    @Transactional(readOnly = true)
    public String predictContact(@NotBlank(message = "Partial input cannot be blank") String partialInput) {

        String username = getCurrentUsername();
        logger.info("User '{}' predicting contact from partial input: {}", username, partialInput);

        String cacheKey = "predict:" + partialInput;

        String cached = (String) redisTemplate.opsForValue().get(cacheKey);

        if (cached != null && !cached.startsWith("Prediction error") && !cached.equals("No matching contact found")) {
            logger.info("Returning cached prediction for '{}': {}", partialInput, cached);
            return cached;
        }

        try {

            // ML prediction branch removed — `invokeXaiModel(...)` was a stub
            // that always returned null, so the surrounding `if` was dead
            // code (audit 2.20). Reintroduce a real model call here when
            // SmartAssist is actually implemented.

            // Heuristic prediction.
            //
            // Name and fax-number candidate sets are both pulled via paged
            // queries with an explicit cap. The previous implementation called
            // contactRepository.findAll() and filtered in JVM memory, which is
            // an unbounded full-table load and starts misbehaving badly past
            // a few thousand contacts (audit finding 2.9). The cap here is
            // intentionally generous so heuristic quality is not perceptibly
            // reduced for a directory of any plausible size; if a deployment
            // has more than CANDIDATE_LIMIT matches we'd want a real index
            // (trigram, full-text) rather than a bigger cap.
            Pageable candidatePage = Pageable.ofSize(CANDIDATE_LIMIT);

            List<Contact> nameMatches =
                    contactRepository.findByNameContainingIgnoreCase(partialInput, candidatePage).getContent();

            List<Contact> numberMatches =
                    contactRepository.findByFaxNumberContaining(partialInput, candidatePage).getContent();

            List<Contact> allMatches = new ArrayList<>(nameMatches);

            numberMatches.forEach(match -> {
                if (!allMatches.contains(match)) {
                    allMatches.add(match);
                }
            });

            if (allMatches.isEmpty()) {

                logger.info("No matching contacts found for input: {}", partialInput);

                String result = "No matching contact found";
                cachePrediction(cacheKey, result);

                return result;
            }

            // Score each candidate using a COUNT query for history depth.
            // Previously this called findByFaxNumber(..., Pageable.unpaged())
            // inside the loop, loading every log row for every candidate
            // (a classic N+1 with unbounded result sets) just to read
            // getTotalElements(). countByFaxNumber asks the DB for the count
            // directly (2.9).
            Optional<Contact> bestMatch = allMatches
                    .stream()
                    .map(c -> {
                        long historyCount = faxLogRepository.countByFaxNumber(c.getFaxNumber());
                        int score = (int) (historyCount * 10);
                        score += c.getName().toLowerCase().contains(partialInput.toLowerCase()) ? 5 : 0;
                        score += c.getFaxNumber().contains(partialInput) ? 3 : 0;
                        return new AbstractMap.SimpleEntry<>(c, score);
                    })
                    .max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(Map.Entry::getKey);

            String predictedFaxNumber =
                    bestMatch.isPresent()
                            ? bestMatch.get().getFaxNumber()
                            : allMatches.get(0).getFaxNumber();

            logger.info("Predicted contact with score-based logic: {}", predictedFaxNumber);

            cachePrediction(cacheKey, predictedFaxNumber);

            return predictedFaxNumber;

        } catch (Exception e) {

            logger.error("Failed to predict contact for input '{}': {}", partialInput, e.getMessage());

            String result = "Prediction error: " + e.getMessage();

            cachePrediction(cacheKey, result);

            return result;
        }
    }

    @Async
    @Transactional
    public CompletableFuture<Void> autoSendFaxAsync(
            @NotBlank(message = "Partial input cannot be blank") String partialInput,
            @NotBlank(message = "File path cannot be blank") String filePath) {

        autoSendFax(partialInput, filePath);

        return CompletableFuture.completedFuture(null);
    }

    @Transactional
    public void autoSendFax(String partialInput, String filePath) {

        String username = getCurrentUsername();

        logger.info("User '{}' auto-sending fax with partial input: {} and file: {}",
                username, partialInput, filePath);

        String predictedFaxNumber = predictContact(partialInput);

        if (predictedFaxNumber.startsWith("Prediction error")
                || predictedFaxNumber.equals("No matching contact found")) {

            logger.warn("Auto-send aborted due to prediction failure: {}", predictedFaxNumber);

            broadcastFailure("Auto-send failed: No valid contact predicted for " + partialInput);

            return;
        }

        faxEngineService
                .sendFaxAsync(predictedFaxNumber, filePath)
                .thenRun(() -> {

                    logger.info("Auto-sent fax to predicted contact by '{}': {}",
                            username, predictedFaxNumber);

                    broadcastSuccess(
                            "Auto-sent fax to " + predictedFaxNumber +
                                    " based on input: " + partialInput);
                });
    }

    // invokeXaiModel(...) removed — was a stub always returning null. (2.20)

    private void cachePrediction(String cacheKey, String result) {

        redisTemplate.opsForValue()
                .set(cacheKey, result, 1, TimeUnit.HOURS);
    }

    private String getCurrentUsername() {

        return org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication() != null

                ? org.springframework.security.core.context.SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName()

                : "system";
    }

    private void broadcastSuccess(String message) {

        Map<String, Object> update = new HashMap<>();

        update.put("status", "success");
        update.put("message", message);
        update.put("timestamp", LocalDateTime.now().toString());

        faxUpdateHandler.broadcast(update);
    }

    private void broadcastFailure(String message) {

        Map<String, Object> update = new HashMap<>();

        update.put("status", "failure");
        update.put("message", message);
        update.put("timestamp", LocalDateTime.now().toString());

        faxUpdateHandler.broadcast(update);
    }
}
