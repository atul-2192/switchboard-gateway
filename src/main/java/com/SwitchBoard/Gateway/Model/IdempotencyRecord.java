package com.SwitchBoard.Gateway.Model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;

/**
 * Redis storage model for idempotency tracking.
 * 
 * Design rationale:
 * - Immutable to prevent race conditions in concurrent scenarios
 * - Status enum prevents invalid state transitions
 * - startedAt enables TTL expiry detection and debugging
 * - Stores complete HTTP response for exact replay
 */
@Getter
public class IdempotencyRecord {
    
    public enum Status {
        IN_PROGRESS,
        COMPLETED
    }
    
    private final Status status;
    private final Integer httpStatus;
    private final String responseBody;
    private final Instant startedAt;

    public static IdempotencyRecord inProgress() {
        return new IdempotencyRecord(Status.IN_PROGRESS, null, null, Instant.now());
    }
    

    public static IdempotencyRecord completed(Integer httpStatus, String responseBody, Instant startedAt) {
        return new IdempotencyRecord(Status.COMPLETED, httpStatus, responseBody, startedAt);
    }
    
    @JsonCreator
    public IdempotencyRecord(
            @JsonProperty("status") Status status,
            @JsonProperty("httpStatus") Integer httpStatus,
            @JsonProperty("responseBody") String responseBody,
            @JsonProperty("startedAt") Instant startedAt) {
        this.status = status;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.startedAt = startedAt;
    }


    public boolean isExpired(long inProgressTtlSeconds) {
        if (status != Status.IN_PROGRESS) {
            return false;
        }
        return startedAt.plusSeconds(inProgressTtlSeconds).isBefore(Instant.now());
    }
}
