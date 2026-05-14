package com.devroom.auth.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Hämtar de äldsta unprocessed events. Publishern (Plan 04: via RabbitMQ) iterar dessa,
     * skickar dem och markerar processed_at via markProcessed() på entiteten.
     */
    List<OutboxEvent> findByProcessedAtIsNullOrderByCreatedAtAsc(Limit limit);
}
