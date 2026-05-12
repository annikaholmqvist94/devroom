# Plan 07: Bot Service (wrappar Nordic Dev Mentor)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **VIKTIG PRE-STEP:** Innan denna plan körs, läs `~/IdeaProjects/dev-mentor/README.md` och inspektera dess struktur. Bot Service ska INTEGRERA Nordic Dev Mentor som dependency, inte modifiera dess kärnlogik.
>
> **Revision 2026-05-12 — OAuth2-pivot:** Hela auth-mekaniken pivotas. Konkret ändringar:
>
> - **Inget pre-issued service-JWT.** Ersätter `GenerateServiceJwt` (Task 4) och `ServiceTokenProvider` (Task 5) med Spring Security OAuth2 Client + **Client Credentials grant**.
> - **Dependency:** ersätt referens till `auth-starter` (finns inte längre) med `spring-boot-starter-oauth2-client`.
> - **application.yml** lägg till:
>
>   ```yaml
>   spring:
>     security:
>       oauth2:
>         client:
>           registration:
>             auth-service:
>               provider: auth-service
>               client-id: bot-service
>               client-secret: ${BOT_CLIENT_SECRET}
>               authorization-grant-type: client_credentials
>               scope: bot:write
>           provider:
>             auth-service:
>               issuer-uri: ${AUTH_SERVICE_ISSUER:http://localhost:8081}
>   ```
>
> - **MessagePoster** (Task 10) använder `WebClient` (eller `RestClient`) med `ServletOAuth2AuthorizedClientExchangeFilterFunction` som hanterar token-fetching och caching automatiskt:
>
>   ```java
>   @Bean
>   public WebClient messageServiceWebClient(OAuth2AuthorizedClientManager manager) {
>       ServletOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
>               new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
>       oauth2.setDefaultClientRegistrationId("auth-service");
>       return WebClient.builder()
>               .baseUrl(messageServiceUrl)
>               .apply(oauth2.oauth2Configuration())
>               .build();
>   }
>   ```
>
> - Spring sköter resten: hämtar access-token från Auth Server vid första anrop, cachar i `OAuth2AuthorizedClientService`, refreshar när den expirerar.
> - **Task 1 om Nordic Dev Mentor-integration är oförändrad** — den handlar om dev-mentor som kodbas, inte om auth.
> - **Task 11 (Integration test) ska mocka Auth Server:s /oauth2/token-endpoint** via WireMock istället för att mocka pre-issued JWT.

**Goal:** Implementera Bot Service som konsumerar `message-published` från RabbitMQ, filtrerar mentions med `is_system=true`, slår upp avsändare via gRPC, anropar Nordic Dev Mentor för att generera bot-svar, postar svar via REST mot Message Service med service-JWT. Vid plan-slut: skicka ett meddelande med @-mention i lokalt running system → bot-svar dyker upp i samma tråd ~5 sekunder senare.

**Architecture:** Spring Boot 4. Importerar Nordic Dev Mentor som biblioteks-dependency för dess `MentorChatService` (eller motsvarande huvudklass). RabbitMQ-consumer på `bot-service.message-published`-queue. gRPC-klient mot User Service. RestClient mot Message Service med service-JWT från K8s Secret (lokalt: file).

**Tech Stack:** Spring Boot 4, Spring AMQP, gRPC client, Spring RestClient, Nordic Dev Mentor som dependency, JJWT.

**Refererar spec:** sektion 2.1 (Bot Service), 5.2, 4.3.

**Pre-conditions:** plan 01-06 klara. Nordic Dev Mentor lokalt installerat i Maven local repo.

---

## File Structure

```
services/bot-service/
├── pom.xml
├── src/main/java/com/devroom/bot/
│   ├── BotServiceApplication.java
│   ├── config/
│   │   ├── RabbitTopologyConfig.java   # bot-service.message-published-queue + DLQ
│   │   ├── GrpcClientConfig.java
│   │   ├── HttpClientConfig.java       # RestClient mot Message Service
│   │   └── ServiceTokenConfig.java     # läser pre-issued service-JWT från fil
│   ├── messaging/
│   │   └── MessagePublishedConsumer.java
│   ├── application/
│   │   ├── BotReplyOrchestrator.java   # huvudflöde
│   │   ├── MentorClient.java           # wrapper runt Nordic Dev Mentor-kärnan
│   │   ├── MessagePoster.java          # POST /messages med service-JWT
│   │   └── SenderLookup.java           # gRPC GetUser
│   └── domain/
│       └── (delade typer)
├── src/main/resources/
│   └── application.yml
└── src/test/java/com/devroom/bot/
    └── BotServiceIntegrationTest.java
```

