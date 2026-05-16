package com.devroom.auth;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end-verifiering av signup → outbox → RabbitMQ-flödet.
 *
 * Två containers (Postgres + RabbitMQ) körs samtidigt. Testet POST:ar mot /signup, väntar på att
 * OutboxPublisher pollar och skickar, och plockar sedan meddelandet från en test-deklarerad
 * observer-queue bunden till samma exchange och routing key som user-service:s queue.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class OutboxToRabbitIntegrationTest {

    private static final String OBSERVER_QUEUE = "test.user-registered.observer";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authdb");

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
    }

    @Autowired TestRestTemplate http;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired AmqpAdmin admin;

    @Test
    void signupResultsInUserRegisteredMessageOnRabbit() {
        // RabbitMQ 4 disablar transient non-exclusive queues by default — använd durable här,
        // queue:n raderas naturligt när containern dör vid test-slut.
        Queue observer = QueueBuilder.durable(OBSERVER_QUEUE).build();
        TopicExchange exchange = new TopicExchange("devroom.events", true, false);
        Binding binding = BindingBuilder.bind(observer).to(exchange).with("user.registered");
        admin.declareQueue(observer);
        admin.declareExchange(exchange);
        admin.declareBinding(binding);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email": "rabbit@test.com", "password": "password123"}
                """;
        ResponseEntity<Map> resp = http.postForEntity("/signup", new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            Message msg = rabbitTemplate.receive(OBSERVER_QUEUE, 200);
            assertThat(msg).isNotNull();
            String json = new String(msg.getBody());
            assertThat(json).contains("rabbit@test.com");
            assertThat(json).contains("user.registered");
            assertThat(msg.getMessageProperties().getContentType()).isEqualTo("application/json");
        });
    }
}
