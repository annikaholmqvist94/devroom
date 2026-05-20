# Plan 07: Bot Service (wrappar Nordic Dev Mentor)

> **Revisionshistorik:**
> - **2026-05-12 — OAuth2-pivot:** byter pre-issued service-JWT mot Spring Security OAuth2 Client + Client Credentials grant. `GenerateServiceJwt` + `ServiceTokenProvider` ersätts av automatisk token-hantering.
> - **2026-05-20 — Konsekvens-pivot:** RestClient + `OAuth2ClientHttpRequestInterceptor` istället för WebClient + `ServletOAuth2AuthorizedClientExchangeFilterFunction`. Matchar Plan 06 (WebMVC-Gateway) — en servlet-mental-modell i hela repot, ingen reactive paradigm. Se ADR-0008.
> - **2026-05-20 — Nordic Dev Mentor-strategi fastställd:** dev-mentor är en standalone Spring Boot-app, INTE en lib. **Variant A (svart låda via REST)** vald — Bot Service anropar `POST /api/v1/chat` med `{personality, message, sessionId?}`. Dev-mentor körs på port 8090 (för att inte kollidera med Gateway:s 8080).

**Goal:** Implementera Bot Service som konsumerar `message.published` från RabbitMQ, filtrerar mentions med `isSystem=true`, slår upp avsändare via gRPC, anropar Nordic Dev Mentor för att generera bot-svar, postar svar via REST mot Message Service med OAuth2-token. Vid plan-slut: `mvn -B clean verify` grön + integration-test bevisar end-to-end-flödet utan att kräva externa services uppe.

**Architecture:** Spring Boot 4. Konsumerar `message.published` på durable queue `bot-service.message-published`. gRPC-klient mot User Service (Spring gRPC 1.0.3, samma mönster som Message Service). RestClient mot Nordic Dev Mentor (publikt API). RestClient + `OAuth2ClientHttpRequestInterceptor` mot Message Service med automatisk token-hantering via `OAuth2AuthorizedClientManager`.

**Tech Stack:** Spring Boot 4.0.6, Spring AMQP, Spring gRPC 1.0.3, Spring Security 7 OAuth2 Client, Jackson 3, Testcontainers (RabbitMQ), WireMock, InProcess gRPC.

**Refererar spec:** sektion 2.1 (Bot Service), 5.2, 4.3.

**Pre-conditions:**
- ✅ Plan 01-06 mergade till `main`
- ✅ Nordic Dev Mentor finns lokalt på `~/IdeaProjects/dev-mentor` (Spring Boot 4, port 8080)
- ✅ Auth Service har bot-service-client registrerad (`client-id: bot-service`, `client_credentials`, scope `bot:write`) — i `services/auth-service/src/main/resources/application.yml:57-71`
- ✅ Message Service publicerar `message.published` på `devroom.events` med routing-key `message.published` och payload-fält `mentions: [{userId, isSystem, personality}]`

---

## File Structure

```
services/bot-service/
├── pom.xml
├── src/main/java/com/devroom/bot/
│   ├── BotServiceApplication.java
│   ├── config/
│   │   ├── RabbitTopologyConfig.java   # bot-service.message-published-queue + DLQ
│   │   ├── GrpcClientConfig.java       # ManagedChannel mot user-service
│   │   ├── MentorRestClientConfig.java # RestClient mot dev-mentor
│   │   └── MessageServiceClientConfig.java # RestClient + OAuth2-interceptor
│   ├── messaging/
│   │   └── MessagePublishedConsumer.java
│   ├── application/
│   │   ├── BotReplyOrchestrator.java   # huvudflöde
│   │   ├── MentorClient.java           # wrapper runt dev-mentor REST
│   │   ├── MessagePoster.java          # POST /messages med OAuth2-token
│   │   └── SenderLookup.java           # gRPC GetUser
│   └── domain/
│       └── (delade typer vid behov)
├── src/main/resources/
│   └── application.yml
└── src/test/java/com/devroom/bot/
    ├── BotServiceIntegrationTest.java
    └── support/
        └── (test-stöd-klasser, t.ex. WireMock-extensions)
```

