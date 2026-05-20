package com.devroom.tests;

import com.devroom.auth.application.SignupService;
import com.devroom.auth.domain.DevroomUserRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import com.devroom.user.application.UserRegisteredHandler;
import com.devroom.user.messaging.UserRegisteredConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifierar wire-kontraktet på user.registered-eventet mellan Auth Service (producent)
 * och User Service (konsument). Service-lokala tester verifierar varje sidas eget
 * beteende; den här testen binder ihop dem och fångar schema-drift som annars går
 * först-i-prod.
 */
class UserRegisteredEventContractTest {

    private static final String DEMO_TEAM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String EMAIL = "contract@test.com";

    @Test
    void authPayloadParsesIntoUserConsumerArgs() {
        JsonMapper mapper = JsonMapper.builder().build();
        DevroomUserRepository userRepo = mock(DevroomUserRepository.class);
        OutboxRepository outboxRepo = mock(OutboxRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("encoded");

        SignupService auth = new SignupService(userRepo, outboxRepo, passwordEncoder, mapper, DEMO_TEAM_ID);
        SignupService.Result result = auth.signup(EMAIL, "password123");

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(eventCaptor.capture());
        OutboxEvent published = eventCaptor.getValue();

        assertThat(published.getEventType()).isEqualTo("user.registered");

        UserRegisteredHandler handler = mock(UserRegisteredHandler.class);
        UserRegisteredConsumer consumer = new UserRegisteredConsumer(handler, mapper);
        consumer.onMessage(published.getPayload());

        verify(handler).handle(
                eq(result.userId()),
                eq(EMAIL),
                eq(UUID.fromString(DEMO_TEAM_ID))
        );
    }
}
