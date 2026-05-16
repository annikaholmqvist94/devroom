package com.devroom.user;

import com.devroom.user.application.UserRegisteredHandler;
import com.devroom.user.domain.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifierar att UserRegisteredHandler är idempotent: två anrop med samma userId ska resultera i
 * en (1) rad i users-tabellen. Detta speglar at-least-once-leverans från RabbitMQ där samma event
 * kan komma flera gånger (t.ex. om consumern ack:ar för sent och broker re-levererar).
 *
 * Skyddet i koden är existsByUserId-checken; backstop är den unika constraint på user_id i
 * Flyway-migrationen V1.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UserRegisteredHandlerIdempotencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("userdb")
            .withUsername("dbuser")
            .withPassword("dbpass");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserRegisteredHandler handler;
    @Autowired UserRepository repo;

    @Test
    void duplicateHandlerCallDoesNotCreateDuplicateRow() {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String email = "idempotency@test.com";

        handler.handle(userId, email, teamId);
        handler.handle(userId, email, teamId);

        long count = repo.findAll().stream()
                .filter(u -> u.getUserId().equals(userId))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
