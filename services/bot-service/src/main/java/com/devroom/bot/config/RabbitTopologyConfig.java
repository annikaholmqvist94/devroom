package com.devroom.bot.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumer-sidans topology: queue + DLQ + bindings för message.published-eventet.
 *
 * Vid 3 misslyckade leveranser (retry-policy i application.yml) dead-letteras meddelandet till
 * devroom.events.dlx → bot-service.message-published.dlq, där det kan inspekteras manuellt utan
 * att huvudkön blockeras av poison messages.
 *
 * Exchange:n devroom.events och DLX:n devroom.events.dlx deklareras idempotent — RabbitAdmin
 * matchar mot befintliga deklarationer från auth-service/user-service och tar ingen action
 * om argumenten är identiska.
 */
@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String DLX_EXCHANGE = "devroom.events.dlx";
    public static final String MESSAGE_PUBLISHED_QUEUE = "bot-service.message-published";
    public static final String MESSAGE_PUBLISHED_DLQ = "bot-service.message-published.dlq";
    public static final String MESSAGE_PUBLISHED_ROUTING_KEY = "message.published";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue messagePublishedQueue() {
        return QueueBuilder.durable(MESSAGE_PUBLISHED_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MESSAGE_PUBLISHED_DLQ)
                .build();
    }

    @Bean
    public Queue messagePublishedDlq() {
        return QueueBuilder.durable(MESSAGE_PUBLISHED_DLQ).build();
    }

    @Bean
    public Binding messagePublishedBinding() {
        return BindingBuilder.bind(messagePublishedQueue())
                .to(eventsExchange())
                .with(MESSAGE_PUBLISHED_ROUTING_KEY);
    }

    @Bean
    public Binding messagePublishedDlqBinding() {
        return BindingBuilder.bind(messagePublishedDlq())
                .to(dlxExchange())
                .with(MESSAGE_PUBLISHED_DLQ);
    }
}
