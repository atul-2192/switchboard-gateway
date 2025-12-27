package com.SwitchBoard.Gateway.Service;



import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwksService {

    private static final Logger log = LoggerFactory.getLogger(JwksService.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String jwksUrl;

    // kid -> RSAPublicKey
    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();

    public JwksService(@Value("${auth.jwks-url}") String jwksUrl,
                       WebClient.Builder webClientBuilder,
                       ObjectMapper objectMapper) {
        this.jwksUrl = jwksUrl;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        fetchAndCacheKeys();
    }

    /**
     * Periodically refresh JWKS (default interval controlled by property).
     * This runs in Spring's scheduler.
     */
    @Scheduled(fixedDelayString = "${auth.jwks-refresh-ms:60000}")
    public void scheduledRefresh() {
        fetchAndCacheKeys();
    }

    /**
     * Public accessor for the cached public key. If kid is null or not found, returns first available key (fallback).
     */
    public RSAPublicKey getKey(String kid) {
        if (kid != null) {
            RSAPublicKey k = keyCache.get(kid);
            if (k != null) return k;
        }
        // fallback: first key
        return keyCache.values().stream().findFirst().orElse(null);
    }

    /**
     * Fetches JWKS JSON from auth service and updates cache atomically.
     */
    private void fetchAndCacheKeys() {
        try {
            log.debug("Fetching JWKS from {}", jwksUrl);
            String body = webClient.get()
                    .uri(jwksUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(); // scheduled background task - blocking is acceptable here

            if (body == null) {
                log.warn("JWKS fetch returned empty body");
                return;
            }

            JsonNode root = objectMapper.readTree(body);
            JsonNode keysNode = root.get("keys");
            if (keysNode == null || !keysNode.isArray()) {
                log.warn("JWKS response missing keys array");
                return;
            }

            Map<String, RSAPublicKey> tmp = new ConcurrentHashMap<>();
            for (JsonNode keyNode : keysNode) {
                JsonNode kidNode = keyNode.get("kid");
                JsonNode nNode = keyNode.get("n");
                JsonNode eNode = keyNode.get("e");
                if (kidNode == null || nNode == null || eNode == null) {
                    continue;
                }
                String kid = kidNode.asText();
                String n = nNode.asText();
                String e = eNode.asText();

                RSAPublicKey publicKey = buildRsaPublicKey(n, e);
                tmp.put(kid, publicKey);
            }

            if (!tmp.isEmpty()) {
                keyCache.clear();
                keyCache.putAll(tmp);
                log.info("JWKS cache updated ({} keys)", keyCache.size());
            } else {
                log.warn("No RSA keys found in JWKS response");
            }

        } catch (Exception ex) {
            log.error("Failed to fetch or parse JWKS from {}: {}", jwksUrl, ex.toString());
            // keep old cache on failure
        }
    }

    private RSAPublicKey buildRsaPublicKey(String nBase64Url, String eBase64Url) throws Exception {
        byte[] modulusBytes = base64UrlDecode(nBase64Url);
        byte[] exponentBytes = base64UrlDecode(eBase64Url);

        BigInteger modulus = new BigInteger(1, modulusBytes);
        BigInteger exponent = new BigInteger(1, exponentBytes);

        RSAPublicKeySpec spec = new RSAPublicKeySpec(modulus, exponent);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(spec);
    }

    private byte[] base64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}

