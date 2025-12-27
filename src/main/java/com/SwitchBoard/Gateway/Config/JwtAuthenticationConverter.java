package com.SwitchBoard.Gateway.Config;

import com.SwitchBoard.Gateway.Util.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;

@Component
@Slf4j
public class JwtAuthenticationConverter implements ServerAuthenticationConverter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationConverter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

        @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return Mono.fromCallable(() -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.debug("No Bearer token found in Authorization header");
                return null;
            }

            String token = authHeader.substring(7);
            log.debug("Found Bearer token, attempting to validate");

            try {
                Claims claims = jwtUtil.parseClaims(token);
                
                // Use 'sub' for the subject (email)
                String email = claims.getSubject();
                
                // Extract userId from JWT
                String userId = claims.get("userId", String.class);
                
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

                log.debug("JWT validation successful for user: {}, userId: {}, role: {}", email, userId, role);

                // Create custom authentication token that includes userId for header forwarding
                Authentication auth = new JwtAuthenticationToken(
                        email,
                        userId,
                        role,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role))
                );

                return auth;

            } catch (Exception e) {
                log.error("JWT validation failed: {}", e.getMessage());
                return null;
            }
        })
        .onErrorResume(throwable -> {
            log.error("Error in JWT conversion: {}", throwable.getMessage());
            return Mono.empty();
        });
    }
}