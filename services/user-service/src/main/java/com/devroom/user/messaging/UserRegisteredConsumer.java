package com.devroom.user.messaging;

import com.devroom.user.application.UserRegisteredHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("rabbit")
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);

    private final UserRegisteredHandler handler;
    private final ObjectMapper mapper;

    public UserRegisteredConsumer(UserRegisteredHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "user-service.user-registered")
    public void onMessage(String json) throws Exception {
        log.info("Received user.registered: {}", json);
        JsonNode node = mapper.readTree(json);
        UUID userId = UUID.fromString(node.get("user_id").asText());
        String email = node.get("email").asText();
        UUID teamId = UUID.fromString(node.get("team_id").asText());
        handler.handle(userId, email, teamId);
    }
}
