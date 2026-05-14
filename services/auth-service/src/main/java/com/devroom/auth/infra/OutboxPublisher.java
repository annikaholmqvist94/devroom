package com.devroom.auth.infra;

import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pollar outbox_events och "publicerar" unprocessed rader. I Plan 04 ersätts log.info()
 * med rabbitTemplate.convertAndSend(...). Tills dess är detta en stub.
 *
 * Single-instance design — en publisher kör i taget. För multi-instance (k8s replicas)
 * behövs SELECT FOR UPDATE SKIP LOCKED eller en distribuerad lock.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository repo;

    public OutboxPublisher(OutboxRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repo.findByProcessedAtIsNullOrderByCreatedAtAsc(Limit.of(BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            log.info("OUTBOX: type={}, payload={}", event.getEventType(), event.getPayload());
            event.markProcessed();
        }
        repo.saveAll(batch);
    }
}
