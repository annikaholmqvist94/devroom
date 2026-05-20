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
git branch    # main + plan-08-frontend (klar, redo för PR)
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

**Plan 06 (2026-05-20, branch `plan-06-gateway`, 10 commits):** Gateway implementerad som Spring Cloud Gateway 5.0.1 med OAuth2 Authorization Code-flöde mot Auth Service och TokenRelay-filter mot nedströms-services. 4/4 tester gröna (~2.2s). `mvn -B clean verify` på hela repot grön på 44s (17 tester totalt).

- **WebMVC-pivot (ADR-0007):** plan-texten specade webflux-varianten, men sedan Gateway 4.1 finns en webmvc-variant med feature-paritet (inkl. TokenRelay). Pivot till `spring-cloud-starter-gateway-server-webmvc` för konsistens med Auth/User/Message — en mental-modell (`SecurityFilterChain` + `HttpSecurity`) i hela repot, inga reactive paradigm-skiften.
- Spring Cloud BOM 2025.1.1 (Aurora) — första Spring Cloud-tåget byggt på Spring Framework 7 + Spring Boot 4. Importerat EFTER spring-boot-dependencies så vår Boot 4.0.6 övertrumfar BOM:ens default 4.0.2.
- YAML-driven routing: 3 routes (`/api/users/**`, `/api/messages/**`, `/signup/**`) med `StripPrefix=1` + `TokenRelay`-filter. Signup är publik (TokenRelay skippas).
- SecurityFilterChain: `oauth2Login()` + `oauth2Client()` aktiverar Authorization Code-flödet och `OAuth2AuthorizedClientManager`-beanen som TokenRelay behöver. CSRF disabled (BFF-mönster). CORS via `CorsConfigurationSource`-bean (webmvc-varianten har ingen dokumenterad YAML-CORS-key).
- `/api/me` RouterFunction returnerar 200+JSON eller 401 (NOT redirect) så frontend kan använda 401 som "ej inloggad"-signal istället för att fastna i Authorization Code-flödet.
- Testverktyg: WireMock 3.13.2 (shaded) mockar Auth Service:s OIDC discovery i integration-test. Spring Security kontaktar `issuer-uri` vid bean-skapande, så WireMock måste startas i `static {}`-block FÖRE `@DynamicPropertySource`. TestRestTemplate (Boot 4-paket: `org.springframework.boot.resttestclient.TestRestTemplate`) + Java HttpClient med `Redirect.NEVER` för 302-testet (TestRestTemplate följer redirects by default).
- Boot 4-modularisering: `TestRestTemplate` flyttat ur `-starter-test` till `spring-boot-resttestclient`; `RestTemplateBuilder` till `spring-boot-restclient`. Båda explicit deklarerade i gateway/pom.xml.
- ADR-0007 skriven: Gateway WebMVC vs WebFlux med trade-offs och future-paths.
- Task 8 (manuell smoke-test) deferred — kräver Auth Service + Postgres uppe + browser för Authorization Code-flödet. Stegen finns i plan 06.

**Plan 07 (2026-05-20, branch `plan-07-bot-service`, ~10 commits):** Bot Service implementerad som RabbitMQ-consumer som wrappar Nordic Dev Mentor (svart-låda via REST). 3/3 integration-tester gröna (~9s).

- **Plan-revision innan exekvering:** plan-filen var skriven före OAuth2-pivoten (2026-05-12) och dev-mentor-inspektionen — Task 4-5 (pre-issued service-JWT) ersattes av Client Credentials grant, Task 9 Variant A (svart låda) vald över Variant B (direkt-import).
- **OAuth2 Client Credentials-flödet:** `MessageServiceClientConfig` exponerar `AuthorizedClientServiceOAuth2AuthorizedClientManager` (vi MÅSTE definiera explicit — Boot:s default `DefaultOAuth2AuthorizedClientManager` kräver `HttpServletRequest` i scope som vår RabbitMQ-tråd saknar). `MessagePoster` sätter `.attributes(clientRegistrationId("auth-service"))` + `.attributes(principal("bot-service"))` per call — Spring Security 6.5+ dokumenterad pattern för Client Credentials.
- **RestClient över WebClient (ADR-0008):** servlet-konsolidering, samma resonemang som ADR-0007. `OAuth2ClientHttpRequestInterceptor` + `RequestAttributePrincipalResolver` istället för `ServletOAuth2AuthorizedClientExchangeFilterFunction`.
- **HTTP/1.1-tvång (HttpClientConfig):** RestClient default kör HTTP/2 via JdkClientHttpRequestFactory + java.net.http.HttpClient → krockar med WireMock i test (`RST_STREAM: Stream cancelled`). Tvinga `HttpClient.Version.HTTP_1_1` på en gemensam factory-bean. Säkert i prod — Tomcat-baserade services pratar HTTP/1.1 default.
- **Bot-client i auth-service:** `bot-service` med scope `bot:write` var redan registrerad i `auth-service/application.yml` sedan Plan 02 (default-secret `BOT_CLIENT_SECRET=dev-bot-secret-change-me`). Bot Service:s `application.yml` matchar bara värdena.
- **Test-arkitektur:** Testcontainers (RabbitMQ) + WireMock som agerar 3 tjänster på samma server (auth OIDC discovery + `/oauth2/token`, dev-mentor `/api/v1/chat`, message-service `/messages`) + in-process gRPC via `@TestConfiguration` `@Primary`-bean. WireMock måste startas i `static{}`-block FÖRE Spring boot:ar (samma mönster som Plan 06).
- **Konsekvens-vinster i kod:** följer message-service-mönstret för gRPC-stub-bean (`GrpcClientConfig` separerar wiring från användning), user-service-mönstret för RabbitListener (`String json`-parameter), och samma RabbitTopologyConfig-konstant-stil. Bot Service är 19 produktionskällfiler + 1 integration-test.
- **Idempotency-Key skickas alltid** även om Message Service inte läser den än (`bot-reply-<originalMessageId>`) — framtidssäkring när dedup-filter byggs i Message Service.
- **Nordic Dev Mentor som svart låda:** dev-mentor är en standalone Spring Boot-app (inte lib), måste startas med `SERVER_PORT=8090` lokalt för att inte kollidera med Gateway. Ingen auth mellan Bot Service och dev-mentor — dev-mentor saknar auth-mekanism (känd limitation deras README).
- Tasks 12-13 (manuell smoke-test + cross-service-tester) deferred till Plan 09.