---

## Task 1: Förbered Nordic Dev Mentor som black-box ✅ KLAR

**Resultat av inspektion 2026-05-20:**

- `~/IdeaProjects/dev-mentor` är en **standalone Spring Boot 4-app**, inte en lib
- REST-yta: `POST /api/v1/chat` med `{ personality, message, sessionId? }` → `{ sessionId, personality, reply }`
- Default-port `8080` — måste mappas om till `8090` via `SERVER_PORT=8090` för att inte kollidera med Gateway
- Ingen auth (känd limitation i deras README) — Bot Service kallar utan token
- 4 personalities matchar våra mentor-users seedade i Plan 03: `junior-helper`, `senior-architect`, `code-reviewer`, `rubber-duck`

**Beslut: Variant A (svart låda via REST).** Variant B (direkt-import) skulle kräva att vi mängd-refaktorerar dev-mentor till en lib — onödigt scope när REST-API:et redan finns. Att starta dev-mentor som egen container i compose/k8s är Plan 10-jobb.

---

## Task 2: Scaffold Maven-modul

**Steg 1: Skapa `services/bot-service/pom.xml`** — kopia av `message-service/pom.xml`-strukturen, med skillnaden att:
- INGEN `spring-boot-starter-data-jpa`, `-flyway`, `-validation`, postgres — Bot Service har ingen egen DB
- INGEN `-starter-oauth2-resource-server` — Bot Service är inte en HTTP-yta för andra klienter
- LÄGG TILL `spring-boot-starter-oauth2-client` — för att hämta Client Credentials-token mot Auth Service
- INGEN `spring-grpc-server-spring-boot-starter` i test — vi mockar User Service med Spring gRPC:s in-process-stöd
- LÄGG TILL `wiremock-standalone` (test) — mockar dev-mentor + Auth Service:s `/oauth2/token` + Message Service

**Steg 2: Lägg till modulen** i parent `pom.xml` `<modules>`-blocket efter `gateway`.

**Steg 3: Verifiera build** med `mvn -pl services/bot-service compile` — ska bygga (även om det inte finns Java-kod än, ska scaffold:en kompilera).

Commit: `chore(bot-service): scaffold maven module`

---

## Task 3: BotServiceApplication + application.yml

**Steg 1: `BotServiceApplication.java`**

```java
@SpringBootApplication
public class BotServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BotServiceApplication.class, args);
    }
}
```

**Steg 2: `application.yml`** — port `8084`, RabbitMQ-listener-config (samma retry-mönster som User Service: 3 försök, exponentiell backoff 1s→2s→4s), Spring gRPC client mot user-service, OAuth2 Client Credentials mot Auth Service, dev-mentor + Message Service URL:er.

