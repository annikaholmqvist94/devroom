package com.devroom.user.messaging;

import com.devroom.user.application.UserRegisteredHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Component
@Profile("rabbit")
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);

    private final UserRegisteredHandler handler;
    private final JsonMapper mapper;

    public UserRegisteredConsumer(UserRegisteredHandler handler, JsonMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "user-service.user-registered")
    public void onMessage(String json) {
        log.info("Received user.registered: {}", json);
        JsonNode node = mapper.readTree(json);
        UUID userId = UUID.fromString(node.get("user_id").asString());
        String email = node.get("email").asString();
        UUID teamId = UUID.fromString(node.get("team_id").asString());
        handler.handle(userId, email, teamId);
    }
}
