package com.devroom.message.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "channels")
public class Channel {

    @Id
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Channel() {}

    public Channel(UUID id, UUID teamId, String name) {
        this.id = id;
        this.teamId = teamId;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTeamId() { return teamId; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
}
