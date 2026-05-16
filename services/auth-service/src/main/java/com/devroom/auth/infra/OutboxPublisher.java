package com.devroom.auth.infra;

import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.devroom.auth.config.RabbitTopologyConfig.EVENTS_EXCHANGE;

/**
 * Pollar outbox_events och publicerar unprocessed rader till RabbitMQ.
 *
 * Persistent delivery + durable exchange/queue ger end-to-end-garanti att eventet överlever
 * en broker-omstart. Misslyckas sändningen lämnas processed_at=null så att eventet retryas
 * nästa cykel (at-least-once); consumer-sidan är idempotent via existsByUserId-koll.
 *
 * Single-instance design — en publisher kör i taget. För multi-instance (k8s replicas) behövs
 * SELECT FOR UPDATE SKIP LOCKED eller en distribuerad lock.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository repo;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(OutboxRepository repo, RabbitTemplate rabbit) {
        this.repo = repo;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = repo.findByProcessedAtIsNullOrderByCreatedAtAsc(Limit.of(BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            try {
                Message msg = MessageBuilder
                        .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .build();
                rabbit.send(EVENTS_EXCHANGE, event.getEventType(), msg);
                event.markProcessed();
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}, will retry", event.getId(), e);
                // lämna processed_at = null → retry nästa cykel
            }
        }
        repo.saveAll(batch);
    }
}
