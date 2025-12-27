package com.SwitchBoard.Gateway.Config;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class JwtAuthenticationToken extends AbstractAuthenticationToken {

    private final String email;
    private final String userId;
    private final String role;

    public JwtAuthenticationToken(String email, String userId, String role, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.email = email;
        this.userId = userId;
        this.role = role;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null; // No credentials needed for JWT
    }

    @Override
    public Object getPrincipal() {
        return email;
    }

    public String getEmail() {
        return email;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}