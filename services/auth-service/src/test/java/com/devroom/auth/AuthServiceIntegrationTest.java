package com.devroom.auth;

import com.devroom.auth.domain.DevroomUserRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class AuthServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authdb");

    @Autowired
    TestRestTemplate http;

    @Autowired
    DevroomUserRepository userRepo;

    @Autowired
    OutboxRepository outboxRepo;

    @Test
    void jwksEndpointExposesPublicKey() {
        // SAS 7.0.5 exponerar JWKS på /oauth2/jwks (default), inte /.well-known/jwks.json.
        // Resource servers ska upptäcka rätt path via discovery-dokumentets jwks_uri-fält.
        ResponseEntity<Map> resp = http.getForEntity("/oauth2/jwks", Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsKey("keys");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> keys = (List<Map<String, Object>>) resp.getBody().get("keys");
        assertThat(keys).isNotEmpty();
        assertThat(keys.get(0)).containsKeys("kty", "kid", "n", "e");
        assertThat(keys.get(0).get("kty")).isEqualTo("RSA");
    }

    @Test
    void openidConfigurationIsAvailable() {
        ResponseEntity<Map> resp = http.getForEntity("/.well-known/openid-configuration", Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody())
                .containsEntry("issuer", "http://localhost:8081")
                .containsKey("jwks_uri")
                .containsKey("token_endpoint")
                .containsKey("authorization_endpoint");
    }

    @Test
    void clientCredentialsGrantReturnsAccessToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("bot-service", "dev-bot-secret-change-me");
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("scope", "bot:write");

        ResponseEntity<Map> resp = http.postForEntity("/oauth2/token", new HttpEntity<>(body, headers), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody())
                .containsKey("access_token")
                .containsEntry("token_type", "Bearer")
                .containsEntry("scope", "bot:write");
    }

    @Test
    void signupCreatesUserAndOutboxEvent() {
        long outboxBefore = outboxRepo.count();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email": "annika@example.com", "password": "password123"}
                """;

        ResponseEntity<Map> resp = http.postForEntity("/signup", new HttpEntity<>(body, headers), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        assertThat(resp.getBody()).containsKey("userId");

        assertThat(userRepo.findByUsername("annika@example.com")).isPresent();

        List<OutboxEvent> events = outboxRepo.findAll();
        assertThat(events).hasSizeGreaterThan((int) outboxBefore);
        assertThat(events)
                .anyMatch(e -> "user.registered".equals(e.getEventType())
                        && e.getPayload().contains("annika@example.com"));
    }

    @Test
    void duplicateEmailReturns409() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email": "dup@example.com", "password": "password123"}
                """;

        // Första signup → 201
        ResponseEntity<Map> first = http.postForEntity("/signup", new HttpEntity<>(body, headers), Map.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);

        // Andra med samma email → 409
        ResponseEntity<Map> second = http.postForEntity("/signup", new HttpEntity<>(body, headers), Map.class);
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody()).containsEntry("error", "email_already_exists");
    }
}
