# Plan 04: RabbitMQ End-to-End Signup-flöde

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Koppla ihop Auth Services outbox-publisher med RabbitMQ, aktivera User Services `user.registered`-consumer, deklarera exchange/queues/DLQs, och verifiera att signup → outbox → RabbitMQ → User Service-profil fungerar end-to-end.

**Architecture:** En topic-exchange `devroom.events` med routing keys per event-typ. Durable queues, persistent messages, manual ack med retry → DLQ vid 3 misslyckanden. Topology deklareras i en `RabbitTopologyConfig`-bean i varje service som rör RabbitMQ.

**Tech Stack:** Spring AMQP 3.x (kommer med Spring Boot 4), RabbitMQ 4 (redan i docker-compose från plan 01).

**Refererar spec:** sektion 5.1, 5.3, 5.4.

**Pre-condition:** plan 01-03 klara. Auth Service har outbox-stub, User Service har consumer-stub `@Profile("rabbit")`.

---

## File Structure

```
services/auth-service/src/main/java/com/devroom/auth/
├── config/
│   └── RabbitTopologyConfig.java          # ny — exchange + outbound deklaration
└── infra/
    ├── OutboxPublisher.java               # uppdatera — wire RabbitTemplate
    └── RabbitEventPublisher.java          # ny — tunn wrapper

services/user-service/src/main/java/com/devroom/user/
├── config/
│   └── RabbitTopologyConfig.java          # ny — queue + DLQ + binding
└── messaging/
    └── UserRegisteredConsumer.java        # ta bort @Profile("rabbit") begränsning

services/auth-service/src/test/java/com/devroom/auth/
└── OutboxToRabbitIntegrationTest.java     # ny — Testcontainers + RabbitMQContainer

services/user-service/src/test/java/com/devroom/user/
└── UserRegisteredConsumerTest.java        # ny — verifiera idempotency
```

---

## Task 1: Lägg till AMQP-dependency i auth-service

**Files:**
- Modify: `services/auth-service/pom.xml`

- [ ] **Step 1: Lägg till `spring-boot-starter-amqp`**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>rabbitmq</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verifiera bygge**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Lägg till RabbitMQ-config i `application.yml`**

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: devroom
    password: devroom
```

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/pom.xml services/auth-service/src/main/resources/application.yml
git commit -m "feat(auth-service): add Spring AMQP dependency and config"
```

---

## Task 2: Deklarera RabbitMQ-topology i auth-service

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/config/RabbitTopologyConfig.java`

- [ ] **Step 1: Skapa topology-config**

```java
package com.devroom.auth.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String USER_REGISTERED_ROUTING_KEY = "user.registered";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/config/RabbitTopologyConfig.java
git commit -m "feat(auth-service): declare events exchange and JSON converter"
```

---

## Task 3: Wire OutboxPublisher mot RabbitTemplate

**Files:**
- Modify: `services/auth-service/src/main/java/com/devroom/auth/infra/OutboxPublisher.java`

- [ ] **Step 1: Uppdatera publishern**

```java
package com.devroom.auth.infra;

import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.devroom.auth.config.RabbitTopologyConfig.EVENTS_EXCHANGE;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repo;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(OutboxRepository repo, RabbitTemplate rabbit) {
        this.repo = repo;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "PT0.5S")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> events = repo.findUnprocessed(PageRequest.of(0, BATCH_SIZE));
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                String routingKey = event.getEventType();  // "user.registered"
                rabbit.convertAndSend(EVENTS_EXCHANGE, routingKey, event.getPayload());
                event.markProcessed();
                log.debug("Published outbox event id={} type={}", event.getId(), event.getEventType());
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={}, will retry", event.getId(), e);
                // lämna processed_at = null så den retryas nästa cykel
            }
        }
        repo.saveAll(events);
    }
}
```

OBS: skickar payload som **string** (inte deserialiserat objekt), eftersom outbox-payload är förvalidate JSON. RabbitTemplate's default-converter hanterar bytes/strings.

- [ ] **Step 2: Justera message converter för bytes/string**

Eftersom payloads är JSON-strings, men `Jackson2JsonMessageConverter` förväntar Java-objekt, byt till en converter som passar:

```java
// I RabbitTopologyConfig.java — ersätt messageConverter:
@Bean
public MessageConverter messageConverter() {
    // Vi skickar redan JSON-strings, så vi använder default SimpleMessageConverter
    // (Jackson2JsonMessageConverter är för objekt → JSON)
    return new org.springframework.amqp.support.converter.SimpleMessageConverter();
}
```

Faktum: enklast är att använda `RabbitTemplate.send(...)` med raw-bytes. Simplifiera publishern:

```java
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