---

## Task 1: Förbered Nordic Dev Mentor som lokal Maven-dependency

- [ ] **Step 1: Läs dev-mentor-repot**

```bash
cat ~/IdeaProjects/dev-mentor/README.md
ls ~/IdeaProjects/dev-mentor/
```

Förstå: är det en publicerad lib eller måste vi bygga lokalt? Vad är `groupId:artifactId:version`? Vilken huvudklass bör vi använda?

Om det är en standalone-applikation (har `main`-klass) snarare än en lib, justera Bot Service-strategin: extrahera kärnlogiken som lib eller använd Nordic Dev Mentor som black-box via internt REST-anrop.

- [ ] **Step 2: Installera lokalt om det är en lib**

```bash
cd ~/IdeaProjects/dev-mentor
mvn -DskipTests install
```

Resultat: dev-mentor finns i `~/.m2/repository/...`.

- [ ] **Step 3: Justera planen om Nordic Dev Mentor INTE är en lib**

Om den är en standalone Spring Boot-applikation utan exporterbar lib:
- Alternativ 1: extrahera dess `MentorChatService` + dependencies till en separat module i Devroom monorepo (kopia, refaktorera).
- Alternativ 2: kör Nordic Dev Mentor som egen container i docker-compose/k8s, anropa via REST.

Den enklare vägen för demon är **Alternativ 2** (svart låda via REST). Då blir Bot Service:
1. Konsumera message-published
2. Filtrera mentions
3. Slå upp avsändare via gRPC mot User Service
4. POST /chat eller liknande till Nordic Dev Mentor (own container)
5. POST /messages mot Message Service med svaret

Den här planen antar Alternativ 2 om inget annat sägs vid execution. Justera vid behov.

---

## Task 2: Scaffold Maven-modul

- [ ] **Step 1: pom.xml** — beroenden:
  - `spring-boot-starter-web` (för actuator + health, ingen public REST-API)
  - `spring-boot-starter-amqp`
  - `spring-boot-starter-actuator`
  - `auth-starter` (för att utfärda/använda service-JWT om vi gör det dynamiskt — eller bara läs en pre-issued)
  - `grpc-client-spring-boot-starter` + grpc-protobuf + protobuf-java + protobuf-plugin (för att gen User-proto)
  - Test: spring-boot-starter-test, testcontainers (postgres, rabbitmq), wiremock

Lägg till modulen i parent POM. Commit.

---

## Task 3: Application + config

- [ ] **Step 1: application.yml**

```yaml
server:
  port: 8084

spring:
  application:
    name: bot-service
  rabbitmq:
    host: localhost
    port: 5672
    username: devroom
    password: devroom
    listener:
      simple:
        acknowledge-mode: auto
        prefetch: 5
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2.0
          max-interval: 4s

grpc:
  client:
    user-service:
      address: static://localhost:9082
      negotiationType: plaintext

devroom:
  bot:
    service-jwt-path: ${BOT_SERVICE_JWT_PATH:file:./keys/bot-service.jwt}
    message-service-url: ${MESSAGE_SERVICE_URL:http://localhost:8083}
    nordic-dev-mentor-url: ${NORDIC_DEV_MENTOR_URL:http://localhost:8090}  # om svart låda

management:
  endpoints:
    web:
      exposure:
        include: health
```

Commit.

---

## Task 4: Generera service-JWT (en gång manuellt)

Service-JWT signeras av Auth Services privata nyckel och lagras som fil. Skript för att generera den, körs manuellt en gång:

- [ ] **Step 1: Skriv hjälpklass `GenerateServiceJwt` (i `auth-service/src/main/java/.../tools/`)**

```java
package com.devroom.auth.tools;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtIssuer;
import com.devroom.auth.KeyLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class GenerateServiceJwt {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: GenerateServiceJwt <private-key-pem> <service-name> <output-file>");
            System.exit(1);
        }
        var issuer = new JwtIssuer(KeyLoader.loadPrivateKey(Path.of(args[0])));
        Instant now = Instant.now();
        var claims = JwtClaims.forService(args[1], List.of("system"),
                now, now.plus(Duration.ofDays(365)));
        String jwt = issuer.issue(claims);
        Files.writeString(Path.of(args[2]), jwt);
        System.out.println("Service JWT written to " + args[2]);
    }
}
```

