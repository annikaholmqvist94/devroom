package com.devroom.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA-mappning av users-tabellen (V1__create_users_and_authorities.sql).
 * Innehåller Devroom-specifika kolumner (user_id, team_id) utöver Spring Security-standardfälten.
 */
@Entity
@Table(name = "users")
public class DevroomUser {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;     // = email

    @Column(nullable = false)
    private String password;     // BCrypt-prefixed hash, t.ex. "{bcrypt}$2a$10$..."

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DevroomUser() {
        // JPA kräver en no-arg constructor
    }

    public DevroomUser(UUID userId, String username, String password, UUID teamId) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.enabled = true;
        this.teamId = teamId;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