// ... i publishPending:
for (OutboxEvent event : events) {
    try {
        org.springframework.amqp.core.Message msg = MessageBuilder
                .withBody(event.getPayload().getBytes())
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        rabbit.send(EVENTS_EXCHANGE, event.getEventType(), msg);
        event.markProcessed();
    } ...
}
```

- [ ] **Step 3: Bygg och kör enhetstester**

Run: `mvn -pl services/auth-service test`
Expected: BUILD SUCCESS — befintliga tester ska fortfarande passa (vi har inte modifierat dem; integration-testet validerar via stub).

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/
git commit -m "feat(auth-service): wire OutboxPublisher to RabbitTemplate with persistent delivery"
```

---

## Task 4: Deklarera queue + DLQ i user-service

**Files:**
- Create: `services/user-service/src/main/java/com/devroom/user/config/RabbitTopologyConfig.java`

- [ ] **Step 1: Skapa topology**

```java
package com.devroom.user.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitTopologyConfig {

    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String USER_REGISTERED_QUEUE = "user-service.user-registered";
    public static final String USER_REGISTERED_DLQ = "user-service.user-registered.dlq";
    public static final String DLX_EXCHANGE = "devroom.events.dlx";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return QueueBuilder.durable(USER_REGISTERED_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", USER_REGISTERED_DLQ)
                .build();
    }

    @Bean
    public Queue userRegisteredDlq() {
        return QueueBuilder.durable(USER_REGISTERED_DLQ).build();
    }

    @Bean
    public Binding userRegisteredBinding() {
        return BindingBuilder.bind(userRegisteredQueue())
                .to(eventsExchange())
                .with("user.registered");
    }

    @Bean
    public Binding userRegisteredDlqBinding() {
        return BindingBuilder.bind(userRegisteredDlq())
                .to(dlxExchange())
                .with(USER_REGISTERED_DLQ);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add services/user-service/src/main/java/com/devroom/user/config/RabbitTopologyConfig.java
git commit -m "feat(user-service): declare user-registered queue with DLQ"
```

---

## Task 5: Aktivera UserRegisteredConsumer (ta bort @Profile("rabbit"))

**Files:**
- Modify: `services/user-service/src/main/java/com/devroom/user/messaging/UserRegisteredConsumer.java`

- [ ] **Step 1: Ta bort `@Profile`-begränsningen**

```java
@Component
public class UserRegisteredConsumer {
    // ... resten oförändrad
```

- [ ] **Step 2: Lägg till retry-config i application.yml**

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: auto
        prefetch: 10
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2.0
          max-interval: 4s
```

- [ ] **Step 3: Commit**

```bash
git add services/user-service/src/main/java/com/devroom/user/messaging/UserRegisteredConsumer.java \
        services/user-service/src/main/resources/application.yml
