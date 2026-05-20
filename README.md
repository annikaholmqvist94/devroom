# Devroom

Distributed chat with @-mentionable AI mentors built on a microservice architecture.

See [design spec](docs/superpowers/specs/2026-05-10-devroom-design.md).

## Status

Under utveckling — Plan 08 av 10 klar. Full README skrivs i Plan 10.

| # | Komponent | Status |
|---|---|---|
| 1 | Auth Service (Spring Authorization Server 7.0.5) | Plan 02 klar 2026-05-14 |
| 2 | User Service (Spring gRPC 1.0.3 + JPA) | Plan 03 klar 2026-05-14 |
| 3 | RabbitMQ-wiring (`user.registered`-flödet) | Plan 04 klar 2026-05-16 |
| 4 | Message Service (POST/GET, gRPC-klient, `message.published`) | Plan 05 klar 2026-05-18 |
| 5 | Gateway (Spring Cloud Gateway 5.0.1 WebMVC, OAuth2 Authorization Code + TokenRelay) | Plan 06 klar 2026-05-20 |
| 6 | Bot Service (RabbitMQ-consumer + OAuth2 Client Credentials + Nordic Dev Mentor wrapper) | Plan 07 klar 2026-05-20 |
| 7 | Frontend (Next.js 16, Tailwind 4, cookie-baserad auth) | Plan 08 klar 2026-05-20 |

## Quick start

```bash
docker compose up -d                              # Postgres + RabbitMQ (services tillkommer via 'profiles: [full]')
mvn -B verify                                     # build + test alla moduler
mvn -pl services/auth-service spring-boot:run     # kör Auth Service på :8081 (HTTP)
mvn -pl services/user-service spring-boot:run     # kör User Service på :8082 (HTTP) + :9082 (gRPC)
mvn -pl services/message-service spring-boot:run  # kör Message Service på :8083 (HTTP)
GATEWAY_CLIENT_SECRET=dev-gateway-secret-change-me \
  mvn -pl services/gateway spring-boot:run        # kör Gateway/BFF på :8080 (browser-entry för frontend)
BOT_CLIENT_SECRET=dev-bot-secret-change-me \
  mvn -pl services/bot-service spring-boot:run    # kör Bot Service på :8084 (kräver Auth Service + dev-mentor uppe)
cd frontend && npm install && npm run dev         # kör Next.js på :3000 (kräver Gateway uppe på :8080)
```

För bot-service krävs Nordic Dev Mentor lokalt (`~/IdeaProjects/dev-mentor`) startad med `SERVER_PORT=8090 mvn spring-boot:run` så den inte kolliderar med Gateway.

## Arkitekturbeslut

Se [docs/adr/](docs/adr/) för fullständig lista. I korthet:

- **ADR-0001** — fem backend-services + en frontend, bounded contexts
- **ADR-0002** — transactional outbox för `user.registered` (atomicitet över DB + RabbitMQ)
- **ADR-0003** — Spring OAuth2-stack (Authorization Server + Resource Server) framför Kong eller egen JWT
- **ADR-0004** — gRPC för intern read-trafik, REST för writes
- **ADR-0005** — inga foreign keys över databas-gränser, eventual consistency via events
- **ADR-0006** — Spring gRPC 1.0.3 (officiell Spring-portfolio) som gRPC-starter, inte den community-drivna `net.devh`
- **ADR-0007** — Spring Cloud Gateway WebMVC-variant (inte WebFlux) för konsistens med resten av services — servlet-stack, en `SecurityFilterChain`-mental-modell i hela repot
- **ADR-0008** — Bot Service använder RestClient + `OAuth2ClientHttpRequestInterceptor` (inte WebClient + filter-function) för Client Credentials — samma servlet-konsolidering som ADR-0007
