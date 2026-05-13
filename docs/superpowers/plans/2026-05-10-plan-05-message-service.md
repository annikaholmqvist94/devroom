# Plan 05: Message Service

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Detta är den största servicen — räkna med två arbetsdagar.
>
> **Revision 2026-05-12 — OAuth2-pivot:** Task 9 (JwtAuthenticationFilter + SecurityConfig) ska ersättas med Spring Resource Server-konfiguration. Konkret:
>
> - Ersätt `auth-starter`-dependency (finns inte längre) med `spring-boot-starter-oauth2-resource-server`.
> - Ta bort `security/JwtAuthenticationFilter.java` och `config/SecurityConfig.java`'s handskrivna `JwtAuthenticationFilter`.
> - I `application.yml`, lägg till:
>
>   ```yaml
>   spring:
>     security:
>       oauth2:
>         resourceserver:
>           jwt:
>             jwk-set-uri: ${AUTH_SERVICE_JWKS_URI:http://localhost:8081/.well-known/jwks.json}
>             issuer-uri: ${AUTH_SERVICE_ISSUER:http://localhost:8081}
>   ```
>
> - I `SecurityConfig.java`, använd:
>
>   ```java
>   @Bean
>   public SecurityFilterChain chain(HttpSecurity http) throws Exception {
>       http
>           .csrf(csrf -> csrf.disable())
>           .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
>           .authorizeHttpRequests(a -> a
>               .requestMatchers("/actuator/**").permitAll()
>               .requestMatchers(HttpMethod.POST, "/messages").hasAnyAuthority("SCOPE_profile", "SCOPE_bot:write")
>               .anyRequest().authenticated())
>           .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
>       return http.build();
>   }
>   ```
>
> - JwtClaims-extraktion: använd `Authentication.getPrincipal()` som castar till `Jwt`. Läs `team_id`-claim via `jwt.getClaimAsString("team_id")`. För service-token: `jwt.getClaimAsStringList("scope")` innehåller "bot:write".
> - Task 11 (as_user_id-säkerhetscheck): logik oförändrad men implementationen läser `as_user_id` från body och scope `bot:write` från Jwt-principalen istället för `JwtClaims.isService()`.

**Goal:** Implementera Message Service: `channels` + `messages`-tabeller (med trådar via `parent_message_id` och JSONB-mentions), POST/GET REST-endpoints, gRPC-klient mot User Service för mention-resolution, RabbitMQ-publisher för `message-published`-events. Vid plan-slut kan POST /messages skapa ett meddelande, resolva mentions, lagra det med korrekt mentions-array, och publicera ett event som senare konsumeras av Bot Service.

**Architecture:** Spring Boot 4 + JPA + Flyway. JWT-validering via `auth-starter`'s `JwtValidator` (filter i Spring Security). gRPC-klient via `grpc-client-spring-boot-starter`. Mention-extraktion: regex `@([a-z0-9-]+)` mot body, sedan gRPC ResolveMentions. Vid skriv: DB-write + RabbitMQ-publish i samma `@Transactional`-metod (utan outbox — se ADR-0008 reservpott).

**Tech Stack:** Spring Boot 4, Spring Data JPA, Flyway, Postgres 16, gRPC client, Spring AMQP, Testcontainers.

**Refererar spec:** sektion 3.3, 5.2, 4.1-4.3.

**Pre-conditions:** plan 01-04 klara. User Service kör med gRPC-server. RabbitMQ kör.

---

## File Structure

