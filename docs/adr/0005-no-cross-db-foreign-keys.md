# ADR-0005: Inga foreign keys över databas-gränser

**Status:** Accepted
**Date:** 2026-05-14
**Context:** Varje service äger sin egen Postgres-databas (ADR-0001); domän-entiteter refererar till entiteter i andra services

## Sammanhang

Per ADR-0001 har varje service en egen databas:

- **authdb** (Auth Service) — `users` (credentials, BCrypt-password, team_id)
- **userdb** (User Service) — `user_profiles` (display-namn, mentor-flagga), `teams`
- **messagedb** (Message Service) — `channels`, `messages` (med `author_id`-kolumn som refererar till en user)

En `messages`-rad har en `author_id` som logiskt pekar på en user. I en monolit skulle vi haft `FOREIGN KEY (author_id) REFERENCES users(user_id)`. I vår uppdelning är tabellerna i olika databaser — Postgres FK-constraints fungerar inte över DB-gränser.

Frågan är inte "kan vi" (svaret är nej, tekniskt) utan "hur designar vi konsistens":

- Ska vi validera vid INSERT att `author_id` finns i userdb?
- Vad händer när en user raderas — ska alla messages cascadea?
- Hur säkerställer vi att två services har samma uppfattning om vilka users som finns?

## Beslut

**Inga FK-constraints, ingen synkron validering av referenser, ingen cross-service cascade.** Vi accepterar att konsistens är eventuell, inte momentan.

**Hur konsistens upprätthålls:**

1. **User Service publicerar livscykelevents:**
   - `user.registered` (vid signup) — andra services skapar lokala kopior/cachar om de behöver det
   - `user.deleted` / `user.deactivated` (vid borttagning) — andra services markerar referenser som "deleted user"
2. **Consumers är idempotenta** (samma `event_id` kan komma två gånger, ska ge samma resultat)
3. **Soft delete + tombstone events** istället för hard delete — borttagna users finns kvar med `deleted_at`-timestamp, och displayas som "Deleted user" i UI:t
4. **Inga cross-service joins i SQL.** Om Message Service behöver visa author-display-namn hämtas det via gRPC-anrop till User Service (`GetUser`) eller från en lokal projektion uppdaterad från events

## Övervägda alternativ

**Alt A: Logiska FK med synkron validering vid INSERT.**
Vid INSERT i `messages` gör Message Service en gRPC-anrop till User Service för att verifiera att `author_id` finns. Avvisad. Coupling: Message Service kan inte skriva om User Service är nere. Latensen ökar med ett nätverksanrop per write. Race conditions kvarstår — usern kan tas bort mellan validation och INSERT.

**Alt B: Shared schema (alla services delar en databas).**
Avvisad. Bryter ADR-0001 (varje service äger sitt datalager). Tar bort den centrala fördelen med microservices: oberoende schema-evolution, deploy och skalning.

**Alt C: Postgres Foreign Data Wrappers (`postgres_fdw`).**
Postgres kan exponera tabeller från en annan Postgres-instans som "external tables" och deklarera FK över dem. Avvisad. Skapar runtime-koppling mellan databaserna (om userdb är nere kraschar writes i messagedb). Performance-overhead. Bryter service-isoleringen och försvårar separata DB-uppgraderingar.

**Alt D: Distribuerade transaktioner (Saga eller XA).**
Använda saga-pattern eller XA för att synkronisera writes över services. Avvisad. För read-cases (visa author-namn) är detta overkill — vi behöver inte ACID-konsistens där, eventual räcker. För write-cases (signup) använder vi outbox-pattern (ADR-0002) som är enklare än fulla sagas.

## Konsekvenser

**Positiva:**

- Service-isolering bevaras. Auth-DB, user-DB, message-DB kan deployas, uppgraderas och migreras helt oberoende.
- Inga runtime-beroenden mellan databaser. Message Service kan acceptera writes även om User Service är nere — `author_id` lagras, displayas eventuellt korrekt när User Service kommer tillbaka.
- Snabbare writes — ingen synkron FK-validation per INSERT.
- Schema-migrationer är fria — Auth Service kan byta `users.user_id` från UUID till bigint utan att koordinera med andra services (så länge tokens fortfarande publicerar samma `user_id`-claim).

**Negativa:**

- **Eventual consistency.** En `messages`-rad kan referera till en `author_id` som inte (längre) finns i userdb. UI:t måste hantera "Deleted user" / "Unknown user" graciöst.
- **Inga cross-service SQL-joins.** Visa-author-namn på messages kräver gRPC-anrop eller lokal projektion. Mer kod, mer cache-invalidering.
- **Inga cascading deletes.** När en user raderas måste varje service som har referenser hantera sina egna rader (anonymisera, markera som deleted, behålla för audit).
- **Möjlig "ghost references"** under tider av nätverksparticion eller event-leveransproblem. Mitigeras av idempotenta consumers + outbox-pattern (ADR-0002).
- Lättare att skriva felaktig kod som *antar* referential integrity. Krävs disciplin i code review.

## Referenser

- Spec: `docs/superpowers/specs/2026-05-10-devroom-design.md` sektion 5.4
- ADR-0001: Microservice decomposition (motivation för separata DB)
- ADR-0002: Transactional outbox (mekanismen som driver consistency-events)
- ADR-0004: gRPC för intern read-trafik (`GetUser` används för att läsa cross-service data)
- Mönsterreferens: https://microservices.io/patterns/data/database-per-service.html
