# Devroom — projektkontext för Claude

> Den här filen laddas automatiskt av Claude Code vid varje session i detta repo. Håll den uppdaterad löpande.

## Projektets natur

- **Distribuerat chat-system** med @-mentionable AI-mentorer inom mikroservicearkitektur
- **Tidsbudget:** ~140h development time, ~25h marginal kvar efter OAuth2-pivoten
- **Kvalitetsmål:** ADR:er för viktiga arkitekturbeslut, integrationstester med Testcontainers, "docker compose up" som lokal snabbstart, README med arkitekturdiagram och demo-flow
- **Deployment-mål:** Kubernetes via Minikube (utöver lokal docker-compose)
- **Återanvänder Nordic Dev Mentor** som dependency för Bot Service. Lokalt på `~/IdeaProjects/dev-mentor`.

## Arkitektur (efter pivot 2026-05-12)

5 backend-services + 1 frontend:

- **Auth Service** — Spring Authorization Server. Utfärdar JWTs, exponerar JWKS (`/.well-known/jwks.json`). RSA-keypair genereras in-memory vid uppstart (ingen PEM-fil). Custom `/signup`-endpoint + outbox för `user.registered`-event.
- **User Service** — Spring Resource Server. JWT-validering via `spring-boot-starter-oauth2-resource-server` + JWKS. gRPC-server för `GetUser` + `ResolveMentions`. Seedar 4 mentor-users.
- **Message Service** — Spring Resource Server. POST/GET messages, gRPC-klient mot User, RabbitMQ-publisher för `message-published`.
- **Gateway** (BFF-roll) — **Spring Cloud Gateway** (reactive). OAuth2 Authorization Code + PKCE-flöde mot Auth Service, server-side session, HttpOnly cookie till browser, TokenRelay-filter mot nedströms. Ersätter klassisk Spring Web BFF.
- **Bot Service** — Spring OAuth2 Client med **Client Credentials grant** (scope `bot:write`). Konsumerar `message-published` från RabbitMQ, anropar Nordic Dev Mentor, postar svar via Gateway-relay till Message Service.
- **Frontend** — Next.js 16 / React 19 / TS / Tailwind 4. Inget auth-bibliotek — `fetch(..., { credentials: 'include' })` mot Gateway, cookie auto-medskickas. Inga tokens i localStorage någonsin.

## Aktuell branch och status

```bash
git branch    # main + plan-05-message-service (klar, redo för PR)
git log --oneline | head -5
```