git commit -m "feat(user-service): activate user.registered consumer with retry config"
```

---

## Task 6: Skriv idempotency-test för consumer

**Files:**
- Create: `services/user-service/src/test/java/com/devroom/user/UserRegisteredConsumerTest.java`

- [ ] **Step 1: Test som verifierar att duplicate-leverans inte skapar duplicat-rad**

```java
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

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class UserRegisteredConsumerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired UserRegisteredHandler handler;
    @Autowired UserRepository repo;

    @Test
    void duplicateHandlerCallIsIdempotent() {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        handler.handle(userId, "test@example.com", teamId);
        handler.handle(userId, "test@example.com", teamId);

        long count = repo.findAll().stream().filter(u -> u.getUserId().equals(userId)).count();
        assertThat(count).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Kör testet**

Run: `mvn -pl services/user-service verify`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add services/user-service/src/test/java/com/devroom/user/UserRegisteredConsumerTest.java
git commit -m "test(user-service): verify consumer idempotency"
```

---

## Task 7: End-to-end-test: outbox → RabbitMQ

**Files:**
- Create: `services/auth-service/src/test/java/com/devroom/auth/OutboxToRabbitIntegrationTest.java`

- [ ] **Step 1: Test som startar Postgres + RabbitMQ Testcontainers, signar upp en user, och verifierar att meddelandet hamnar i RabbitMQ**

```java
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class OutboxToRabbitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void signupResultsInUserRegisteredEventOnRabbit() {
        // Sätt upp test-queue bunden till samma exchange
        Queue testQueue = QueueBuilder.nonDurable("test.user-registered.observer").build();
        TopicExchange exchange = new TopicExchange("devroom.events", true, false);
        Binding binding = BindingBuilder.bind(testQueue).to(exchange).with("user.registered");
        admin.declareQueue(testQueue);
        admin.declareExchange(exchange);
        admin.declareBinding(binding);

        // Signup
        http.postForEntity("/auth/signup",
                Map.of("email", "rabbit@test.com", "password", "password123"), Map.class);

        // Vänta tills outbox-publishern har gjort sitt
        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            Message msg = rabbitTemplate.receive("test.user-registered.observer", 100);
            assertThat(msg).isNotNull();
            String json = new String(msg.getBody());
            assertThat(json).contains("rabbit@test.com");
            assertThat(json).contains("user.registered");
        });
    }
}
```

- [ ] **Step 2: Lägg till awaitility i POM**

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Kör testet**

Run: `mvn -pl services/auth-service verify`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/
git commit -m "test(auth-service): end-to-end outbox to RabbitMQ verification"
```

---

## Task 8: Manuell smoke-test mot lokal stack

- [ ] **Step 1: Starta full infra**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d
sleep 15
```

- [ ] **Step 2: Starta båda services**

I separat terminal-flikar:
```bash
# Terminal 1
mvn -pl services/auth-service spring-boot:run
# Terminal 2
mvn -pl services/user-service spring-boot:run
```

- [ ] **Step 3: Signup via curl**

```bash
curl -X POST http://localhost:8081/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"e2e@test.com","password":"password123"}'
```

- [ ] **Step 4: Verifiera profil-skapande i UserDB**

Vänta 2-3 sekunder, sedan:

```bash
docker exec devroom-user-db psql -U dbuser -d userdb -c \
  "SELECT user_id, display_name, is_system FROM users WHERE display_name='e2e@test.com';"
```

Expected: en rad med `is_system=false`.

- [ ] **Step 5: Verifiera via gRPC**

```bash
USER_ID=$(docker exec devroom-user-db psql -U dbuser -d userdb -t -c \
  "SELECT user_id FROM users WHERE display_name='e2e@test.com';" | tr -d ' ')
grpcurl -plaintext -import-path proto -proto user.proto \
  -d "{\"user_id\":\"$USER_ID\"}" \
  localhost:9082 devroom.user.v1.UserGrpcService/GetUser
```

Expected: JSON med användarens info.

- [ ] **Step 6: Verifiera RabbitMQ-trafik**

Öppna http://localhost:15672 (login: devroom/devroom). Gå till Queues och se att `user-service.user-registered` har messages-counter > 0 (eller "Delivered" om consumern redan plockat).

- [ ] **Step 7: Stoppa services**

Ctrl+C i båda terminalerna och:
```bash
docker compose -f docker-compose.dev.yml down
```

---

## Task 9: Plan-slut: full verifikation

- [ ] **Step 1: `mvn -B clean verify`**

Expected: BUILD SUCCESS, alla tester passar inkl. det nya end-to-end-testet.

- [ ] **Step 2: Slutkontroll mot Plan-4-Goal**

- [ ] Auth Service publicerar `user.registered` på exchange `devroom.events` med routing key `user.registered`
- [ ] User Service har queue `user-service.user-registered` med DLQ
- [ ] Manuell signup → profil-skapande verifierad
- [ ] Idempotency-test passerar
- [ ] End-to-end Testcontainers-test passerar

---

## Plan 4 — slut

Vid godkänd verifikation: gå vidare till plan 05 (Message Service).
