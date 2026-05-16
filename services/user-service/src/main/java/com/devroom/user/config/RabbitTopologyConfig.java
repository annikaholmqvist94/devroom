package com.devroom.user.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumer-sidans topology: queue + DLQ + bindings för user.registered-eventet.
 *
 * Vid 3 misslyckade leveranser (retry-policy i application.yml) dead-letteras meddelandet till
 * devroom.events.dlx → user-service.user-registered.dlq, där det kan inspekteras manuellt utan
 * att huvudkön blockeras av poison messages.
 *
 * Exchange:n devroom.events deklareras även här idempotent — RabbitAdmin matchar mot befintlig
 * deklaration från auth-service och tar ingen action om argumenten är identiska.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String DLX_EXCHANGE = "devroom.events.dlx";
    public static final String USER_REGISTERED_QUEUE = "user-service.user-registered";
    public static final String USER_REGISTERED_DLQ = "user-service.user-registered.dlq";
    public static final String USER_REGISTERED_ROUTING_KEY = "user.registered";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return QueueBuilder.durable(USER_REGISTERED_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", USER_REGISTERED_DLQ)
                .build();
    }

    @Bean
    public Queue userRegisteredDlq() {
        return QueueBuilder.durable(USER_REGISTERED_DLQ).build();
    }

    @Bean
    public Binding userRegisteredBinding() {
        return BindingBuilder.bind(userRegisteredQueue())
                .to(eventsExchange())
                .with(USER_REGISTERED_ROUTING_KEY);
    }

    @Bean
    public Binding userRegisteredDlqBinding() {
        return BindingBuilder.bind(userRegisteredDlq())
                .to(dlxExchange())
                .with(USER_REGISTERED_DLQ);
    }
}
