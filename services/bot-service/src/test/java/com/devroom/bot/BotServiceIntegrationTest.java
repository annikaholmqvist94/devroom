package com.devroom.bot;

import com.devroom.bot.config.RabbitTopologyConfig;
import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.User;
import com.devroom.user.grpc.UserGrpcServiceGrpc;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end-test för Bot Service utan att kräva externa services uppe.
 *
 * Beroenden mockas så här:
 *   - RabbitMQ: Testcontainers (riktig broker — exchange/queue/routing-semantik kräver det)
 *   - User Service (gRPC): In-process server registrerad via @Primary @Bean
 *   - Auth Service /oauth2/token + OIDC discovery: WireMock
 *   - Nordic Dev Mentor /api/v1/chat: WireMock (samma server)
 *   - Message Service /messages: WireMock (samma server)
 *
 * En enda WireMock-instans agerar alla 3 HTTP-tjänster — paths-isolerade. Spring-config
 * pekar alla tre URL:er på wireMock.baseUrl(). Initieras i static {}-block för att vara
 * uppe INNAN Spring Security gör OIDC discovery vid bean-skapande.
 */
@SpringBootTest
@Testcontainers
@Import(BotServiceIntegrationTest.TestGrpcConfig.class)
class BotServiceIntegrationTest {

    private static final String CODE_REVIEWER_USER_ID = "22222222-2222-2222-2222-222222222203";
    private static final String SENDER_ID = "44444444-4444-4444-4444-444444444401";
    private static final String CHANNEL_ID = "33333333-3333-3333-3333-333333333301";

    private static final WireMockServer wireMock = new WireMockServer(0);

    static {
        wireMock.start();
        String base = wireMock.baseUrl();

        // OIDC discovery — Spring slår mot detta vid bean-skapande av ClientRegistration.
        wireMock.stubFor(get("/.well-known/openid-configuration")
                .willReturn(okJson("""
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/oauth2/authorize",
                          "token_endpoint": "%s/oauth2/token",
                          "jwks_uri": "%s/.well-known/jwks.json",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"],
                          "scopes_supported": ["openid", "profile", "bot:write"],
                          "grant_types_supported": ["client_credentials", "authorization_code"]
                        }
                        """.formatted(base, base, base, base))));

        wireMock.stubFor(get("/.well-known/jwks.json")
                .willReturn(okJson("{\"keys\": []}")));

        // Client Credentials token-anrop från OAuth2AuthorizedClientManager.
        wireMock.stubFor(post("/oauth2/token")
                .willReturn(okJson("""
                        {
                          "access_token": "fake-bot-access-token",
                          "token_type": "Bearer",
                          "expires_in": 3600,
                          "scope": "bot:write"
                        }
                        """)));

        // Nordic Dev Mentor mock-svar.
        wireMock.stubFor(post("/api/v1/chat")
                .willReturn(okJson("""
                        {
                          "sessionId": "test-session",
                          "personality": "code-reviewer",
                          "reply": "Use constructor injection — clearer dependency graph."
                        }
                        """)));

        // Message Service mock-svar. Verifieras via wireMock.verify(...) i testen.
        wireMock.stubFor(post("/messages")
                .willReturn(okJson("""
                        {
                          "id": "55555555-5555-5555-5555-555555555501",
                          "channelId": "%s",
                          "senderId": "%s",
                          "body": "ok",
                          "parentMessageId": null,
                          "mentions": [],
                          "createdAt": "2026-05-20T12:00:00Z"
                        }
                        """.formatted(CHANNEL_ID, CODE_REVIEWER_USER_ID))));
    }

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password", rabbit::getAdminPassword);

        // Alla 3 HTTP-tjänster pekar på samma WireMock.
        r.add("spring.security.oauth2.client.provider.auth-service.issuer-uri",
                wireMock::baseUrl);
        r.add("devroom.bot.nordic-dev-mentor-url", wireMock::baseUrl);
        r.add("devroom.bot.message-service-url", wireMock::baseUrl);

        // Bot-client-secret matchar default i auth-service application.yml.
        r.add("BOT_CLIENT_SECRET", () -> "test-secret");

