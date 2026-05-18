package com.devroom.message.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Producer-sidans topology: deklarerar bara exchange:n. Queues och bindings ägs av consumers
 * (bot-service i plan 07). Exchange-deklaration är idempotent — om auth-service redan deklarerat
 * 'devroom.events' med samma egenskaper är detta en no-op på broker.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String MESSAGE_PUBLISHED_ROUTING_KEY = "message.published";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
