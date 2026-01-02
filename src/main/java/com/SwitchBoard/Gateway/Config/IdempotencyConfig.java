package com.SwitchBoard.Gateway.Config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyConfig {
    

    private TtlConfig defaults = new TtlConfig(
            Duration.ofSeconds(30),    // Conservative default for IN_PROGRESS
            Duration.ofHours(24)       // Standard retry window for COMPLETED
    );
    

    private Map<String, TtlConfig> endpoints = new HashMap<>();
    

    public TtlConfig resolve(String method, String path) {
        String key = method + ":" + path;
        
        // 1. Exact match
        if (endpoints.containsKey(key)) {
            return endpoints.get(key);
        }
        
        // 2. Wildcard matching (simple implementation)
        for (Map.Entry<String, TtlConfig> entry : endpoints.entrySet()) {
            String pattern = entry.getKey();
            if (matches(pattern, key)) {
                return entry.getValue();
            }
        }
        return defaults;
    }
    

    private boolean matches(String pattern, String key) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return key.startsWith(prefix);
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return key.startsWith(prefix) && !key.substring(prefix.length()).contains("/");
        }
        return pattern.equals(key);
    }

    @Setter
    @Getter
    public static class TtlConfig {

        private Duration inProgress;

        private Duration completed;
        
        public TtlConfig() {
        }
        
        public TtlConfig(Duration inProgress, Duration completed) {
            this.inProgress = inProgress;
            this.completed = completed;
        }

    }
}
