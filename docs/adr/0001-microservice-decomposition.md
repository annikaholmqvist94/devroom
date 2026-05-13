# ADR-0001: Microservice Decomposition

**Status:** Accepted
**Date:** 2026-05-10
**Context:** Devroom — distribuerat chat-system med @-mentionable AI-mentorer

## Sammanhang

Devroom kunde implementeras som en enda Spring Boot-applikation. Projektets uttalade mål är dock att demonstrera distribuerade system-mönster (REST + gRPC + meddelandeköer, separata datalager, oberoende deploy-bara enheter). Vi behövde besluta hur systemet skulle delas upp.

## Beslut

Vi delar upp systemet i fem backend-tjänster + en frontend, baserat på bounded contexts:

1. **Gateway** — entry point (Spring Cloud Gateway). Hanterar OAuth2 Authorization Code + PKCE-flöde mot Auth Service, server-side session med HttpOnly cookie till browser, TokenRelay-filter mot nedströms services.
2. **Auth Service** — Spring Authorization Server. Utfärdar JWTs, exponerar JWKS-endpoint. Ägare av credentials.
3. **User Service** — Profiler, teams, mentor-users. gRPC-server för `GetUser` och `ResolveMentions`. Ägare av användardata.
4. **Message Service** — Kanaler, meddelanden, trådar. REST för writes, publicerar `message-published`-events till RabbitMQ.
5. **Bot Service** — Integrationslager runt befintlig Nordic Dev Mentor (separat kodbas). Konsumerar `message-published`, filtrerar @-mentions, anropar mentor, postar svar via Gateway-relay.
6. **Frontend** — Next.js. Kommunicerar med Gateway via fetch + cookie. Inga tokens i browser-storage.

## Övervägda alternativ

**Alt A: Monolit (en Spring Boot-app).**
Avvisad. Bryter projektets uttalade microservice-arkitektur-mål. Det blir omöjligt att demonstrera bounded contexts, oberoende deploys, eller distribuerade kommunikationsmönster (gRPC, MQ, BFF) i ett monoliterat system.

**Alt B: Tre tjänster (Auth, User, Message slås ihop i en).**
Avvisad. Credentials-data (lösenordshashes, OAuth2-tokens) och profil-data (display-namn, team-tillhörigheter) har olika känslighetsklasser och bör ägas av olika kontexter. Att slå ihop dem skulle förvätska auth-domänens säkerhetsmodell.

**Alt C: Sex tjänster (separat Notification Service).**
Avvisad. För stort scope för tidsbudgeten (~140h). Notifikationer (push-notiser, e-post) är framtida arbete; för demon räcker det att frontend visar meddelanden via vanlig polling/SSE från Gateway.

## Konsekvenser

**Positiva:**

- Tydliga bounded contexts: Auth ≠ User ≠ Message. Varje service äger sitt datalager.
- Bot Service utvecklas oberoende av Message Service. När Nordic Dev Mentor uppgraderas behöver bara Bot Service' integrationskod ändras.
- Demonstrerar tre kommunikationsmönster i ett system: REST (Gateway → services för writes), gRPC (intern read-trafik via User Service), MQ (asynkrona events via RabbitMQ).
- Service-gränserna gör auth-modellen explicit: Bot Service har egen OAuth2-identitet (Client Credentials grant, scope `bot:write`) — det blir tydligt att botens skrivrättigheter är åtskilda från en mänsklig användares.

**Negativa:**

- Distribuerad signup-transaktion (Auth Service skapar credentials, User Service skapar profil) löses med outbox-pattern (se ADR-0002). Mer kod än en lokal transaktion.
- Inga foreign keys över databas-gränser (se ADR-0005). Konsistens upprätthålls via event-konsumption och idempotenta consumers.
- Fler containrar att starta lokalt. Mitigeras av `docker-compose.yml` med `profiles: [full]` — `docker compose up` startar bara infra, `docker compose --profile full up` startar allt.
- Operations-komplexitet i K8s (Plan 10): 5 Deployments, 3 StatefulSets för databaser, separata Services och ConfigMaps per service.

## Referenser

- Spec: `docs/superpowers/specs/2026-05-10-devroom-design.md` sektion 2.1 (system overview)
- ADR-0002: Outbox pattern för `user.registered`-eventet
- ADR-0003: Spring OAuth2-stack
- ADR-0004: gRPC för intern read-trafik, REST för writes
- ADR-0005: Inga foreign keys över databas-gränser