- [ ] **Step 2: Generera bot-service.jwt**

```bash
mvn -pl modules/auth-starter,services/auth-service compile
mvn -pl services/auth-service exec:java -Dexec.mainClass=com.devroom.auth.tools.GenerateServiceJwt \
  -Dexec.args="./keys/private.pem bot-service ./keys/bot-service.jwt"
cat ./keys/bot-service.jwt
```

(Kräver `exec-maven-plugin` i auth-service POM eller exec via java direkt.)

- [ ] **Step 3: Commit hjälpklassen** (men inte JWT-filen).

---

## Task 5: ServiceTokenProvider

```java
package com.devroom.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ServiceTokenProvider {
    private final String token;

    public ServiceTokenProvider(@Value("${devroom.bot.service-jwt-path}") Resource jwtResource) throws IOException {
        try (var in = jwtResource.getInputStream()) {
            this.token = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    public String token() {
        return token;
    }
}
```

Commit.

---

## Task 6: RabbitMQ topology + consumer

```java
// RabbitTopologyConfig.java
@Configuration
public class RabbitTopologyConfig {
    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String QUEUE = "bot-service.message-published";
    public static final String DLQ = "bot-service.message-published.dlq";
    public static final String DLX = "devroom.events.dlx";

    @Bean public TopicExchange eventsExchange() { return new TopicExchange(EVENTS_EXCHANGE, true, false); }
    @Bean public DirectExchange dlx() { return new DirectExchange(DLX, true, false); }

    @Bean public Queue queue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }
    @Bean public Queue dlq() { return QueueBuilder.durable(DLQ).build(); }

    @Bean public Binding binding() {
        return BindingBuilder.bind(queue()).to(eventsExchange()).with("message.published");
    }
    @Bean public Binding dlqBinding() {
        return BindingBuilder.bind(dlq()).to(dlx()).with(DLQ);
    }
}
```

```java
// MessagePublishedConsumer.java
@Component
public class MessagePublishedConsumer {

    private final BotReplyOrchestrator orchestrator;
    private final ObjectMapper mapper;

    public MessagePublishedConsumer(BotReplyOrchestrator orchestrator, ObjectMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "bot-service.message-published")
    public void onMessage(byte[] payloadBytes) throws Exception {
        JsonNode event = mapper.readTree(payloadBytes);
        orchestrator.handle(event);
    }
}
```

Commit.

---

## Task 7: BotReplyOrchestrator (huvudflöde)

```java
@Service
public class BotReplyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BotReplyOrchestrator.class);

    private final SenderLookup senderLookup;
    private final MentorClient mentor;
    private final MessagePoster poster;

    public BotReplyOrchestrator(SenderLookup senderLookup, MentorClient mentor, MessagePoster poster) {
        this.senderLookup = senderLookup;
        this.mentor = mentor;
        this.poster = poster;
    }

    public void handle(JsonNode event) {
        // 1. Filtrera: är det någon mention med is_system=true?
        var mentions = event.get("mentions");
        if (mentions == null || !mentions.isArray()) return;

        for (JsonNode m : mentions) {
            if (!m.get("isSystem").asBoolean()) continue;

            String personality = m.get("personality").asText();
            String mentorUserId = m.get("userId").asText();
            String channelId = event.get("channel_id").asText();
            String senderId = event.get("sender_id").asText();
            String body = event.get("body").asText();
            String originalMessageId = event.get("message_id").asText();
            String parentForReply = event.get("parent_message_id").isNull()
                    ? originalMessageId
                    : event.get("parent_message_id").asText();

            // 2. Slå upp avsändare för personalisering
            String senderName = senderLookup.displayName(senderId);

            // 3. Anropa Nordic Dev Mentor
            String reply = mentor.chat(personality, body, senderName);

            // 4. Posta svaret till Message Service
            poster.post(channelId, mentorUserId, reply, parentForReply, originalMessageId);

            log.info("Posted bot reply from {} to channel {}", personality, channelId);
        }
    }
}
```

Commit.

---

## Task 8: SenderLookup (gRPC)

```java
@Component
public class SenderLookup {
    @GrpcClient("user-service")
    private UserGrpcServiceGrpc.UserGrpcServiceBlockingStub stub;

    public String displayName(String userId) {
        try {
            var u = stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build());
            return u.getDisplayName();
        } catch (StatusRuntimeException e) {
            return "user";  // fallback om uppslag failar
        }
    }
}
```

Commit.

---

## Task 9: MentorClient (svart låda eller direkt-import)

