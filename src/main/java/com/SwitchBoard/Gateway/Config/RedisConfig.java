package com.SwitchBoard.Gateway.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * Redis configuration for idempotency management.
 * 
 * Production considerations:
 * - Uses Lettuce (reactive Redis client) - comes with spring-boot-starter-data-redis-reactive
 * - Connection pooling configured in application.yml
 * - For production: configure Redis Sentinel or Cluster for HA
 * - For production: enable SSL/TLS for Redis connections
 * - For production: configure proper authentication
 */
@Configuration
public class RedisConfig {
    
    /**
     * Reactive Redis template for string operations.
     * Used by IdempotencyService for atomic key operations.
     * 
     * Why ReactiveStringRedisTemplate:
     * - Spring Cloud Gateway is fully reactive (WebFlux)
     * - Blocking Redis operations would break reactive chain
     * - String keys/values are sufficient for idempotency use case
     */
    @Bean
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }
}
