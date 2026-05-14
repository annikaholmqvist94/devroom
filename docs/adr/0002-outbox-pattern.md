# ADR-0002: Transactional Outbox Pattern för domain events

**Status:** Accepted
**Date:** 2026-05-14
**Context:** Auth Service skapar user + ska publicera `user.registered` så att User Service kan skapa motsvarande profil-rad

## Sammanhang

Signup-flödet i Auth Service består av två writes mot två separata system:

1. INSERT i `users`-tabellen (Auth-DB) — Auth Service äger credentials
2. Publicering av `user.registered`-event till RabbitMQ — så User Service kan reagera och skapa profil

Två separata systems writes kan inte göras atomära i naiv ordning:

- **DB först, sedan Rabbit:** om Rabbit-publish kraschar efter DB-commit hamnar user i DB utan att User Service får eventet. User kan logga in i Auth men har ingen profil — login fungerar, allt nedströms kraschar med "user not found".
- **Rabbit först, sedan DB:** om DB-commit fails efter Rabbit-publish konsumeras eventet av User Service som skapar profil för en user som inte finns i Auth-DB. Ghost-användare.
- Båda problemen är fail-modes som inträffar sällan men inte aldrig — nätverksfel, kraschade processer mitt i flödet, OOM under commit.

Spring `@Transactional` täcker bara JDBC-transaktionen, inte Rabbit. Vi kan inte heller använda XA (two-phase commit) i praktiken — se alternativ B.

## Beslut

Transactional outbox-pattern. SignupService skriver event:et till `outbox_events`-tabellen **i samma DB-transaktion** som user-INSERTen. En separat `@Scheduled OutboxPublisher` pollar tabellen och publicerar till RabbitMQ asynkront, markerar `processed_at = NOW()` efter lyckad publish.

```
SignupService (@Transactional)
  ├─ INSERT INTO users (...)         ┐
  └─ INSERT INTO outbox_events (...) ┘ samma DB-transaktion → atomär

OutboxPublisher (@Scheduled, separat tråd, var 5:e sekund)
  1. SELECT FROM outbox_events WHERE processed_at IS NULL
  2. För varje rad: rabbitTemplate.convertAndSend(payload)
  3. UPDATE outbox_events SET processed_at = NOW()
```

**Event-format:**

```json
{
  "event_id": "<uuid>",
  "event_type": "user.registered",
  "occurred_at": "<ISO-8601>",
  "user_id": "<uuid>",
  "email": "<email>",
  "team_id": "<uuid>"
}
```

`event_id` används som idempotency-nyckel av consumers.

## Övervägda alternativ

**Alt A: Direct Rabbit-publish efter DB-commit (best-effort).**
Avvisad. Beskriven fail-mode ovan: om Rabbit-publish kraschar efter DB-commit hamnar user i DB utan event. Best-effort räcker kanske för analytics-events där förlust är acceptabel — men `user.registered` driver schemat i ett annat service och får inte tappas.

**Alt B: Two-phase commit (XA) över Postgres + RabbitMQ.**
Avvisad. Postgres stödjer XA via JTA men RabbitMQ:s XA-stöd är ofullständigt (txSelect/txCommit är inte riktig XA). Spring Boot kräver dessutom en JTA-transaction-manager (Atomikos/Narayana) vilket inflar runtime-komplexiteten. Latensen är också betydligt högre. För ~140h-budgeten och en demoapp inte motiverat.

**Alt C: Change Data Capture (CDC) via Debezium.**
Avvisad. Debezium läser Postgres WAL och publicerar tabellförändringar till Kafka. Korrekt och vanlig prod-pattern för stora system, men kräver Kafka + Debezium-connector + ZooKeeper/Strimzi och en helt annan operations-modell. Overkill för Devroom — vi har redan RabbitMQ för andra events (`message-published`).

**Alt D: Publicera från User Service istället (User skapas först, Auth som consumer).**
Avvisad. Auth Service är ägare av credentials per ADR-0001. Att flytta event-publicering till User Service betyder att User skulle initiera signup-flödet, vilket bryter bounded context-modellen. Auth-domänens "skapa user med password"-action skulle bli en passiv reaktion på ett event från en annan kontext.

## Konsekvenser

**Positiva:**

- Atomicitet är garanterad inom Postgres-transaktionen — user + event lyckas eller fails tillsammans.
- Robust mot Rabbit-kraschar: publishern återupptar pollning vid restart, inga events går förlorade.
- Tillåter att Rabbit är nere vid signup — events ackumuleras i outbox och publiceras när Rabbit kommer tillbaka.
- Standard pattern (Microservices.io, Chris Richardson) — välkänd för läsare av koden.

**Negativa:**

- **At-least-once leverans, inte exactly-once.** Om publishern kraschar efter `rabbitTemplate.convertAndSend()` men före `UPDATE processed_at`, publiceras eventet igen vid nästa polling. Consumers MÅSTE vara idempotenta — i Devroom uppnås detta via `event_id` som idempotency-nyckel i User Service.
- Latens: events publiceras med upp till `fixedDelay`-intervallet (5s i demon) efter DB-commit. För `user.registered` är 5s acceptabelt — användaren ser inte fördröjningen eftersom signup-responsen returnerar omedelbart.
- Extra tabell (`outbox_events`), partial index, scheduled job, log-volym.
- Single-instance design — vid k8s-replicas måste vi byta till `SELECT FOR UPDATE SKIP LOCKED` (Postgres-syntax) eller en distribuerad lock. Notering i `OutboxPublisher`.
- Outbox-tabellen växer obegränsat om vi inte rensar bort processed rader. För demon är detta acceptabelt; för prod skulle vi schemalägga en `DELETE WHERE processed_at < NOW() - INTERVAL '7 days'`.

## Referenser

- Spec: `docs/superpowers/specs/2026-05-10-devroom-design.md` sektion 5.3
- ADR-0001: Microservice decomposition (motiverar varför events behövs alls)
- ADR-0005: Inga foreign keys över databas-gränser (kompletterar outbox — konsistens via events)
- Flyway-migration: `V4__create_outbox_events.sql`
- Implementation: `SignupService.signup()`, `OutboxPublisher.publishPending()`
- Mönsterreferens: https://microservices.io/patterns/data/transactional-outbox.html