**Variant A — Nordic Dev Mentor som svart låda (REST):**

```java
@Component
public class MentorClient {
    private final RestClient client;

    public MentorClient(@Value("${devroom.bot.nordic-dev-mentor-url}") String url) {
        this.client = RestClient.builder().baseUrl(url).build();
    }

    public String chat(String personality, String question, String senderName) {
        Map<String, Object> body = Map.of(
                "personality", personality,
                "question", question,
                "sender_name", senderName
        );
        Map<String, Object> resp = client.post()
                .uri("/api/chat")  // anpassa till Nordic Dev Mentors faktiska endpoint
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);
        return (String) resp.get("reply");
    }
}
```

**Variant B — Direkt Java-import:**

```java
@Component
public class MentorClient {
    private final NordicDevMentorService service;  // eller motsvarande klass

    public MentorClient(NordicDevMentorService service) {
        this.service = service;
    }

    public String chat(String personality, String question, String senderName) {
        return service.chat(personality, question, senderName);
    }
}
```

Anpassa baserat på Nordic Dev Mentor-strukturen som upptäcktes i Task 1. Commit.

---

## Task 10: MessagePoster (REST + service-JWT)

```java
@Component
public class MessagePoster {
    private final RestClient client;
    private final ServiceTokenProvider tokenProvider;

    public MessagePoster(@Value("${devroom.bot.message-service-url}") String url,
                          ServiceTokenProvider tokenProvider) {
        this.client = RestClient.builder().baseUrl(url).build();
        this.tokenProvider = tokenProvider;
    }

    public void post(String channelId, String asUserId, String body,
                      String parentMessageId, String idempotencyKey) {
        Map<String, Object> req = new HashMap<>();
        req.put("channelId", channelId);
        req.put("body", body);
        req.put("asUserId", asUserId);
        if (parentMessageId != null) req.put("parentMessageId", parentMessageId);

        client.post()
                .uri("/messages")
                .header("Authorization", "Bearer " + tokenProvider.token())
                .header("Idempotency-Key", "bot-reply-" + idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .toBodilessEntity();
    }
}
```

OBS: `Idempotency-Key`-headern är ett mönster vi nämner i specet. Implementation i Message Service är out-of-scope för nu — om duplicering blir ett problem i praktiken adressera det vid testning. För demon räcker det att Bot Service inkluderar headern; Message Service kan utvidgas senare.

Commit.

---

## Task 11: Integration test

Använd Testcontainers för Postgres + RabbitMQ + WireMock för Nordic Dev Mentor + Message Service. Mockar User Service-gRPC via in-process-server. Verifiera att en publicerad event leder till en POST mot Message Service med rätt payload.

Commit.

---

## Task 12: Manuell smoke-test (full flöde end-to-end)

```bash
# Starta Nordic Dev Mentor som svart-låda i en separat container/process
# (eller kör den från ~/IdeaProjects/dev-mentor)

docker compose -f docker-compose.dev.yml up -d
# Starta auth, user, message, bff, bot
mvn -pl services/auth-service spring-boot:run &
mvn -pl services/user-service spring-boot:run &
mvn -pl services/message-service spring-boot:run &
mvn -pl services/gateway spring-boot:run &
mvn -pl services/bot-service spring-boot:run &
sleep 30

# Genom BFF
RESP=$(curl -s -X POST http://localhost:8080/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"e2e@bot.com","password":"password123"}')
TOKEN=$(echo $RESP | jq -r .jwt)
sleep 3

curl -X POST http://localhost:8080/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"channelId":"33333333-3333-3333-3333-333333333301","body":"Hej @code-reviewer kan du förklara DI?"}'

# Vänta på bot-svar
sleep 8

curl "http://localhost:8080/messages?channelId=33333333-3333-3333-3333-333333333301" \
  -H "Authorization: Bearer $TOKEN" | jq
```

Expected: två meddelanden i kanalen — ditt original och ett bot-svar med `senderId` matchande code-reviewer-UUID och `parentMessageId` pekande på ditt original.

---

## Task 13: Plan-slut

- [ ] `mvn -B clean verify` passerar
- [ ] End-to-end mention → bot-svar fungerar lokalt
- [ ] Bot-svaret hamnar i samma tråd (`parent_message_id` korrekt)
- [ ] Service-JWT genererad och lagrad i `keys/bot-service.jwt` (utanför git)

---

## Plan 7 — slut

Vid godkänd verifikation: gå vidare till plan 08 (Frontend).
