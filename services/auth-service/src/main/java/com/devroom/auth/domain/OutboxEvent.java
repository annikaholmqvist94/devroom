package com.devroom.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA-mappning av outbox_events-tabellen (V4__create_outbox_events.sql).
 *
 * payload lagras som Postgres JSONB; vi håller den som JSON-sträng på Java-sidan eftersom
 * SignupService redan serialiserar via Jackson. Hibernate hanterar konverteringen via
 * @JdbcTypeCode(SqlTypes.JSON).
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected OutboxEvent() {
        // JPA kräver en no-arg constructor
    }

    public OutboxEvent(String eventType, String payload) {
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markProcessed() {
        this.processedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
