package com.SwitchBoard.Gateway.Filter;

import com.SwitchBoard.Gateway.Config.IdempotencyConfig;
import com.SwitchBoard.Gateway.Model.IdempotencyRecord;
import com.SwitchBoard.Gateway.Service.IdempotencyService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class IdempotencyGlobalFilter implements GlobalFilter, Ordered {
    
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    
    // Only these methods require idempotency - GET is naturally idempotent
    private static final Set<HttpMethod> IDEMPOTENT_METHODS = Set.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH
    );
    
    private final IdempotencyService idempotencyService;
    private final IdempotencyConfig idempotencyConfig;
    
    public IdempotencyGlobalFilter(IdempotencyService idempotencyService, 
                                    IdempotencyConfig idempotencyConfig) {
        this.idempotencyService = idempotencyService;
        this.idempotencyConfig = idempotencyConfig;
    }
    
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        
        // Fast path: Skip if not idempotent method or no idempotency key
        if (!requiresIdempotency(request)) {
            return chain.filter(exchange);
        }
        
        String idempotencyKey = extractIdempotencyKey(request);
        if (idempotencyKey == null) {
            return chain.filter(exchange);
        }
        
        log.info("Processing idempotent request: {} {} with key: {}", 
                request.getMethod(), request.getPath(), idempotencyKey);
        
        // Resolve TTL configuration for this endpoint
        IdempotencyConfig.TtlConfig ttlConfig = idempotencyConfig.resolve(
                request.getMethod().name(),
                request.getPath().value()
        );
        
        log.debug("Using TTL config for {} {}: IN_PROGRESS={}, COMPLETED={}", 
                request.getMethod(), request.getPath(), 
                ttlConfig.getInProgress(), ttlConfig.getCompleted());
        
        // Try to acquire the idempotency key atomically
        return idempotencyService.tryAcquire(idempotencyKey, ttlConfig.getInProgress())
                .flatMap(maybeRecord -> {
                    return maybeRecord.map(idempotencyRecord -> handleFirstRequest(exchange, chain, idempotencyKey,
                            idempotencyRecord, ttlConfig)).orElseGet(() -> handleRetryRequest(exchange, idempotencyKey, ttlConfig));
                });
    }
    

    private Mono<Void> handleFirstRequest(ServerWebExchange exchange, GatewayFilterChain chain,
                                           String idempotencyKey, IdempotencyRecord inProgressRecord,
                                           IdempotencyConfig.TtlConfig ttlConfig) {
        
        ServerHttpResponse originalResponse = exchange.getResponse();
        
        // Add industry-standard response headers for first request
        originalResponse.getHeaders().add(IDEMPOTENCY_KEY_HEADER, idempotencyKey);  // Echo back the key
        originalResponse.getHeaders().add("X-Idempotent-Replay", "false");  // Indicate NOT a replay
        
        // Decorate response to capture body before sending to client
        ResponseCaptureDecorator decoratedResponse = new ResponseCaptureDecorator(
                originalResponse,
                idempotencyKey,
                inProgressRecord.getStartedAt(),
                idempotencyService,
                ttlConfig.getCompleted()
        );
        
        ServerWebExchange decoratedExchange = exchange.mutate()
                .response(decoratedResponse)
                .build();
        
        return chain.filter(decoratedExchange)
                .doOnSuccess(v -> log.info("Successfully processed first request for key: {}", idempotencyKey))
                .doOnError(e -> log.error("Error processing first request for key: {}", idempotencyKey, e));
    }
    

    private Mono<Void> handleRetryRequest(ServerWebExchange exchange, String idempotencyKey,
                                           IdempotencyConfig.TtlConfig ttlConfig) {
        return idempotencyService.fetch(idempotencyKey)
                .flatMap(maybeRecord -> {
                    if (maybeRecord.isEmpty()) {
                        // Record disappeared (TTL expired) - allow re-processing
                        log.warn("Idempotency record expired for key: {}, allowing retry", idempotencyKey);
                        // Recursive call will trigger tryAcquire again
                        return filter(exchange, null);
                    }
                    
                    IdempotencyRecord record = maybeRecord.get();
                    
                    if (record.getStatus() == IdempotencyRecord.Status.COMPLETED) {
                        // Return cached response
                        return returnCachedResponse(exchange, record, idempotencyKey);
                    } else {
                        // IN_PROGRESS - check if expired
                        if (idempotencyService.isExpired(record, ttlConfig.getInProgress())) {
                            log.warn("IN_PROGRESS record expired for key: {}, allowing retry", idempotencyKey);
                            // Allow re-processing - downstream must handle duplicate
                            return filter(exchange, null);
                        } else {
                            // Still processing - return 202 Accepted
                            return returnProcessingResponse(exchange, idempotencyKey);
                        }
                    }
                });
    }

    private Mono<Void> returnCachedResponse(ServerWebExchange exchange, 
                                             IdempotencyRecord record, String idempotencyKey) {
        log.info("Returning cached response for key: {} with status: {}", 
                idempotencyKey, record.getHttpStatus());
        
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(record.getHttpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        // Industry-standard headers for cached/replayed responses
        response.getHeaders().add(IDEMPOTENCY_KEY_HEADER, idempotencyKey);  // Echo back the key
        response.getHeaders().add("X-Idempotent-Replay", "true");  // Indicate this IS a replay
        
        DataBuffer buffer = response.bufferFactory()
                .wrap(record.getResponseBody().getBytes(StandardCharsets.UTF_8));
        
        return response.writeWith(Mono.just(buffer));
    }
    

    private Mono<Void> returnProcessingResponse(ServerWebExchange exchange, String idempotencyKey) {
        log.info("Request still in progress for key: {}, returning 202", idempotencyKey);
        
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.ACCEPTED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        
        // Industry-standard headers for in-progress responses
        response.getHeaders().add(IDEMPOTENCY_KEY_HEADER, idempotencyKey);  // Echo back the key
        response.getHeaders().add("Retry-After", "5");  // Suggest retry after 5 seconds
        
        String body = String.format(
                "{\"message\":\"Request is being processed. Please retry after 5 seconds.\",\"idempotencyKey\":\"%s\"}", 
                idempotencyKey
        );
        
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
    
    private boolean requiresIdempotency(ServerHttpRequest request) {
        return IDEMPOTENT_METHODS.contains(request.getMethod());
    }
    
    private String extractIdempotencyKey(ServerHttpRequest request) {
        List<String> headers = request.getHeaders().get(IDEMPOTENCY_KEY_HEADER);
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        return headers.get(0);
    }
    

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
    

    private static class ResponseCaptureDecorator extends ServerHttpResponseDecorator {
        
        private final String idempotencyKey;
        private final java.time.Instant startedAt;
        private final IdempotencyService idempotencyService;
        private final java.time.Duration completedTtl;
        private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
        
        public ResponseCaptureDecorator(ServerHttpResponse delegate,
                                         String idempotencyKey,
                                         java.time.Instant startedAt,
                                         IdempotencyService idempotencyService,
                                         java.time.Duration completedTtl) {
            super(delegate);
            this.idempotencyKey = idempotencyKey;
            this.startedAt = startedAt;
            this.idempotencyService = idempotencyService;
            this.completedTtl = completedTtl;
        }
        
        @Override
        public Mono<Void> writeWith(org.reactivestreams.Publisher<? extends DataBuffer> body) {
            
            // Buffer the entire response body
            Flux<DataBuffer> flux = Flux.from(body);
            
            return DataBufferUtils.join(flux)
                    .flatMap(dataBuffer -> {
                        // Extract response body as string
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        String responseBody = new String(bytes, StandardCharsets.UTF_8);
                        
                        // Store in Redis asynchronously (fire-and-forget)
                        Integer httpStatus = getStatusCode() != null ? getStatusCode().value() : 200;
                        idempotencyService.complete(idempotencyKey, httpStatus, responseBody, startedAt, completedTtl)
                                .subscribe(
                                        v -> {}, // Success - no action needed
                                        e -> LoggerFactory.getLogger(ResponseCaptureDecorator.class)
                                                .error("Failed to store idempotency record for key: {}", idempotencyKey, e)
                                );
                        
                        // Send response to client
                        DataBuffer buffer = bufferFactory.wrap(bytes);
                        return getDelegate().writeWith(Mono.just(buffer));
                    });
        }
    }
}
