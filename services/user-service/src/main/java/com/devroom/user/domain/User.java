package com.devroom.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "is_system", nullable = false)
    private boolean isSystem;

    @Column(name = "mentor_personality")
    private String mentorPersonality;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
    }

    public User(UUID userId, String displayName, UUID teamId, boolean isSystem, String mentorPersonality) {
        this.userId = userId;
        this.displayName = displayName;
        this.teamId = teamId;
        this.isSystem = isSystem;
        this.mentorPersonality = mentorPersonality;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public UUID getTeamId() {
        return teamId;
    }

    public boolean isSystem() {
        return isSystem;
    }

    public String getMentorPersonality() {
        return mentorPersonality;
    }
}