Nyckel-config:

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          auth-service:
            provider: auth-service
            client-id: bot-service
            client-secret: ${BOT_CLIENT_SECRET:dev-bot-secret-change-me}
            authorization-grant-type: client_credentials
            scope: bot:write
        provider:
          auth-service:
            issuer-uri: ${AUTH_SERVICE_ISSUER:http://localhost:8081}

spring.grpc.client.channels.user-service:
  address: static://localhost:9082
  negotiation-type: plaintext

devroom.bot:
  message-service-url: ${MESSAGE_SERVICE_URL:http://localhost:8083}
  nordic-dev-mentor-url: ${NORDIC_DEV_MENTOR_URL:http://localhost:8090}
```

**Varför `issuer-uri`?** Spring Security gör en `GET ${issuer-uri}/.well-known/openid-configuration` vid bean-skapande, hittar `token_endpoint`, och vet då vart Client Credentials-anropet ska. Samma OIDC discovery-mönster som Gateway använder.

**Varför `provider`-key:n `auth-service`?** Spring registrerar en `OAuth2AuthorizedClientProvider` per registration. När vi senare gör `manager.authorize(... .principal("bot-service") .clientRegistrationId("auth-service") ...)` använder den den config:en.

Commit: `feat(bot-service): application + config`

---

## Task 4: RabbitTopologyConfig

```java
@Configuration
public class RabbitTopologyConfig {
    public static final String EVENTS_EXCHANGE = "devroom.events";
    public static final String QUEUE = "bot-service.message-published";
    public static final String DLQ = "bot-service.message-published.dlq";
    public static final String DLX = "devroom.events.dlx";
    public static final String ROUTING_KEY = "message.published";

    @Bean public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean public DirectExchange dlx() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean public Queue queue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX)
                .withArgument("x-dead-letter-routing-key", DLQ)
                .build();
    }

    @Bean public Queue dlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean public Binding binding() {
        return BindingBuilder.bind(queue()).to(eventsExchange()).with(ROUTING_KEY);
    }

    @Bean public Binding dlqBinding() {
        return BindingBuilder.bind(dlq()).to(dlx()).with(DLQ);
    }
}
```

**Varför reuse av `devroom.events.dlx`?** User Service deklarerade redan DLX i Plan 04. RabbitMQ:s exchange-deklaration är idempotent — så länge egenskaperna matchar är det no-op. En central DLX gör DLQ-monitoring enklare framöver.

Commit: `feat(bot-service): rabbit topology with DLQ`

---

## Task 5: MessagePublishedConsumer

```java
@Component
public class MessagePublishedConsumer {

    private static final Logger log = LoggerFactory.getLogger(MessagePublishedConsumer.class);

    private final BotReplyOrchestrator orchestrator;
    private final JsonMapper mapper;

    public MessagePublishedConsumer(BotReplyOrchestrator orchestrator, JsonMapper mapper) {
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE)
    public void onMessage(byte[] payloadBytes) {
        try {
            JsonNode event = mapper.readTree(payloadBytes);
            orchestrator.handle(event);
        } catch (Exception e) {
            log.error("Failed to handle message.published event", e);
            throw new AmqpRejectAndDontRequeueException("Unhandled exception in consumer", e);
        }
    }
}
```

**Varför `byte[]` istället för `String` eller `JsonNode`?** Spring AMQP:s default-`MessageConverter` är `SimpleMessageConverter` som kan ge `String` eller `Serializable`. Att ta emot `byte[]` är det mest robusta — vi äger deserialiseringen själva och har explicit kontroll över felhantering. Samma mönster som `UserRegisteredConsumer`.

**Varför `AmqpRejectAndDontRequeueException`?** Standard Spring AMQP-retry-policy försöker 3 ggr (config från application.yml). Vid det 3:e misslyckandet behöver vi explicit säga "ge upp" så meddelandet hamnar i DLQ istället för att evigt loopa. `AmqpRejectAndDontRequeueException` är signalen.

Commit: `feat(bot-service): rabbit consumer parses message.published events`

---

## Task 6: SenderLookup (gRPC)

```java
@Component
public class SenderLookup {

    private final UserGrpcServiceGrpc.UserGrpcServiceBlockingStub stub;

    public SenderLookup(GrpcChannelFactory channels) {
        ManagedChannel channel = channels.createChannel("user-service");
        this.stub = UserGrpcServiceGrpc.newBlockingStub(channel);
    }

    public String displayName(String userId) {
        try {
            User user = stub.getUser(GetUserRequest.newBuilder().setUserId(userId).build());
            return user.getDisplayName();
        } catch (StatusRuntimeException e) {
            return "user";
        }
    }
}
```

**Varför `GrpcChannelFactory` och inte `@GrpcClient`?** Spring gRPC 1.0.3 (vår valda starter, ADR-0006) använder constructor injection + factory-mönster. `@GrpcClient`-annotation hör hemma i `net.devh:grpc-spring-boot-starter` som vi övergav. Samma mönster som `MentionResolver` i message-service.

**Varför fallback `"user"` istället för att kasta exception?** Om User Service är nere ska vi inte stoppa hela bot-flödet — vi har inget meningsfullt fallback-svar men en saknad displayName är inte fatalt. Mentor-prompten används med eller utan namnet.

Commit: `feat(bot-service): grpc sender lookup against user-service`

---

## Task 7: MentorClient

```java
@Component
public class MentorClient {

    private final RestClient client;

    public MentorClient(@Qualifier("mentorRestClient") RestClient client) {
        this.client = client;
    }

    public String chat(String personality, String message) {
        ChatResponse response = client.post()
                .uri("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(personality, message, null))
                .retrieve()
                .body(ChatResponse.class);
        return response == null ? "" : response.reply();
    }

    public record ChatRequest(String personality, String message, String sessionId) {}
    public record ChatResponse(String sessionId, String personality, String reply) {}
}
```

`MentorRestClientConfig`:

```java
@Configuration
public class MentorRestClientConfig {
    @Bean
    public RestClient mentorRestClient(@Value("${devroom.bot.nordic-dev-mentor-url}") String url) {
        return RestClient.builder().baseUrl(url).build();
    }
}
```

**Varför skicka `sessionId: null`?** För MVP är varje bot-svar fristående. Senare kan vi mappa `(channelId, mentorUserId)` → en sessionId så mentorn behåller kontext per kanal-mention-kombo. Out-of-scope nu.

**Varför records för request/response?** Boot 4 + Jackson 3 mappar records out-of-the-box. Mindre boilerplate än fullskaliga DTO-klasser. Begränsat scope (bara MentorClient internt).

Commit: `feat(bot-service): mentor REST client (variant A)`

---

## Task 8: MessagePoster (OAuth2)

```java
@Component
public class MessagePoster {

    private static final Logger log = LoggerFactory.getLogger(MessagePoster.class);

    private final RestClient client;

    public MessagePoster(@Qualifier("messageServiceRestClient") RestClient client) {
        this.client = client;
    }

    public void post(String channelId, String asUserId, String body,
                     String parentMessageId, String idempotencyKey) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("channelId", channelId);
        req.put("body", body);
        req.put("asUserId", asUserId);
        if (parentMessageId != null) req.put("parentMessageId", parentMessageId);

        client.post()
                .uri("/messages")
                .header("Idempotency-Key", "bot-reply-" + idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .toBodilessEntity();

        log.debug("Posted bot reply to {} channel={}", asUserId, channelId);
    }
}
```

`MessageServiceClientConfig`:

```java
@Configuration
public class MessageServiceClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clientService) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder
                .builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public RestClient messageServiceRestClient(
            @Value("${devroom.bot.message-service-url}") String url,
            OAuth2AuthorizedClientManager manager) {
        OAuth2ClientHttpRequestInterceptor oauth2 =
                new OAuth2ClientHttpRequestInterceptor(manager);
        oauth2.setClientRegistrationIdResolver(request -> "auth-service");
        return RestClient.builder()
                .baseUrl(url)
                .requestInterceptor(oauth2)
                .build();
    }
}
```

**Varför `AuthorizedClientServiceOAuth2AuthorizedClientManager`?** Spring Security har två manager-implementationer:
- `DefaultOAuth2AuthorizedClientManager` — kräver en `HttpServletRequest` i scope (för web-flöden där användaren är inloggad)
- `AuthorizedClientServiceOAuth2AuthorizedClientManager` — fristående, kräver INGEN request. **Det är vad vi behöver** eftersom Bot Service triggas av RabbitMQ, inte HTTP

**Varför `OAuth2ClientHttpRequestInterceptor` och inte `ExchangeFilterFunction`?** `ExchangeFilterFunction` är WebFlux. `OAuth2ClientHttpRequestInterceptor` finns i `spring-security-oauth2-client` och plugger rakt in i `RestClient` (servlet). Båda gör samma sak: före varje request, kör `manager.authorize(...)`, plocka access-token, sätt `Authorization: Bearer ...`. Token cachas automatiskt i `OAuth2AuthorizedClientService` tills den expirerar.

**Varför sätta `clientRegistrationIdResolver` med lambda?** Default-resolverns letar efter en attribut på request:en. Vår RabbitMQ-trigger-kontext har ingen sådan, så vi binder hårt till `auth-service`-registreringen.

Commit: `feat(bot-service): oauth2 client credentials + message poster`

---

## Task 9: BotReplyOrchestrator

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
        JsonNode mentions = event.get("mentions");
        if (mentions == null || !mentions.isArray() || mentions.isEmpty()) return;

        String channelId = event.get("channel_id").asString();
        String senderId = event.get("sender_id").asString();
        String body = event.get("body").asString();
        String originalMessageId = event.get("message_id").asString();
        JsonNode parent = event.get("parent_message_id");
        String parentForReply = (parent == null || parent.isNull())
                ? originalMessageId
                : parent.asString();

        for (JsonNode m : mentions) {
            if (!m.get("isSystem").asBoolean()) continue;

            String personality = m.get("personality").asString();
            String mentorUserId = m.get("userId").asString();

            String senderName = senderLookup.displayName(senderId);
            log.debug("Bot mention for {} from {} in channel {}", personality, senderName, channelId);

            String reply = mentor.chat(personality, body);

            poster.post(channelId, mentorUserId, reply, parentForReply, originalMessageId);

            log.info("Posted bot reply from {} to channel {}", personality, channelId);
        }
    }
}
```