```
services/message-service/
├── pom.xml
├── src/main/java/com/devroom/message/
│   ├── MessageServiceApplication.java
│   ├── config/
│   │   ├── JwtConfig.java                  # public-key bean + JwtValidator bean
│   │   ├── SecurityConfig.java             # JWT-filter chain
│   │   ├── GrpcClientConfig.java           # User Service stub
│   │   └── RabbitTopologyConfig.java       # devroom.events exchange (skickar)
│   ├── domain/
│   │   ├── Channel.java
│   │   ├── ChannelRepository.java
│   │   ├── Message.java
│   │   ├── MessageRepository.java
│   │   └── MentionInfo.java               # JSONB-mappad värdeklass
│   ├── application/
│   │   ├── PostMessageService.java        # huvudflöde
│   │   ├── MentionParser.java             # regex
│   │   ├── MentionResolver.java           # gRPC-klient-wrapper
│   │   ├── MessageEventPublisher.java     # publish-helper
│   │   ├── ChannelNotFoundException.java
│   │   └── MentionResolutionException.java
│   ├── web/
│   │   ├── MessageController.java
│   │   ├── MessageDtos.java
│   │   └── ExceptionHandlers.java
│   ├── security/
│   │   └── JwtAuthenticationFilter.java   # bygger SecurityContext från JWT
│   └── infra/
│       └── (övrigt)
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_channels_and_messages.sql
│       └── V2__seed_demo_channels.sql
└── src/test/java/com/devroom/message/
    └── MessageServiceIntegrationTest.java
```

---

## Task 1: Scaffold Maven-modul

**Files:**
- Create: `services/message-service/pom.xml`
- Modify: `pom.xml`

- [ ] **Step 1: Modul-POM** (samma stomme som user-service men med dessa dependencies):

```xml
<dependencies>
    <dependency>
        <groupId>com.devroom</groupId>
        <artifactId>auth-starter</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>

    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>com.vladmihalcea</groupId>
        <artifactId>hibernate-types-60</artifactId>
        <version>2.21.1</version>
    </dependency>

    <!-- gRPC client -->
    <dependency>
        <groupId>net.devh</groupId>
        <artifactId>grpc-client-spring-boot-starter</artifactId>
        <version>3.1.0.RELEASE</version>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-protobuf</artifactId>
        <version>${grpc.version}</version>
    </dependency>
    <dependency>
        <groupId>io.grpc</groupId>
        <artifactId>grpc-stub</artifactId>
        <version>${grpc.version}</version>
    </dependency>
    <dependency>
        <groupId>com.google.protobuf</groupId>
        <artifactId>protobuf-java</artifactId>
        <version>${protobuf.version}</version>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>postgresql</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>rabbitmq</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

`<build>` block samma som user-service (protobuf-plugin för att gen koden, spring-boot-plugin).

- [ ] **Step 2: Lägg till modulen i parent POM** + commit.

---

## Task 2: Application + config

- [ ] **Step 1: `application.yml`**

```yaml
server:
  port: 8083

spring:
  application:
    name: message-service
  datasource:
    url: jdbc:postgresql://localhost:5434/messagedb
    username: dbuser
    password: dbpass
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  rabbitmq:
    host: localhost
    port: 5672
    username: devroom
    password: devroom

grpc:
  client:
    user-service:
      address: static://localhost:9082
      negotiationType: plaintext

devroom:
  message:
    public-key-path: ${AUTH_PUBLIC_KEY_PATH:file:./keys/public.pem}
    issuer: auth-service
    demo-team-id: 11111111-1111-1111-1111-111111111111

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 2: `MessageServiceApplication.java`** + commit.

---

## Task 3: Flyway-migrations

- [ ] **Step 1: V1 — channels + messages**

```sql
-- V1__create_channels_and_messages.sql
CREATE TABLE channels (
    id          UUID PRIMARY KEY,
    team_id     UUID NOT NULL,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (team_id, name)
);

CREATE TABLE messages (
    id                  UUID PRIMARY KEY,
    channel_id          UUID NOT NULL REFERENCES channels(id),
    sender_id           UUID NOT NULL,
    body                TEXT NOT NULL,
    parent_message_id   UUID REFERENCES messages(id),
    mentions            JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_channel_created
    ON messages(channel_id, created_at DESC);
CREATE INDEX idx_messages_parent
    ON messages(parent_message_id)
    WHERE parent_message_id IS NOT NULL;
```

- [ ] **Step 2: V2 — seed demo-kanaler**

```sql
-- V2__seed_demo_channels.sql
INSERT INTO channels (id, team_id, name) VALUES
    ('33333333-3333-3333-3333-333333333301', '11111111-1111-1111-1111-111111111111', 'general'),
    ('33333333-3333-3333-3333-333333333302', '11111111-1111-1111-1111-111111111111', 'frontend'),
    ('33333333-3333-3333-3333-333333333303', '11111111-1111-1111-1111-111111111111', 'backend')
ON CONFLICT (id) DO NOTHING;
```

