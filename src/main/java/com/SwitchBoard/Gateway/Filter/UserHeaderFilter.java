package com.SwitchBoard.Gateway.Filter;

import com.SwitchBoard.Gateway.Config.JwtAuthenticationToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class UserHeaderFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(securityContext -> securityContext.getAuthentication())
                .cast(JwtAuthenticationToken.class)
                .map(jwtAuth -> {
                    // Add user headers to the request
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .header("X-User-Id", jwtAuth.getUserId())
                            .header("X-User-Email", jwtAuth.getEmail())
                            .header("X-User-Role", jwtAuth.getRole())
                            .build();

                    log.debug("Added user headers - X-User-Id: {}, X-User-Email: {}, X-User-Role: {}", 
                            jwtAuth.getUserId(), jwtAuth.getEmail(), jwtAuth.getRole());

                    return exchange.mutate().request(mutated).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        // Run after authentication but before routing
        return -50;
    }
}