**Varför `parentForReply = originalMessageId` när parent är null?** En @mention på top-level (utan parent) ska få bot-svaret som tråd-svar PÅ top-level-meddelandet. När parent finns (mention inne i en tråd) ska bot-svaret hamna i samma tråd — alltså samma parent. Det här är "thread coherence"-regeln.

**Varför `idempotencyKey = originalMessageId`?** Deterministisk per logiskt event. Om vi får samma `message.published` två gånger blir Idempotency-Key:n identisk — när Message Service senare implementerar header-läsning fångas duplikatet automatiskt.

**Varför `asString()` och inte `asText()`?** Jackson 3-migration (CLAUDE.md). `asText()` är borttaget.

Commit: `feat(bot-service): orchestrator wires mentions to bot replies`

---

## Task 10: Integration test

Test-strategi (kompletterar `project-testing-strategy`-memory):
- **RabbitMQ**: Testcontainers — vi behöver riktig routing/exchange/queue
- **User Service gRPC**: In-process gRPC (`InProcessServerBuilder`) — registrerar en mock-implementation av `UserGrpcService`
- **Nordic Dev Mentor**: WireMock — mockar `POST /api/v1/chat`
- **Message Service**: WireMock — mockar `POST /messages`, verifierar `Authorization: Bearer ...`-header
- **Auth Service `/oauth2/token`**: WireMock — returnerar en fake access-token JSON-svar
- **Auth Service OIDC discovery**: WireMock — returnerar minimal `/.well-known/openid-configuration` så Spring kan hitta token-endpoint