**Plan 01 (2026-05-13, mergad PR #2):** parent POM, `proto/user.proto`, `docker-compose.yml` (single file med profiles-strategi), CI workflow, ADR-0001 mikroservice-decomposition. Task 4-6 (auth-starter) ersattes av Spring Authorization Server-pivot 2026-05-12, Task 11 täcks av ADR-0003.

**Plan 02 (2026-05-14, mergad PR #3):** Auth Service implementerad och verifierad end-to-end. Spring Boot 4.0.6 + Spring Authorization Server 7.0.5 med klienter via properties + InMemoryRegisteredClientRepository och JSON-baserat signup-API (ingen Thymeleaf). 5/5 Testcontainers-tester gröna mot Postgres 16-alpine. Sju Boot 4-paket-rename dokumenterade i commits + plan-revisionsbanners.

**Plan 03 (2026-05-14, mergad PR #4):** User Service implementerad med gRPC-server, JPA-persistens och stub-MQ-consumer. 2/2 Testcontainers-tester gröna. Spring gRPC-pivot från `net.devh` till `org.springframework.grpc:spring-grpc-server-spring-boot-starter` 1.0.3 (ADR-0006). 2 Flyway-migrationer (V1 teams + users, V2 seed med 4 mentor-personligheter). `UserGrpcServiceImpl` auto-discovers via `BindableService`. `UserRegisteredConsumer` gated på `@Profile("rabbit")` (aktiveras i Plan 04).

**Plan 04 (2026-05-16, branch `plan-04-rabbitmq-wiring`, 8 commits):** RabbitMQ end-to-end-flöde aktivt. Auth Service:s `OutboxPublisher` skickar `user.registered` på exchange `devroom.events` med persistent delivery; User Service consumer (utan profile-gating) plockar från durable queue `user-service.user-registered` med DLQ till `devroom.events.dlx`. Listener-retry: 3 försök med exponentiell backoff (1s→2s→4s) innan dead-letter.

- 9/9 tester gröna efter plan-slut: `OutboxToRabbitIntegrationTest` (Postgres + RabbitMQ Testcontainers, observer-queue), `UserRegisteredHandlerIdempotencyTest`.
- Jackson 3-migration cross-service: Boot 4 auto-config exponerar `tools.jackson.databind.json.JsonMapper`, inte `com.fasterxml.jackson.databind.ObjectMapper`. API-byten: `asText()` → `asString()`, `JsonProcessingException` → `JacksonException` (numera unchecked).
- RabbitMQ 4-gotcha: feature-flaggan `transient_nonexcl_queues` disablad by default. Använd `QueueBuilder.durable(...)` för test-observer-queues.
- Test-startup-skydd: `application-test.yml` (user-service) sätter `spring.rabbitmq.listener.simple.auto-startup=false` så `UserRegisteredConsumer` inte connectar mot en frånvarande broker under existing integration-tester.
- Manuell smoke-test verifierad: HTTP `/signup` → `users` i `userdb` med matchande user_id, RabbitMQ management API visade `publish_in=1` på `devroom.events` + `delivered=1` på huvudkön.

**Plan 05 (2026-05-18, branch `plan-05-message-service`, 14 commits):** Message Service implementerad med POST/GET endpoints, gRPC-klient mot User Service och RabbitMQ-publisher för `message.published`-events. 6/6 tester gröna (3 Testcontainers-integration + 3 MentionParser-unit). `mvn -B clean verify` på hela repot grön på 37s.

- Spring Resource Server-säkerhet: validerar JWT via JWKS från Auth Service, scope-baserad authz (`profile` eller `bot:write`) på `POST /messages`. Första HTTP-yta i repot som validerar JWT.
- Mention-flöde: regex `@([a-z0-9-]+)` → gRPC `ResolveMentions` mot User Service → JSONB-array i `messages.mentions`. Inline-lagring eftersom 95% av reads vill ha mentions med (ADR-0005 hindrar FK ändå).
- JSONB-mappning: Hibernate 7:s inbyggda `@JdbcTypeCode(SqlTypes.JSON)` på `List<MentionInfo>`. Ingen `hibernate-types-60` eller `hypersistence-utils` — Jackson 3 på classpath räcker.
- gRPC-klient: Spring gRPC 1.0.3 bean-baserad konfiguration via `GrpcChannelFactory.createChannel("user-service")` (inte `@GrpcClient`-annotation från `net.devh`). `MentionResolver` och `ServiceTokenSenderResolver` wrappar protobuf-typer så domän-logiken inte ser dem.
- as_user_id-säkerhetscheck: när scope `bot:write` används verifieras `as_user_id` via gRPC `GetUser` att peka på system-user. Hindrar confused-deputy från Bot Service.
- Atomicitet-kompromiss: DB-write + Rabbit-publish i samma `@Transactional` (ingen outbox). ADR-0008 placeholder om vi senare behöver exactly-once.
- Boot 4-paket-rename: `@AutoConfigureMockMvc` flyttat ur `spring-boot-test-autoconfigure` till ny artifact `spring-boot-webmvc-test`, nytt paket `org.springframework.boot.webmvc.test.autoconfigure`. Lade också till `-parameters` compiler-flag i parent POM (krävs för `@RequestParam UUID channelId`-mappning).
- ADR-0004 (gRPC vs REST) skriven: fyller den planerade luckan som ADR-0001, 0005 och 0006 alla framåt-refererade till.

**Compose-strategi:** En `docker-compose.yml` med infra (auth-db, user-db, message-db, rabbitmq). Services i Plan 02-07 läggs till med `profiles: [full]` så att `docker compose up` bara startar infra som default.

**Nästa steg:** Merga `plan-05-message-service` till `main`, sedan ny branch `plan-06-gateway` (Spring Cloud Gateway med OAuth2 Authorization Code + PKCE-flöde, server-side session, TokenRelay-filter mot nedströms services).

## Nyckel-dokument (läs vid sessionsstart)

| Fil | Innehåll |
|---|---|
| `docs/superpowers/specs/2026-05-10-devroom-design.md` | Sanningskälla för arkitektur, alla 15 sektioner |
| `docs/adr/0003-oauth2-stack.md` | Varför Spring OAuth2 över Kong, Spring Web BFF, PEM-filer — 8 alternativ vägda |
| `docs/adr/0006-grpc-starter-spring-grpc.md` | Varför Spring gRPC 1.0.3 ersätter `net.devh` |
| `docs/superpowers/plans/2026-05-10-plan-04-rabbitmq-wiring.md` | Nästa implementations-plan (RabbitMQ end-to-end) |
| `docs/superpowers/plans/2026-05-10-plan-06-gateway.md` | Spring Cloud Gateway med TokenRelay |

## Workflow-regler

- **Feature-branch per plan:** `plan-01-bootstrap`, `plan-02-auth-service`, etc. Merge till `main` vid plan-slut.
- **Verifiera dependency-versioner** mot Maven Central / context7 INNAN commit. Spring Boot 4.0.6 är lägsta accepterad version. Spring Cloud BOM 2025.0.x.
- **Narration:** förklara varje steg pedagogiskt INNAN utförande. Ingen "snabb tyst commit" — användaren bygger för att lära.
- **Java 21**, Maven multi-module, Postgres 16, RabbitMQ 4, Minikube med Docker driver.
- **Verifiera lokalt med `mvn -B clean verify`** innan commit av stora ändringar.

## Saker som INTE ska göras utan explicit godkännande

- Skriva kod utan att först förklara
- Force-push, `git reset --hard`, eller andra destruktiva git-operationer
- Pusha till `main` direkt (allt går via feature-branch + merge)
- Amenda redan-pushade commits
- Lägga till nya dependencies utan version-verifiering
- Pivot:a arkitektur (om en pivot är på gång, diskutera först, dokumentera i ny ADR)

## Stora arkitektoniska val (sammanfattning av ADRs)

- **ADR-0001** Microservice decomposition: 5 services. Bounded contexts.
- **ADR-0002** Outbox pattern för `user.registered`-eventet. At-least-once + idempotent consumer.
- **ADR-0003** Spring OAuth2-stack (se filen för full motivering, 8 alternativ).
- **ADR-0004** gRPC för intern read-trafik, REST för writes.
- **ADR-0005** Inga foreign keys över databas-gränser.
- **ADR-0006** Spring gRPC 1.0.3 (officiell Spring-portfolio) istället för `net.devh:grpc-spring-boot-starter` (fast på Boot 3.2.4).
