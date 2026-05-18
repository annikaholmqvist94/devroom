package com.devroom.message;

import com.devroom.message.domain.ChannelRepository;
import com.devroom.message.domain.MentionInfo;
import com.devroom.message.domain.Message;
import com.devroom.message.domain.MessageRepository;
import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.ResolveMentionsRequest;
import com.devroom.user.grpc.ResolveMentionsResponse;
import com.devroom.user.grpc.User;
import com.devroom.user.grpc.UserGrpcServiceGrpc;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.devroom.message.config.RabbitTopologyConfig.EVENTS_EXCHANGE;
import static com.devroom.message.config.RabbitTopologyConfig.MESSAGE_PUBLISHED_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end-test för Message Service:
 * - Postgres + RabbitMQ via Testcontainers
 * - In-process gRPC-server som mockar User Service (mappad via @Primary stub-bean)
 * - JWT via MockMvc:s jwt()-postprocessor — Resource Server validerar inte mot riktig JWKS-server
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@Import(MessageServiceIntegrationTest.TestGrpcConfig.class)
class MessageServiceIntegrationTest {

    private static final String DEMO_TEAM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String GENERAL_CHANNEL_ID = "33333333-3333-3333-3333-333333333301";
    private static final String CODE_REVIEWER_USER_ID = "22222222-2222-2222-2222-222222222203";
    private static final String SENDER_ID = "44444444-4444-4444-4444-444444444401";

    private static final String TEST_QUEUE = "test.message-published.observer";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("messagedb")
            .withUsername("dbuser")
            .withPassword("dbpass");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        // Resource Server hämtar aldrig JWKS — vi använder MockMvc.jwt()-postprocessor som
        // injicerar Jwt direkt i SecurityContext. URL:erna behöver bara vara giltiga strängar.
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://test-not-used/jwks");
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://test-not-used");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private MessageRepository messageRepo;
    @Autowired private ChannelRepository channelRepo;
    @Autowired private JsonMapper json;

    @BeforeEach
    void setupTestQueue() {
        Queue queue = QueueBuilder.durable(TEST_QUEUE).build();
        rabbitAdmin.declareQueue(queue);
        Binding binding = BindingBuilder.bind(queue)
                .to(new TopicExchange(EVENTS_EXCHANGE, true, false))
                .with(MESSAGE_PUBLISHED_ROUTING_KEY);
        rabbitAdmin.declareBinding(binding);
        rabbitAdmin.purgeQueue(TEST_QUEUE, false);
        messageRepo.deleteAll();
    }

    @Test
    void postMessageResolvesMentionsAndPublishesEvent() throws Exception {
        String body = """
                {
                  "channelId": "%s",
                  "body": "Hello @code-reviewer, please look at this"
                }
                """.formatted(GENERAL_CHANNEL_ID);

        mockMvc.perform(post("/messages")
                        .with(jwt().jwt(j -> j.subject(SENDER_ID).claim("scope", List.of("profile"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mentions[0].userId").value(CODE_REVIEWER_USER_ID))
                .andExpect(jsonPath("$.mentions[0].isSystem").value(true))
                .andExpect(jsonPath("$.mentions[0].personality").value("code-reviewer"));

        org.springframework.amqp.core.Message amqp = rabbitTemplate.receive(TEST_QUEUE, 5_000);
        assertThat(amqp).as("event on devroom.events").isNotNull();
        JsonNode event = json.readTree(amqp.getBody());
        assertThat(event.get("event_type").asString()).isEqualTo("message.published");
        assertThat(event.get("team_id").asString()).isEqualTo(DEMO_TEAM_ID);
        assertThat(event.get("mentions").get(0).get("userId").asString()).isEqualTo(CODE_REVIEWER_USER_ID);
    }

    @Test
    void postWithoutJwtIs401() throws Exception {
        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channelId\":\"" + GENERAL_CHANNEL_ID + "\",\"body\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMessagesSinceReturnsOnlyNewer() throws Exception {
        UUID channelId = UUID.fromString(GENERAL_CHANNEL_ID);
        Message older = new Message(UUID.randomUUID(), channelId, UUID.fromString(SENDER_ID),
                "older", null, List.of());
        messageRepo.save(older);
        Thread.sleep(50);
        Instant cutoff = Instant.now();
        Thread.sleep(50);
        Message newer = new Message(UUID.randomUUID(), channelId, UUID.fromString(SENDER_ID),
                "newer", null, List.of(new MentionInfo(CODE_REVIEWER_USER_ID, true, "code-reviewer")));
        messageRepo.save(newer);

        mockMvc.perform(get("/messages")
                        .param("channelId", GENERAL_CHANNEL_ID)
                        .param("since", cutoff.toString())
                        .with(jwt().jwt(j -> j.subject(SENDER_ID).claim("scope", List.of("profile")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].body").value("newer"));
    }

    @AfterAll
    static void shutdownInProcessGrpc() {
        if (TestGrpcConfig.server != null) {
            TestGrpcConfig.server.shutdownNow();
        }
    }

    @TestConfiguration
    static class TestGrpcConfig {

        static Server server;

        @Bean
        @Primary
        UserGrpcServiceBlockingStub testUserServiceStub() throws IOException {
            String name = "user-service-test-" + UUID.randomUUID();
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
        public void resolveMentions(ResolveMentionsRequest req,
                                     StreamObserver<ResolveMentionsResponse> obs) {
            ResolveMentionsResponse.Builder b = ResolveMentionsResponse.newBuilder();
            for (String name : req.getDisplayNamesList()) {
                if ("code-reviewer".equals(name)) {
                    b.addUsers(User.newBuilder()
                            .setUserId(CODE_REVIEWER_USER_ID)
                            .setDisplayName("code-reviewer")
                            .setTeamId(DEMO_TEAM_ID)
                            .setIsSystem(true)
                            .setMentorPersonality("code-reviewer")
                            .build());
                }
            }
            obs.onNext(b.build());
            obs.onCompleted();
        }

        @Override
        public void getUser(GetUserRequest req, StreamObserver<User> obs) {
            obs.onNext(User.newBuilder()
                    .setUserId(req.getUserId())
                    .setDisplayName("code-reviewer")
                    .setTeamId(DEMO_TEAM_ID)
                    .setIsSystem(true)
                    .setMentorPersonality("code-reviewer")
                    .build());
            obs.onCompleted();
        }
    }
}