```java
@SpringBootTest
@Testcontainers
class BotServiceIntegrationTest {

    static WireMockServer wireMock;  // gemensam för auth + mentor + message
    static Server grpcServer;
    static String grpcServerName;

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

    static {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        stubOidcDiscovery();
        stubTokenEndpoint();
        // mentor + message stubs sätts i @Test eller @BeforeEach
        startInProcessGrpc();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host", rabbitmq::getHost);
        r.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        r.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        r.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        r.add("spring.security.oauth2.client.provider.auth-service.issuer-uri",
                () -> wireMock.baseUrl());
        r.add("devroom.bot.nordic-dev-mentor-url", wireMock::baseUrl);
        r.add("devroom.bot.message-service-url", wireMock::baseUrl);
        // gRPC: byt static://-address mot in-process-namnet
        r.add("spring.grpc.client.channels.user-service.address",
                () -> "in-process:" + grpcServerName);
    }
}
```

Tester:
- `mentionWithIsSystemTriggersBotReply` — publicera event på `devroom.events`, vänta på POST mot WireMock, verifiera headers + payload
- `mentionWithIsSystemFalseIsIgnored` — publicera event, verifiera att INGEN POST sker till Message Service
- `noMentionsIsNoOp` — tom mentions-array, verifiera no POST

