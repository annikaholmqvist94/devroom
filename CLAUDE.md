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
git branch    # main + plan-02-auth-service (klar, redo för PR)
git log --oneline | head -5
```

**Plan 01 (2026-05-13, mergad PR #2):** parent POM, `proto/user.proto`, `docker-compose.yml` (single file med profiles-strategi), CI workflow, ADR-0001 mikroservice-decomposition. Task 4-6 (auth-starter) ersattes av Spring Authorization Server-pivot 2026-05-12, Task 11 täcks av ADR-0003.

**Plan 02 (2026-05-14, branch `plan-02-auth-service`, 23 commits):** Auth Service implementerad och verifierad end-to-end. Spring Boot 4.0.6 + Spring Authorization Server 7.0.5 med Variant F-config (klienter via `application.yml`-properties + InMemoryRegisteredClientRepository) och Variant C-signup (JSON-API, ingen Thymeleaf). 5/5 Testcontainers-tester gröna mot Postgres 16-alpine.

- 2 Flyway-migrationer (V1 users + authorities, V4 outbox_events)
- 5 config-klasser (KeyConfig RSA-keypair, SecurityBeansConfig PasswordEncoder + ObjectMapper, AuthorizationServerConfig, DefaultSecurityConfig, TokenCustomizerConfig team_id-claim)
- DevroomUser-entitet + UserDetailsService + SignupService (transactional outbox-pattern)
- OutboxPublisher `@Scheduled` stub — loggar tills RabbitMQ kopplas in i Plan 04
- ADR-0002 (transactional outbox), ADR-0005 (inga cross-DB FK)

Sju Boot 4-paket-rename fångades under arbetet och är dokumenterade i commits + plan-revisionsbanners: `OAuth2AuthorizationServerConfigurer` flyttad till `spring-security-config`, `OAuth2TokenType` flyttad till SAS-paketet, Flyway auto-config kräver `spring-boot-starter-flyway`, `TestRestTemplate` i `spring-boot-resttestclient` + `spring-boot-restclient`, `@ServiceConnection` i separat `spring-boot-testcontainers`, `@AutoConfigureTestRestTemplate` opt-in, `ObjectMapper`-bean inte auto-konfigurerad vid webmvc-only setup.

**Compose-strategi:** En `docker-compose.yml` med infra (auth-db, user-db, message-db, rabbitmq). Services i Plan 02-07 läggs till med `profiles: [full]` så att `docker compose up` bara startar infra som default.

**Nästa steg:** Merga `plan-02-auth-service` till `main`, sedan ny branch `plan-03-user-service` (User Service med gRPC `GetUser` + `ResolveMentions`, RabbitMQ-consumer för `user.registered` från outbox).

## Nyckel-dokument (läs vid sessionsstart)

| Fil | Innehåll |
|---|---|
| `docs/superpowers/specs/2026-05-10-devroom-design.md` | Sanningskälla för arkitektur, alla 15 sektioner |
| `docs/adr/0003-oauth2-stack.md` | Varför Spring OAuth2 över Kong, Spring Web BFF, PEM-filer — 8 alternativ vägda |
| `docs/superpowers/plans/2026-05-10-plan-02-auth-service.md` | Nästa implementations-plan (Spring Authorization Server) |
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
