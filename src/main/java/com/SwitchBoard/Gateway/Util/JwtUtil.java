package com.SwitchBoard.Gateway.Util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;
import com.SwitchBoard.Gateway.Service.JwksService;

import java.security.interfaces.RSAPublicKey;

@Component
public class JwtUtil {

    private final JwksService jwksService;

    public JwtUtil(JwksService jwksService) {
        this.jwksService = jwksService;
    }

    public Claims parseClaims(String token) {
        // Extract "kid" from token header without signature verification
        String kid = null;
        try {
            // Parse header only (without signature verification)
            String[] chunks = token.split("\\.");
            String headerB64 = chunks[0];
            String header = new String(java.util.Base64.getUrlDecoder().decode(headerB64));
            
            // Extract kid from header JSON if present
            if (header.contains("\"kid\"")) {
                String[] parts = header.split("\"kid\":");
                if (parts.length > 1) {
                    String kidPart = parts[1].trim();
                    if (kidPart.startsWith("\"")) {
                        kid = kidPart.substring(1, kidPart.indexOf("\"", 1));
                    }
                }
            }
        } catch (Exception e) {
            // If we can't parse the header, we'll use fallback key
        }

        RSAPublicKey key = jwksService.getKey(kid);

        if (key == null) {
            throw new RuntimeException("Public key not found for kid: " + kid);
        }

        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}
