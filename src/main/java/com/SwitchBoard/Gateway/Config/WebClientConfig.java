package com.SwitchBoard.Gateway.Config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient configuration for load-balanced service-to-service communication.
 * Enables Eureka service discovery for URLs like http://AUTHSERVICE/...
 */
@Configuration
public class WebClientConfig {

    /**
     * Load-balanced WebClient.Builder bean.
     * This allows JwksService to call http://AUTHSERVICE/.well-known/jwks.json
     * and resolve it via Eureka service discovery instead of hardcoded localhost:port.
     */
    @Bean
    @LoadBalanced
    public WebClient.Builder loadBalancedWebClientBuilder() {
        return WebClient.builder();
    }
}
