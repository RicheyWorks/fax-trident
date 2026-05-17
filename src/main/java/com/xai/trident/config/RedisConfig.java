package com.xai.trident.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import com.xai.trident.config.WebSocketConfig.FaxUpdateHandler;

/**
 * Redis configuration for Fax Trident.
 *
 * <p>The {@link RedisConnectionFactory} is intentionally NOT defined here —
 * Spring Boot's auto-configuration provides it from the standard
 * {@code spring.data.redis.*} properties (host, port, timeout, username,
 * password, ssl, sentinel, cluster, etc.). The previous hand-rolled
 * factory read {@code spring.redis.*} (the Spring Boot 2.x property root)
 * and was therefore silently disconnected from configuration after the
 * Boot 3 upgrade — every deployment quietly connected to localhost.
 * Letting auto-config own the factory eliminates that whole class of bug.
 *
 * <p>What stays here:
 * <ul>
 *   <li>{@link RedisTemplate} with String keys + Jackson-JSON values,
 *       which is what the rest of the codebase actually uses.</li>
 *   <li>A pub/sub listener for the {@code fax-updates} channel that
 *       fans out into the WebSocket {@link FaxUpdateHandler}.</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfig.class);

    /** Redis pub/sub channel used to broadcast fax-state changes to all nodes. */
    public static final String FAX_UPDATES_CHANNEL = "fax-updates";

    @Autowired
    private FaxUpdateHandler faxUpdateHandler; // For pub/sub integration

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        logger.info("Configuring RedisTemplate for Fax Trident...");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // String keys, JSON values — matches how callers across the codebase use the template.
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setDefaultSerializer(new GenericJackson2JsonRedisSerializer());

        // Required for MULTI/EXEC support inside @Transactional methods that touch Redis.
        template.setEnableTransactionSupport(true);

        template.afterPropertiesSet();

        // Eager ping: fail-fast at startup if Redis is unreachable, rather than
        // letting the first request fail. (NOTE: this also means the app will
        // not boot if Redis is down — see audit finding "eager startup ping"
        // for the trade-off and how to make it lazy if you prefer.)
        try {
            template.getConnectionFactory().getConnection().ping();
            logger.info("Redis connection established successfully");
        } catch (Exception e) {
            logger.error("Failed to connect to Redis: {}", e.getMessage());
            throw new RuntimeException("Redis connection failure", e);
        }

        logger.info("RedisTemplate configured with JSON serialization and transaction support");
        return template;
    }

    // Pub/Sub message listener for fax updates
    @Bean
    public MessageListenerAdapter messageListener() {
        return new MessageListenerAdapter(new RedisMessageListener(faxUpdateHandler));
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory,
                                                        MessageListenerAdapter messageListener) {
        logger.info("Configuring Redis pub/sub for fax updates on channel '{}'...", FAX_UPDATES_CHANNEL);
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(messageListener, new ChannelTopic(FAX_UPDATES_CHANNEL));
        return container;
    }

    // Inner class for Redis message handling
    public static class RedisMessageListener {
        private final FaxUpdateHandler faxUpdateHandler;

        public RedisMessageListener(FaxUpdateHandler faxUpdateHandler) {
            this.faxUpdateHandler = faxUpdateHandler;
        }

        public void handleMessage(String message) {
            logger.info("Received Redis pub/sub message: {}", message);
            faxUpdateHandler.broadcast(message);
        }
    }
}
