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

/**
 * Contact-suggestion service.
 *
 * <p>Renamed from {@code SmartAssistService} on 2026-05-17 (audit tech-debt #2
 * close-out). The old name implied an ML / AI assistant; the implementation is
 * — and has always been — a deterministic, score-based fuzzy matcher.
 * Suggesting that this was an ML system was inviting both wrong expectations
 * and dead-stub maintenance (the {@code invokeXaiModel} stub removed under
 * audit 2.20 was a placeholder for the model that never materialized).
 *
 * <h2>How the suggestion works</h2>
 * Given a partial input string (substring of a contact's name or fax number):
 *
 * <ol>
 *   <li>Pull up to {@link #CANDIDATE_LIMIT} candidate contacts whose name
 *       matches case-insensitively, plus up to the same many whose fax
 *       number contains the input.</li>
 *   <li>Score each unique candidate: {@code history_count * 10 +
 *       (name contains ? 5 : 0) + (fax_number contains ? 3 : 0)}.</li>
 *   <li>Return the highest-scoring candidate's fax number.</li>
 * </ol>
 *
 * <p>No machine learning. No model. No training. If you want a real model,
 * this is the right seam to add one — score against the heuristic and the
 * model and decide between them, or replace the scorer wholesale.
 *
 * <h2>API stability</h2>
 * The public HTTP endpoints that surface this service —
 * {@code POST /api/fax/predict-contact} and {@code POST /api/fax/auto-send},
 * plus {@code GET /api/admin/prediction-analytics} — keep their old URLs to
 * avoid a breaking change for clients. Only the Java class name and the
 * Redis key namespace ({@code predict:} → {@code suggest:}) changed.
 *
 * <p>Operator action: existing cached predictions under {@code predict:*} keys
 * are orphaned (the new code reads/writes {@code suggest:*}). Either let them
 * age out (1-hour TTL) or {@code SCAN 0 MATCH predict:* COUNT 100} + {@code DEL}
 * once to free the memory immediately.
 */
@Service
@Validated
public class ContactSuggestionService {

    private static final Logger logger = LoggerFactory.getLogger(ContactSuggestionService.class);

    /**
     * Maximum number of candidate contacts considered per pulled candidate
     * set. Picked to be generous for any realistic directory while still
     * giving us a bounded query and predictable latency. Bigger numbers
     * move the bottleneck from "tablefull memory load" to "trigram index
     * would help" — see audit finding 2.9.
     */
    private static final int CANDIDATE_LIMIT = 200;

    /**
     * Redis namespace for cached suggestions. Renamed from {@code predict:}
     * along with the class rename; see class javadoc for the operator-side
     * cleanup note.
     */
    private static final String CACHE_KEY_PREFIX = "suggest:";

    private final ContactRepository contactRepository;
    private final FaxLogRepository faxLogRepository;
    private final FaxEngineService faxEngineService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FaxUpdateHandler faxUpdateHandler;

    @Autowired
    public ContactSuggestionService(ContactRepository contactRepository,
                                    FaxLogRepository faxLogRepository,
                                    FaxEngineService faxEngineService,
                                    RedisTemplate<String, Object> redisTemplate,
                                    FaxUpdateHandler faxUpdateHandler) {

        this.contactRepository = contactRepository;
        this.faxLogRepository = faxLogRepository;
        this.faxEngineService = faxEngineService;
        this.redisTemplate = redisTemplate;
        this.faxUpdateHandler = faxUpdateHandler;

        logger.info("ContactSuggestionService initialized with all dependencies");
    }

    /**
     * Returns the best-scoring contact's fax number for the given partial
     * input, or one of two sentinel strings:
     * <ul>
     *   <li>{@code "No matching contact found"} — no candidate matched.</li>
     *   <li>{@code "Suggestion error: <msg>"} — something blew up.</li>
     * </ul>
     *
     * <p>Failure sentinels are deliberately NOT cached — the previous
     * implementation cached them, then excluded them on cache-read, so the
     * cache filled with values that were never served. See class javadoc.
     */
    @Transactional(readOnly = true)
    public String suggestContact(@NotBlank(message = "Partial input cannot be blank") String partialInput) {

        String username = getCurrentUsername();
        logger.info("User '{}' suggesting contact from partial input: {}", username, partialInput);

        String cacheKey = CACHE_KEY_PREFIX + partialInput;

        String cached = (String) redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            // We never cache failure sentinels (see below), so any cached
            // value is a real suggestion safe to return.
            logger.info("Returning cached suggestion for '{}': {}", partialInput, cached);
            return cached;
        }

        try {
            // Pull candidates via paged queries with explicit caps. The
            // previous implementation called findAll() and filtered in JVM
            // memory — unbounded full-table load that misbehaves past a few
            // thousand contacts (audit 2.9). The cap here is intentionally
            // generous so suggestion quality is not perceptibly reduced for
            // a directory of any plausible size; if a deployment has more
            // than CANDIDATE_LIMIT matches we'd want a real index (trigram,
            // full-text) rather than a bigger cap.
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
                // Intentionally NOT cached — see class javadoc.
                return "No matching contact found";
            }

            // Score each candidate. countByFaxNumber asks the DB for the count
            // directly instead of loading every log row just to read
            // getTotalElements() (audit 2.9).
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

            String suggestedFaxNumber =
                    bestMatch.isPresent()
                            ? bestMatch.get().getFaxNumber()
                            : allMatches.get(0).getFaxNumber();

            logger.info("Suggested contact via score-based heuristic: {}", suggestedFaxNumber);
            cacheSuggestion(cacheKey, suggestedFaxNumber);
            return suggestedFaxNumber;

        } catch (Exception e) {
            logger.error("Failed to suggest contact for input '{}': {}", partialInput, e.getMessage());
            // Intentionally NOT cached — see class javadoc.
            return "Suggestion error: " + e.getMessage();
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

        String suggestedFaxNumber = suggestContact(partialInput);

        if (suggestedFaxNumber.startsWith("Suggestion error")
                || suggestedFaxNumber.equals("No matching contact found")) {

            logger.warn("Auto-send aborted due to suggestion failure: {}", suggestedFaxNumber);
            broadcastFailure("Auto-send failed: No valid contact suggested for " + partialInput);
            return;
        }

        faxEngineService
                .sendFaxAsync(suggestedFaxNumber, filePath)
                .thenRun(() -> {
                    logger.info("Auto-sent fax to suggested contact by '{}': {}",
                            username, suggestedFaxNumber);
                    broadcastSuccess(
                            "Auto-sent fax to " + suggestedFaxNumber +
                                    " based on input: " + partialInput);
                });
    }

    private void cacheSuggestion(String cacheKey, String result) {
        redisTemplate.opsForValue().set(cacheKey, result, 1, TimeUnit.HOURS);
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
