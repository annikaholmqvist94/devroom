package com.devroom.bot.messaging;

import com.devroom.bot.application.BotReplyOrchestrator;
import com.devroom.bot.config.RabbitTopologyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lyssnar på bot-service.message-published — parsar JSON och delegerar till orchestrator.
 *
 * Vid exception bubblar felet upp så Spring AMQP-retry-policy:n (3 försök, exponentiell
 * backoff från application.yml) får göra sitt. Vid sista misslyckandet dead-letterar
 * Spring meddelandet till devroom.events.dlx → bot-service.message-published.dlq.
 */
@Component
public class MessagePublishedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessagePublishedConsumer.class);

    private final BotReplyOrchestrator orchestrator;
    private final JsonMapper mapper;

    public MessagePublishedConsumer(BotReplyOrchestrator orchestrator, JsonMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @RabbitListener(queues = RabbitTopologyConfig.MESSAGE_PUBLISHED_QUEUE)
    public void onMessage(String json) {
        log.debug("Received message.published: {}", json);
        JsonNode event = mapper.readTree(json);
        orchestrator.handle(event);
    }
}