**Compose-strategi:** En `docker-compose.yml` med infra (auth-db, user-db, message-db, rabbitmq). Services i Plan 02-07 läggs till med `profiles: [full]` så att `docker compose up` bara startar infra som default.

**Plan 08 (2026-05-20, branch `plan-08-frontend`, 8 commits):** Next.js 16-frontend implementerad och feature-komplett. Cookie-baserad auth via Gateway, ingen `lib/auth.ts`, inga tokens i browser. `npm run build` + `npm run lint` gröna. `mvn -B clean verify` på hela repot grön (17 backend-tester, ~57s).

- **Stack:** Next.js 16.2.6, React 19.2.4, Tailwind 4 (CSS-first `@theme inline { ... }`), TypeScript 5, ESLint 9. Speglar Nordic Dev Mentor — no-src-dir, flat `app/`, samma typografi (Inter / Crimson Pro / JetBrains Mono) och färgpalett (cream-toner, accent-orange).
- **Plan-kropp vs pivot-banner:** plan-kroppen specade JWT-i-localStorage + Authorization-headers, men pivot-bannern (2026-05-12) ändrade allt till cookie-baserad auth. Följde bannern strikt: ingen `lib/auth.ts`, ingen Authorization-header från frontend, `credentials: 'include'` på alla `fetch`.
- **Auth-flöde:** `/login` är bara en `<a href="${GATEWAY}/oauth2/authorization/auth-service">` som triggar Spring Security:s Authorization Code-flöde. `/signup` är ett React-formulär som POST:ar JSON till `${GATEWAY}/signup/` (Gateway-route utan TokenRelay), redirectar sedan in i OAuth2-login. Logout via POST mot `${GATEWAY}/logout` (CSRF disabled i Gateway-BFF).
- **`/api/me` som inloggad-probe:** Gateway:s RouterFunction returnerar 200+JSON eller 401. Frontend använder den i `ChannelsLayout` och root-`page.tsx` för att avgöra om användaren är inloggad. `api.ts` har special-case: 401 från `/api/me` redirectar INTE, så login-sidan inte infinite-loopar när den check:ar sig själv.
- **`usePolling`-hook:** 3-sekunders polling mot `/api/messages?channelId=X` med visibility-pause (pausar när fliken är dold), exponential backoff vid errors (1s → 2s → 4s → … cap 30s), `refetch`-callback så `PostMessageForm` kan trigga omedelbar fetch efter post.
- **Mention-rendering:** `MessageItem` splittar body på samma regex som server (`@[a-z0-9-]+`) och slår upp varje match i `message.mentions`. System-mentions får accent-orange MentionBadge, human-mentions neutral, oresolverade får muted plain text.
- **3 hårdkodade demo-kanaler** (`333…01/02/03`) i `ChannelList`. Funkar eftersom Message-service inte FK-validerar channel-ids (ADR-0005).
- **Smoke-test deferred:** manuell browser-test kräver hela stacken uppe (5 services + RabbitMQ + 3 Postgres + Nordic Dev Mentor). Görs separat när du har tid, kombinerat med Plan 09 cross-service-tester.

**Compose-strategi (oförändrad):** En `docker-compose.yml` med infra (auth-db, user-db, message-db, rabbitmq). Services i Plan 02-07 läggs till med `profiles: [full]`.

**Nästa steg:** Merga `plan-08-frontend` till `main`, sedan Plan 09 (cross-service integration tests) eller manuell smoke-test av frontend mot full stacken (signup → login → mention → bot-svar).

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
- **Verifiera dependency-versioner** mot Maven Central / context7 INNAN commit. Spring Boot 4.0.6 är lägsta accepterad version. Spring Cloud BOM 2025.1.1 (Aurora — Boot 4-kompatibel).
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
- **ADR-0007** Spring Cloud Gateway WebMVC-variant istället för WebFlux — konsistens med övriga services (servlet-stack, `SecurityFilterChain`).
- **ADR-0008** Bot Service använder RestClient + `OAuth2ClientHttpRequestInterceptor` (inte WebClient + filter-function) för Client Credentials — samma servlet-konsolidering som ADR-0007.
