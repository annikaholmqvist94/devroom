package com.devroom.message.application;

import com.devroom.message.domain.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageEventPublisherTest {

    @Test
    void increments_messages_published_counter_on_publish() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageEventPublisher publisher = new MessageEventPublisher(
                rabbit, JsonMapper.builder().build(),
                "11111111-1111-1111-1111-111111111111", registry);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(UUID.randomUUID());
        when(message.getChannelId()).thenReturn(UUID.randomUUID());
        when(message.getSenderId()).thenReturn(UUID.randomUUID());
        when(message.getBody()).thenReturn("hello");
        when(message.getParentMessageId()).thenReturn(null);
        when(message.getMentions()).thenReturn(List.of());

        publisher.publish(message);

        assertThat(registry.counter("messages.published").count()).isEqualTo(1.0);
    }
}
