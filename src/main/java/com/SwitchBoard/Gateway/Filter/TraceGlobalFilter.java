package com.SwitchBoard.Gateway.Filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;
@Component
public class TraceGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String traceId = UUID.randomUUID().toString();

        String correlationId = Optional.ofNullable(
                        exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"))
                .orElse(UUID.randomUUID().toString());

        MDC.put("traceId", traceId);
        MDC.put("correlationId", correlationId);

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .header("X-Correlation-Id", correlationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        // ✅ SAFE: ensure headers are added before response commit
        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders()
                    .set("X-Trace-Id", traceId);
            mutatedExchange.getResponse().getHeaders()
                    .set("X-Correlation-Id", correlationId);
            return Mono.empty();
        });

        return chain.filter(mutatedExchange)
                .doFinally(signalType -> MDC.clear());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
