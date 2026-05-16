package com.devroom.auth.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Producer-sidans topology: bara exchange:n. Queues + bindings ägs av respektive consumer-service.
 *
 * Topic-exchange valt för att framtida services kan binda på wildcard-patterns (user.*, message.#)
 * utan att producer behöver känna till dem.
 *
 * RabbitAdmin (auto-konfigureras av spring-boot-starter-amqp) plockar denna bean vid uppstart
 * och deklarerar exchange:n i brokern idempotent.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
