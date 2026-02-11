package com.SwitchBoard.Gateway.Service;

import com.SwitchBoard.Gateway.Model.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;


@Service
public class IdempotencyService {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String KEY_PREFIX = "idempotency:";
    
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    
    public IdempotencyService(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public Mono<Optional<IdempotencyRecord>> tryAcquire(String idempotencyKey, Duration inProgressTtl) {
        log.info("IdempotencyService : tryAcquire : Starting with key: {}", idempotencyKey);
        String redisKey = KEY_PREFIX + idempotencyKey;
        IdempotencyRecord record = IdempotencyRecord.inProgress();
        
        return serialize(record)
                .flatMap(json -> redisTemplate.opsForValue()
                        .setIfAbsent(redisKey, json, inProgressTtl)
                        .map(acquired -> {
                            if (Boolean.TRUE.equals(acquired)) {
                                return Optional.of(record);
                            } else {
                                log.warn("IdempotencyService : tryAcquire : Key already exists: {}", idempotencyKey);
                                return Optional.<IdempotencyRecord>empty();
                            }
                        }))
                .doOnSuccess(result -> log.info("IdempotencyService : tryAcquire : Completed for key: {}", idempotencyKey))
                .onErrorResume(e -> {
                    log.error("IdempotencyService : tryAcquire : Failed for key: {}", idempotencyKey, e);
                    return Mono.just(Optional.empty());
                });
    }

    public Mono<Optional<IdempotencyRecord>> fetch(String idempotencyKey) {
        log.info("IdempotencyService : fetch : Starting for key: {}", idempotencyKey);
        String redisKey = KEY_PREFIX + idempotencyKey;
        
        return redisTemplate.opsForValue()
                .get(redisKey)
                .flatMap(json -> deserialize(json)
                        .map(Optional::of))
                .defaultIfEmpty(Optional.empty())
                .doOnSuccess(result -> log.info("IdempotencyService : fetch : Completed for key: {}", idempotencyKey))
                .onErrorResume(e -> {
                    log.error("IdempotencyService : fetch : Failed for key: {}", idempotencyKey, e);
                    return Mono.just(Optional.empty());
                });
    }

    public Mono<Void> complete(String idempotencyKey, Integer httpStatus, 
                                String responseBody, java.time.Instant startedAt, Duration completedTtl) {
        log.info("IdempotencyService : complete : Starting for key: {} with status: {}", idempotencyKey, httpStatus);
        String redisKey = KEY_PREFIX + idempotencyKey;
        IdempotencyRecord record = IdempotencyRecord.completed(httpStatus, responseBody, startedAt);
        
        return serialize(record)
                .flatMap(json -> redisTemplate.opsForValue()
                        .set(redisKey, json, completedTtl)
                        .doOnSuccess(success -> log.info("IdempotencyService : complete : Completed successfully for key: {}", idempotencyKey))
                        .then())
                .onErrorResume(e -> {
                    log.error("IdempotencyService : complete : Failed for key: {}", idempotencyKey, e);
                    // Don't propagate error - response already sent to client
                    return Mono.empty();
                });
    }
    

    public boolean isExpired(IdempotencyRecord record, Duration inProgressTtl) {
        return record.isExpired(inProgressTtl.getSeconds());
    }
    
    private Mono<String> serialize(IdempotencyRecord record) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(record))
                .onErrorMap(JsonProcessingException.class, 
                        e -> new RuntimeException("Failed to serialize IdempotencyRecord", e));
    }
    
    private Mono<IdempotencyRecord> deserialize(String json) {
        return Mono.fromCallable(() -> objectMapper.readValue(json, IdempotencyRecord.class))
                .onErrorMap(JsonProcessingException.class,
                        e -> new RuntimeException("Failed to deserialize IdempotencyRecord", e));
    }
}
