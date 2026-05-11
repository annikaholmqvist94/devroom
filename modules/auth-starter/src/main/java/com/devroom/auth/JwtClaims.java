package com.devroom.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record JwtClaims(
        String subject,
        String issuer,
        Optional<String> teamId,
        List<String> roles,
        Instant issuedAt,
        Instant expiresAt
) {
    public static JwtClaims forUser(String userId, String teamId, Instant issuedAt, Instant expiresAt) {
        return new JwtClaims(
                userId,
                "auth-service",
                Optional.of(teamId),
                List.of(),
                issuedAt,
                expiresAt
        );
    }

    public static JwtClaims forService(String serviceName, List<String> roles, Instant issuedAt, Instant expiresAt) {
        return new JwtClaims(
                serviceName,
                "auth-service",
                Optional.empty(),
                List.copyOf(roles),
                issuedAt,
                expiresAt
        );
    }

    public boolean isService() {
        return roles.contains("system");
    }
}
