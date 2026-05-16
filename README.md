# Devroom

Distributed chat with @-mentionable AI mentors built on a microservice architecture.

See [design spec](docs/superpowers/specs/2026-05-10-devroom-design.md).

## Status

Under utveckling — Plan 04 av 10 klar. Full README skrivs i Plan 10.

| # | Komponent | Status |
|---|---|---|
| 1 | Auth Service (Spring Authorization Server 7.0.5) | Plan 02 klar 2026-05-14 |
| 2 | User Service (Spring gRPC 1.0.3 + JPA) | Plan 03 klar 2026-05-14 |
| 3 | RabbitMQ-wiring (`user.registered`-flödet) | Plan 04 klar 2026-05-16 |
| 4 | Message Service | Plan 05 kommande |
| 5 | Gateway (Spring Cloud Gateway) | Plan 06 kommande |
| 6 | Bot Service (Nordic Dev Mentor wrapper) | Plan 07 kommande |
| 7 | Frontend (Next.js) | Plan 08 kommande |

## Quick start

```bash
docker compose up -d                           # Postgres + RabbitMQ (services tillkommer via 'profiles: [full]')
mvn -B verify                                  # build + test alla moduler
mvn -pl services/auth-service spring-boot:run  # kör Auth Service på :8081 (HTTP)
mvn -pl services/user-service spring-boot:run  # kör User Service på :8082 (HTTP) + :9082 (gRPC)
```

## Arkitekturbeslut

Se [docs/adr/](docs/adr/) för fullständig lista. I korthet:

- **ADR-0001** — fem backend-services + en frontend, bounded contexts
- **ADR-0002** — transactional outbox för `user.registered` (atomicitet över DB + RabbitMQ)
- **ADR-0003** — Spring OAuth2-stack (Authorization Server + Resource Server) framför Kong eller egen JWT
- **ADR-0004** — gRPC för intern read-trafik, REST för writes
- **ADR-0005** — inga foreign keys över databas-gränser, eventual consistency via events
- **ADR-0006** — Spring gRPC 1.0.3 (officiell Spring-portfolio) som gRPC-starter, inte den community-drivna `net.devh`