        // gRPC: byt static://localhost:9082 mot in-process — namnet sätts av TestGrpcConfig.
        // Dummy-värde här; @Primary-stub-bönan i TestGrpcConfig ersätter ändå hela stuben.
        r.add("spring.grpc.client.channels.user-service.address",
                () -> "static://unused-in-test:0");
    }

    @AfterAll
    static void stopMocks() {
        wireMock.stop();
        if (TestGrpcConfig.server != null) {
            TestGrpcConfig.server.shutdownNow();
        }
    }

    @Autowired private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void resetWireMockCounters() {
        wireMock.resetRequests();
    }

    @Test
    void mentionWithIsSystemTrueTriggersBotReply() {
        String event = """
                {
                  "event_id": "%s",
                  "event_type": "message.published",
                  "occurred_at": "2026-05-20T12:00:00Z",
                  "message_id": "%s",
                  "channel_id": "%s",
                  "team_id": "11111111-1111-1111-1111-111111111111",
                  "sender_id": "%s",
                  "body": "Hej @code-reviewer kan du förklara DI?",
                  "parent_message_id": null,
                  "mentions": [
                    {"userId": "%s", "isSystem": true, "personality": "code-reviewer"}
                  ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), CHANNEL_ID,
                              SENDER_ID, CODE_REVIEWER_USER_ID);

        publish(event);

        RequestPatternBuilder messagePost = postRequestedFor(urlEqualTo("/messages"))
                .withHeader("Authorization", equalTo("Bearer fake-bot-access-token"))
                .withHeader("Idempotency-Key", matching("bot-reply-.*"))
                .withRequestBody(matching("(?s).*\"asUserId\"\\s*:\\s*\"" + CODE_REVIEWER_USER_ID + "\".*"));

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> wireMock.verify(messagePost));

        // Bonus: mentor ska ha anropats med rätt personality.
        wireMock.verify(postRequestedFor(urlEqualTo("/api/v1/chat"))
                .withRequestBody(matching("(?s).*\"personality\"\\s*:\\s*\"code-reviewer\".*")));
    }

    @Test
    void mentionWithIsSystemFalseIsIgnored() throws InterruptedException {
        String event = """
                {
                  "event_id": "%s",
                  "event_type": "message.published",
                  "occurred_at": "2026-05-20T12:00:00Z",
                  "message_id": "%s",
                  "channel_id": "%s",
                  "team_id": "11111111-1111-1111-1111-111111111111",
                  "sender_id": "%s",
                  "body": "Hej @annika kan du titta?",
                  "parent_message_id": null,
                  "mentions": [
                    {"userId": "%s", "isSystem": false, "personality": null}
                  ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), CHANNEL_ID,
                              SENDER_ID, SENDER_ID);

        publish(event);

        // Vi har inget event att triggas av — vänta lite och verifiera no-op.
        Thread.sleep(1500);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/messages")))).isEmpty();
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/api/v1/chat")))).isEmpty();
    }

    @Test
    void noMentionsIsNoOp() throws InterruptedException {
        String event = """
                {
                  "event_id": "%s",
                  "event_type": "message.published",
                  "occurred_at": "2026-05-20T12:00:00Z",
                  "message_id": "%s",
                  "channel_id": "%s",
                  "team_id": "11111111-1111-1111-1111-111111111111",
                  "sender_id": "%s",
                  "body": "Bara ett vanligt meddelande",
                  "parent_message_id": null,
                  "mentions": []
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), CHANNEL_ID, SENDER_ID);

        publish(event);

        Thread.sleep(1500);
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/messages")))).isEmpty();
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/api/v1/chat")))).isEmpty();
    }

    private void publish(String json) {
        org.springframework.amqp.core.Message amqp = MessageBuilder
                .withBody(json.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbitTemplate.send(RabbitTopologyConfig.EVENTS_EXCHANGE,
                RabbitTopologyConfig.MESSAGE_PUBLISHED_ROUTING_KEY, amqp);
    }

    @TestConfiguration
    static class TestGrpcConfig {

        static Server server;

        /**
         * Ersätter UserGrpcServiceBlockingStub-bönan från GrpcClientConfig med en stub
         * som pratar mot en in-process gRPC-server. Inga portar, ingen container.
         */
        @Bean
        @Primary
        UserGrpcServiceBlockingStub testUserServiceStub() throws IOException {
            String name = "user-service-bot-test-" + UUID.randomUUID();
            server = InProcessServerBuilder.forName(name)
                    .addService(new MockUserGrpcService())
                    .directExecutor()
                    .build()
                    .start();
            ManagedChannel channel = InProcessChannelBuilder.forName(name)
                    .usePlaintext()
                    .directExecutor()
                    .build();
            return UserGrpcServiceGrpc.newBlockingStub(channel);
        }
    }

    static class MockUserGrpcService extends UserGrpcServiceGrpc.UserGrpcServiceImplBase {

        @Override
        public void getUser(GetUserRequest req, StreamObserver<User> obs) {
            obs.onNext(User.newBuilder()
                    .setUserId(req.getUserId())
                    .setDisplayName("Annika")
                    .setTeamId("11111111-1111-1111-1111-111111111111")
                    .setIsSystem(false)
                    .build());
            obs.onCompleted();
        }
    }
}