- [ ] **Step 3: Commit**.

---

## Task 4: Domän-entiteter

- [ ] **Step 1: `MentionInfo`-värdeklass + `Channel` + `Message` entiteter**

```java
// MentionInfo.java
package com.devroom.message.domain;

public record MentionInfo(String userId, boolean isSystem, String personality) {}
```

```java
// Channel.java — standard JPA-entitet, fält: id (UUID), teamId, name, createdAt
```

```java
// Message.java — JPA med JSONB-mentions via @Type-annotation
package com.devroom.message.domain;

import com.vladmihalcea.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class Message {

    @Id private UUID id;

    @Column(name = "channel_id", nullable = false) private UUID channelId;
    @Column(name = "sender_id", nullable = false) private UUID senderId;
    @Column(nullable = false) private String body;
    @Column(name = "parent_message_id") private UUID parentMessageId;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<MentionInfo> mentions;

    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected Message() {}

    public Message(UUID id, UUID channelId, UUID senderId, String body,
                    UUID parentMessageId, List<MentionInfo> mentions) {
        this.id = id;
        this.channelId = channelId;
        this.senderId = senderId;
        this.body = body;
        this.parentMessageId = parentMessageId;
        this.mentions = mentions;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getChannelId() { return channelId; }
    public UUID getSenderId() { return senderId; }
    public String getBody() { return body; }
    public UUID getParentMessageId() { return parentMessageId; }
    public List<MentionInfo> getMentions() { return mentions; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: Repos**

```java
// ChannelRepository
public interface ChannelRepository extends JpaRepository<Channel, UUID> {}

// MessageRepository
public interface MessageRepository extends JpaRepository<Message, UUID> {
    @Query("SELECT m FROM Message m WHERE m.channelId = :channelId AND m.createdAt > :since ORDER BY m.createdAt ASC")
    List<Message> findByChannelSince(@Param("channelId") UUID channelId, @Param("since") Instant since);

    List<Message> findByChannelIdOrderByCreatedAtAsc(UUID channelId);
}
```

- [ ] **Step 3: Commit**.

---

## Task 5: `MentionParser`

- [ ] **Step 1: TDD — failing test**

```java
@Test
void extractsAtMentions() {
    var parser = new MentionParser();
    var result = parser.extract("Hej @code-reviewer kan du kolla @junior-helper också?");
    assertThat(result).containsExactly("code-reviewer", "junior-helper");
}

@Test
void deduplicatesMentions() {
    var parser = new MentionParser();
    assertThat(parser.extract("@dup @dup hello")).containsExactly("dup");
}

@Test
void returnsEmptyWhenNoMentions() {
    assertThat(new MentionParser().extract("just text")).isEmpty();
}
```

- [ ] **Step 2: Implementation**

```java
package com.devroom.message.application;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MentionParser {

    private static final Pattern MENTION = Pattern.compile("@([a-z0-9-]+)");

    public List<String> extract(String body) {
        var result = new LinkedHashSet<String>();
        Matcher m = MENTION.matcher(body);
        while (m.find()) {
            result.add(m.group(1));
        }
        return List.copyOf(result);
    }
}
```

- [ ] **Step 3: Commit**.

---

## Task 6: `MentionResolver` (gRPC-klient)

- [ ] **Step 1: Skriv klient-wrappern**

```java
package com.devroom.message.application;