**Varför WireMock för `/oauth2/token`?** Spring Security hämtar token via discovery + token-endpoint vid första anropet. Att ha en riktig Auth Service uppe i integration-testet skulle kräva Postgres + Auth Service-container — cykliskt build (Bot-test → Auth-image → Auth-källa). WireMock kapar den cykeln. (Samma resonemang som Gateway-test i Plan 06.)

**Varför in-process gRPC istället för Testcontainer av user-service?** Samma anti-cykel-argument + InProcess är ~10x snabbare än en container-startup.

Commit: `test(bot-service): integration test with testcontainers + wiremock + in-process grpc`

---

## Task 11: ADR-0008

Skriv `docs/adr/0008-bot-service-restclient-oauth2.md`:

```markdown
# ADR-0008: Bot Service använder RestClient + OAuth2ClientHttpRequestInterceptor

## Status
Accepted — 2026-05-20

## Context
Bot Service ska anropa Message Service med en OAuth2-token (Client Credentials).
Spring Security 7 erbjuder två sätt att integrera token-hämtning i HTTP-klienten:

1. WebClient + `ServletOAuth2AuthorizedClientExchangeFilterFunction` (reactive)
2. RestClient + `OAuth2ClientHttpRequestInterceptor` (servlet)

## Decision
Vi använder Variant 2.

## Consequences
- Konsekvens med Plan 06 (WebMVC-Gateway, ADR-0007) — en mental-modell i hela repot.
- Ingen transitiv beroende av `spring-webflux` i en pure servlet-tjänst.
- Mindre kod (interceptor är 3 rader, filter-function är 5).
- Vid framtida streaming-behov kan vi byta — `RestClient` och `WebClient` har nästan identiska API:er.

## Alternatives considered
- WebClient: rejected, samma skäl som Gateway WebMVC vs WebFlux (ADR-0007).
- Manuell token-fetching + caching: rejected — `OAuth2AuthorizedClientService` cachar och refreshar redan korrekt.
```

Commit: `docs(adr): 0008 bot-service restclient + oauth2 interceptor`

---

## Task 12: `mvn -B clean verify`

Kör från repo-root. Förväntat: alla 17 tidigare tester + 3 nya bot-service-tester = 20 tester gröna. Build-tid <60s.

Commit (om något justeras): `chore: verify bot-service in full build`

---

## Task 13: Manuell smoke-test (deferred)

Kräver:
1. Postgres + RabbitMQ uppe (`docker compose up`)
2. Auth + User + Message + Gateway + Bot Service uppe (`mvn spring-boot:run` per service)
3. dev-mentor uppe på port 8090 (`SERVER_PORT=8090 mvn spring-boot:run` i `~/IdeaProjects/dev-mentor`)
4. OpenRouter-API-nyckel i `~/IdeaProjects/dev-mentor/.env`

Flöde (genom Gateway efter Plan 06-OAuth2-flödet):
1. Browser: login mot `http://localhost:8080` → Gateway redirectar till Auth → Auth Code-flöde → session-cookie
2. POST `/api/messages` med body `"Hej @code-reviewer kan du förklara DI?"`
3. Vänta ~5s
4. GET `/api/messages?channelId=...` — verifiera två meddelanden: ditt + bot-svar med `senderId` matchande code-reviewer-UUID

Deferred till Plan 09 (cross-service-tester) — denna plan validerar via integration-test istället.

---

## Plan 7 — slut

Vid godkänd verifikation: merga `plan-07-bot-service` till `main`, sedan ny branch `plan-08-frontend`.
