package com.devroom.bot.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagePosterTest {

    @Test
    void increments_bot_replies_counter_on_post() {
        // RestClient's fluent API uses F-bounded generics (self-returning S),
        // which RETURNS_DEEP_STUBS cannot resolve — wire the chain explicitly.
        RestClient client = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(client.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/messages")).thenReturn(bodySpec);
        when(bodySpec.attributes(any())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagePoster poster = new MessagePoster(client, registry);

        poster.post("channel-1", "user-1", "hi there", null, "msg-1");

        assertThat(registry.counter("bot.replies").count()).isEqualTo(1.0);
    }
}