import com.devroom.message.domain.MentionInfo;
import com.devroom.user.grpc.ResolveMentionsRequest;
import com.devroom.user.grpc.ResolveMentionsResponse;
import com.devroom.user.grpc.UserGrpcServiceGrpc;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class MentionResolver {

    @GrpcClient("user-service")
    private UserGrpcServiceGrpc.UserGrpcServiceBlockingStub stub;

    public List<MentionInfo> resolve(UUID teamId, List<String> displayNames) {
        if (displayNames.isEmpty()) return List.of();
        try {
            ResolveMentionsResponse resp = stub.resolveMentions(ResolveMentionsRequest.newBuilder()
                    .setTeamId(teamId.toString())
                    .addAllDisplayNames(displayNames)
                    .build());
            return resp.getUsersList().stream()
                    .map(u -> new MentionInfo(u.getUserId(), u.getIsSystem(),
                            u.getMentorPersonality().isEmpty() ? null : u.getMentorPersonality()))
                    .collect(Collectors.toList());
        } catch (StatusRuntimeException e) {
            throw new MentionResolutionException("Failed to resolve mentions: " + e.getStatus(), e);
        }
    }
}
```

- [ ] **Step 2: Skapa exception-class** + **commit**.

---

## Task 7: `MessageEventPublisher`

- [ ] **Step 1: Topology-config**

```java
// services/message-service/src/main/java/com/devroom/message/config/RabbitTopologyConfig.java
@Configuration
public class RabbitTopologyConfig {
    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String MESSAGE_PUBLISHED_ROUTING_KEY = "message.published";

    @Bean public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }
}
```

- [ ] **Step 2: Publisher**

```java
package com.devroom.message.application;

import com.devroom.message.domain.Message;
import com.devroom.message.domain.MentionInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.devroom.message.config.RabbitTopologyConfig.*;

@Component
public class MessageEventPublisher {

    private final RabbitTemplate rabbit;
    private final ObjectMapper mapper;
    private final String teamIdDefault;

    public MessageEventPublisher(RabbitTemplate rabbit, ObjectMapper mapper,
                                  @Value("${devroom.message.demo-team-id}") String teamIdDefault) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.teamIdDefault = teamIdDefault;
    }

    public void publish(Message message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("event_type", "message-published");
        payload.put("occurred_at", Instant.now().toString());
        payload.put("message_id", message.getId().toString());
        payload.put("channel_id", message.getChannelId().toString());
        payload.put("team_id", teamIdDefault);
        payload.put("sender_id", message.getSenderId().toString());
        payload.put("body", message.getBody());
        payload.put("parent_message_id", message.getParentMessageId() == null ? null : message.getParentMessageId().toString());
        payload.put("mentions", message.getMentions());

        try {
            String json = mapper.writeValueAsString(payload);
            org.springframework.amqp.core.Message amqp = MessageBuilder.withBody(json.getBytes())
                    .setContentType("application/json")
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .build();
            rabbit.send(EVENTS_EXCHANGE, MESSAGE_PUBLISHED_ROUTING_KEY, amqp);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }
}
```

- [ ] **Step 3: Commit**.

---

## Task 8: `PostMessageService` — huvudflöde

- [ ] **Step 1: Implementation**

```java
package com.devroom.message.application;

