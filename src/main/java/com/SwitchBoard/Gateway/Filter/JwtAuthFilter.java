package com.SwitchBoard.Gateway.Filter;

import com.SwitchBoard.Gateway.Service.JwksService;
import com.SwitchBoard.Gateway.Util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
//import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import reactor.core.publisher.Mono;

import java.util.Collections;

// Disabled this filter - now using SecurityWebFilterChain approach
//@Component
@Slf4j
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS
                || path.startsWith("/api/v1/auth/")
                || path.equals("/.well-known/jwks.json")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        String token = authHeader.substring(7);

        try {
            Claims claims = jwtUtil.parseClaims(token);

            // Use 'sub' instead of 'email' for the subject
            String email = claims.getSubject(); // 'sub' field
            
            // Handle 'role' as array and extract first role
            Object rolesObj = claims.get("role");
            String role = "USER"; // default role
            
            if (rolesObj instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<String> roles = (java.util.List<String>) rolesObj;
                if (!roles.isEmpty()) {
                    role = roles.get(0);
                }
            } else if (rolesObj instanceof String) {
                role = (String) rolesObj;
            }

            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    email,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
            );

            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Email", email)
                    .header("X-User-Role", role)
                    .build();

            return chain.filter(exchange.mutate().request(mutated).build())
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));

        } catch (Exception e) {
            log.error("JWT validation failed", e);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
    }

    @Override
    public int getOrder() {
        return -100; // ensure runs before main security filter
    }
}
