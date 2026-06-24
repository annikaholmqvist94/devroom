package com.devroom.message.application;

import com.devroom.message.domain.Message;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.devroom.message.config.RabbitTopologyConfig.EVENTS_EXCHANGE;
import static com.devroom.message.config.RabbitTopologyConfig.MESSAGE_PUBLISHED_ROUTING_KEY;

@Component
public class MessageEventPublisher {

    private final RabbitTemplate rabbit;
    private final JsonMapper mapper;
    private final UUID demoTeamId;
    private final Counter messagesPublished;

    public MessageEventPublisher(RabbitTemplate rabbit,
                                  JsonMapper mapper,
                                  @Value("${devroom.message.demo-team-id}") String demoTeamId,
                                  MeterRegistry meterRegistry) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.demoTeamId = UUID.fromString(demoTeamId);
        this.messagesPublished = Counter.builder("messages.published")
                .description("Total messages published to RabbitMQ")
                .register(meterRegistry);
    }

    public void publish(Message message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "message.published");
        payload.put("occurred_at", Instant.now().toString());
        payload.put("message_id", message.getId().toString());
        payload.put("channel_id", message.getChannelId().toString());
        payload.put("team_id", demoTeamId.toString());
        payload.put("sender_id", message.getSenderId().toString());
        payload.put("body", message.getBody());
        payload.put("parent_message_id",
                message.getParentMessageId() == null ? null : message.getParentMessageId().toString());
        payload.put("mentions", message.getMentions());

        String json = mapper.writeValueAsString(payload);
        org.springframework.amqp.core.Message amqp = MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbit.send(EVENTS_EXCHANGE, MESSAGE_PUBLISHED_ROUTING_KEY, amqp);
        messagesPublished.increment();
    }
}