import com.devroom.message.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PostMessageService {

    private final ChannelRepository channelRepo;
    private final MessageRepository messageRepo;
    private final MentionParser parser;
    private final MentionResolver resolver;
    private final MessageEventPublisher publisher;
    private final UUID demoTeamId;

    public PostMessageService(ChannelRepository channelRepo, MessageRepository messageRepo,
                               MentionParser parser, MentionResolver resolver,
                               MessageEventPublisher publisher,
                               @org.springframework.beans.factory.annotation.Value("${devroom.message.demo-team-id}") String demoTeamId) {
        this.channelRepo = channelRepo;
        this.messageRepo = messageRepo;
        this.parser = parser;
        this.resolver = resolver;
        this.publisher = publisher;
        this.demoTeamId = UUID.fromString(demoTeamId);
    }

    @Transactional
    public Message post(UUID channelId, UUID senderId, String body, UUID parentMessageId) {
        if (!channelRepo.existsById(channelId)) {
            throw new ChannelNotFoundException(channelId);
        }

        // Bestäm parent: om parentMessageId pekar på en message med en parent, plattgör
        UUID effectiveParent = parentMessageId;
        if (parentMessageId != null) {
            Message parent = messageRepo.findById(parentMessageId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent message not found"));
            if (parent.getParentMessageId() != null) {
                effectiveParent = parent.getParentMessageId();
            }
        }

        List<String> mentionNames = parser.extract(body);
        List<MentionInfo> mentions = resolver.resolve(demoTeamId, mentionNames);

        Message msg = new Message(UUID.randomUUID(), channelId, senderId, body, effectiveParent, mentions);
        messageRepo.save(msg);
        publisher.publish(msg);

        return msg;
    }
}
```

- [ ] **Step 2: Commit**.

---

## Task 9: JWT-validering: filter + security config

- [ ] **Step 1: Skapa JwtConfig (public-key-bean)**

```java
@Configuration
public class JwtConfig {
    @Bean
    public JwtValidator jwtValidator(@Value("${devroom.message.public-key-path}") Resource publicKeyResource,
                                      @Value("${devroom.message.issuer}") String issuer) throws IOException {
        Path tempPath = Files.createTempFile("auth-public-", ".pem");
        Files.copy(publicKeyResource.getInputStream(), tempPath, StandardCopyOption.REPLACE_EXISTING);
        tempPath.toFile().deleteOnExit();
        return new JwtValidator(KeyLoader.loadPublicKey(tempPath), issuer);
    }
}
```

- [ ] **Step 2: Skapa `JwtAuthenticationFilter`**

```java
package com.devroom.message.security;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtValidator;
import com.devroom.auth.InvalidJwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtValidator validator;

    public JwtAuthenticationFilter(JwtValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtClaims claims = validator.validate(token);
                List<SimpleGrantedAuthority> authorities = claims.roles().stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                        .collect(Collectors.toList());
                var auth = new UsernamePasswordAuthenticationToken(claims, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (InvalidJwtException e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"error\":\"invalid_token\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 3: SecurityConfig**

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain chain(HttpSecurity http, JwtAuthenticationFilter filter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 4: Commit**.

---

## Task 10: REST-controllers

- [ ] **Step 1: DTOs**

```java
public final class MessageDtos {
    public record PostMessageRequest(
            UUID channelId,
            String body,
            UUID parentMessageId,
            UUID asUserId  // bara för service-token
    ) {}

    public record MessageResponse(
            UUID id,
            UUID channelId,
            UUID senderId,
            String body,
            UUID parentMessageId,
            List<MentionInfo> mentions,
            Instant createdAt
    ) {}
}
```

- [ ] **Step 2: MessageController**

```java
@RestController
@RequestMapping("/messages")
public class MessageController {

    private final PostMessageService postService;
    private final MessageRepository repo;

    public MessageController(PostMessageService postService, MessageRepository repo) {
        this.postService = postService;
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<MessageResponse> post(@RequestBody PostMessageRequest req,
                                                 Authentication authentication) {
        JwtClaims claims = (JwtClaims) authentication.getPrincipal();
        UUID senderId = resolveSender(claims, req);
        Message msg = postService.post(req.channelId(), senderId, req.body(), req.parentMessageId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(msg));
    }

    @GetMapping
    public List<MessageResponse> get(
            @RequestParam UUID channelId,
            @RequestParam(required = false) Instant since) {
        var msgs = since == null
                ? repo.findByChannelIdOrderByCreatedAtAsc(channelId)
                : repo.findByChannelSince(channelId, since);
        return msgs.stream().map(this::toDto).toList();
    }

    private UUID resolveSender(JwtClaims claims, PostMessageRequest req) {
        if (claims.isService()) {
            if (req.asUserId() == null) {
                throw new IllegalArgumentException("Service token requires as_user_id");
            }
            // TODO: verify as_user_id pekar på is_system=true via gRPC mot User
            return req.asUserId();
        }
        return UUID.fromString(claims.subject());
    }

    private MessageResponse toDto(Message m) {
        return new MessageResponse(m.getId(), m.getChannelId(), m.getSenderId(), m.getBody(),
                m.getParentMessageId(), m.getMentions(), m.getCreatedAt());
    }
}
```

- [ ] **Step 3: ExceptionHandlers** (handla `ChannelNotFoundException`, `MentionResolutionException`, `IllegalArgumentException`).

- [ ] **Step 4: Commit**.

---

## Task 11: Verifiera as_user_id mot User Service (säkerhetscheck)

**Spec sektion 4.3:** "kräv att body.as_user_id ... pekar på en user där is_system=true (verifieras via gRPC mot User Service)".

- [ ] **Step 1: Lägg till gRPC-anrop i `resolveSender`**

```java
private UUID resolveSender(JwtClaims claims, PostMessageRequest req, MentionResolver mr) {
    // ... om claims.isService():
    User user = userGrpcStub.getUser(GetUserRequest.newBuilder()
            .setUserId(req.asUserId().toString()).build());
    if (!user.getIsSystem()) {
        throw new SecurityException("Service token can only post as system users");
    }
    return req.asUserId();
}
```

(Inject `UserGrpcServiceBlockingStub` i controllern eller via en separat helper-bean.)

- [ ] **Step 2: Commit**.

---

## Task 12: Testcontainers integrationstest

**Files:**
- Create: `services/message-service/src/test/java/com/devroom/message/MessageServiceIntegrationTest.java`

- [ ] **Step 1: Setup-test som startar PostgreSQL + RabbitMQ + ett mockat gRPC server för User Service**

För att hålla testet komplett: använd ett in-process gRPC-mock-server (via `io.grpc.testing.GrpcServerRule` eller egen registrering).

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MessageServiceIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");

    // Mock User Service gRPC: starta en in-process server som svarar på ResolveMentions och GetUser

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.rabbitmq.host", rabbit::getHost);
        r.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        r.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        r.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        r.add("grpc.client.user-service.address", () -> "static://localhost:" + IN_PROCESS_GRPC_PORT);
    }

    @Test
    void postMessageResolvesMentionsAndPublishesEvent() {
        // POST /messages med JWT, verifiera:
        // - 201 returneras
        // - mentions-array innehåller resolved info
        // - RabbitMQ-message har skickats (lyssna på en test-queue)
    }

    @Test
    void postWithoutJwtIs401() { /* ... */ }

    @Test
    void getMessagesSinceReturnsOnlyNewer() { /* ... */ }
}
```

- [ ] **Step 2: Implementera och kör — fix tills BUILD SUCCESS**.

- [ ] **Step 3: Commit**.

---

## Task 13: Skriv ADR-0004 (gRPC vs REST)

```markdown
# ADR-0004: gRPC vs REST — var och varför

**Status:** Accepted
**Date:** 2026-05-10

## Decision
- **REST** för: klient → BFF, BFF → interna tjänster, Bot → Message Service.
- **gRPC** för: intern read-trafik mellan services (Message → User vid skriv, Bot → User för avsändaruppslag).

## Considered alternatives
- gRPC överallt — avvisad pga duplicerad skriv-endpoint i Message Service.
- REST överallt — avvisad pga kursen kräver gRPC, plus gRPC är bättre för intern trafik.

## Consequences
+ Stark typning (proto) där det betyder mest (interna kontrakt).
+ JSON där det är operativt enklast (klient, debug).
- Två protokoll att underhålla. auth-starter och proto-genereringen reducerar kostnaden.
```

Commit.

---

## Task 14: Manuell smoke-test

```bash
docker compose up -d
mvn -pl services/auth-service spring-boot:run &
mvn -pl services/user-service spring-boot:run &
mvn -pl services/message-service spring-boot:run &
sleep 20

# Signup
RESP=$(curl -s -X POST http://localhost:8081/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"manual@test.com","password":"password123"}')
TOKEN=$(echo $RESP | jq -r .jwt)
echo "Got JWT: $TOKEN"

# Vänta på att profilen skapas
sleep 3

# Posta ett meddelande med mention
curl -X POST http://localhost:8083/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"channelId":"33333333-3333-3333-3333-333333333301","body":"Hello @code-reviewer"}'

# Hämta meddelanden
curl http://localhost:8083/messages?channelId=33333333-3333-3333-3333-333333333301 \
  -H "Authorization: Bearer $TOKEN" | jq

# Verifiera RabbitMQ-trafik på http://localhost:15672
```

Expected: response innehåller `mentions: [{userId, isSystem: true, personality: "code-reviewer"}]`.

---

## Task 15: Plan-slut: full verifikation

- [ ] `mvn -B clean verify`
- [ ] Manuell smoke-test
- [ ] ADR-0004 skriven

---

## Plan 5 — slut

Vid godkänd verifikation: gå vidare till plan 06 (BFF).
