package com.SwitchBoard.Gateway.Config;

import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationManager implements ReactiveAuthenticationManager {

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        // If the authentication object is null, return empty
        if (authentication == null) {
            return Mono.empty();
        }
        
        // If the authentication object is already populated (from our converter), just return it
        if (authentication.isAuthenticated()) {
            return Mono.just(authentication);
        }
        
        // Mark it as authenticated since our converter already validated the JWT
        authentication.setAuthenticated(true);
        return Mono.just(authentication);
    }
}