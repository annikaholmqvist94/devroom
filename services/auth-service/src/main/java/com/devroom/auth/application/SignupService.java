package com.devroom.auth.application;

import com.devroom.auth.domain.DevroomUser;
import com.devroom.auth.domain.DevroomUserRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Skapar user + user.registered-outbox-event atomärt.
 *
 * Transactional outbox-pattern: båda INSERT:s sker i samma DB-transaktion. Om något fails
 * rullas allt tillbaka. OutboxPublisher (separat tråd) plockar upp eventet och publicerar
 * till RabbitMQ asynkront (Plan 04).
 */
@Service
public class SignupService {

    private final DevroomUserRepository userRepo;
    private final OutboxRepository outboxRepo;
    private final PasswordEncoder passwordEncoder;
    private final JsonMapper mapper;
    private final UUID demoTeamId;

    public SignupService(
            DevroomUserRepository userRepo,
            OutboxRepository outboxRepo,
            PasswordEncoder passwordEncoder,
            JsonMapper mapper,
            @Value("${devroom.auth.demo-team-id}") String demoTeamId) {
        this.userRepo = userRepo;
        this.outboxRepo = outboxRepo;
        this.passwordEncoder = passwordEncoder;
        this.mapper = mapper;
        this.demoTeamId = UUID.fromString(demoTeamId);
    }

    @Transactional
    public Result signup(String email, String password) {
        if (userRepo.existsByUsername(email)) {
            throw new DuplicateEmailException(email);
        }

        UUID userId = UUID.randomUUID();
        DevroomUser user = new DevroomUser(userId, email, passwordEncoder.encode(password), demoTeamId);
        userRepo.save(user);

        outboxRepo.save(new OutboxEvent("user.registered", buildPayload(userId, email)));

        return new Result(userId);
    }

    private String buildPayload(UUID userId, String email) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "user.registered");
        payload.put("occurred_at", Instant.now().toString());
        payload.put("user_id", userId.toString());
        payload.put("email", email);
        payload.put("team_id", demoTeamId.toString());
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            // För en Map<String,String> händer detta inte i praktiken.
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    public record Result(UUID userId) {}
}
