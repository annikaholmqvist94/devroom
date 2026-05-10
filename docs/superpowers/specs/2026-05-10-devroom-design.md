# Devroom — Designspecifikation

**Datum:** 2026-05-10
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Tidsram:** 3,5 veckor heltid (~140h, planerat 122h effektiv tid med 10% buffert)

---

## 1. Kontext och mål

Devroom är en team-chattapplikation för 2-10 utvecklare med fyra @-mentionable AI-mentorer. Användarvärdet är att en utvecklare kan skriva `@code-reviewer kan du kolla det här` direkt i teamchatten utan att lämna kontexten för att gå till en separat AI-tjänst.

**Mentorer:**

- `junior-helper` — tålmodig, förklarar från grunden
- `senior-architect` — fokuserar på trade-offs, "it depends"
- `code-reviewer` — rak och direkt kritik
- `rubber-duck` — ställer sokratiska frågor istället för att svara

**Tre samtidiga målbilder:**

1. **Laboration 2 i backend-kurs** ("microservices och distribuerade system"). Examineras genom kodgranskning, muntlig redovisning, live-demo. VG kräver Kubernetes-deploy utöver basbetygskraven.
2. **Portfolio-projekt.** Kvalitetsmarkörer (ADR:er, integrationstester, "docker compose up" räcker, README med diagram + demo-GIF) väger lika tungt som funktionalitet.
3. **Återanvändning av tidigare laboration.** Bot Service wrappar den befintliga "Nordic Dev Mentor"-tjänsten utan att modifiera dess kärnlogik. Nordic Dev Mentor förblir egen kodbas på `~/IdeaProjects/dev-mentor`.

---

## 2. Arkitektur-översikt

### 2.1 Tjänster och ansvar

| Tjänst | Ansvar | Stack | Databas |
|---|---|---|---|
| **BFF** | REST-fasad mot klient. Validerar user-JWT. Propagerar JWT till interna tjänster. | Spring Boot 4, Java 21 | — |
| **Auth Service** | Signup + login. Utfärdar user-JWT. Skriver `credentials` + `outbox_events` atomärt. Pollar outbox och publicerar `user.registered` till RabbitMQ. | Spring Boot 4 | AuthDB (Postgres) |
| **User Service** | Profil-CRUD, team-uppslag. Konsumerar `user.registered` (idempotent). Exponerar gRPC för avsändar- och mention-uppslag. Seedar mentor-rader (`is_system=true`) vid uppstart. | Spring Boot 4 | UserDB (Postgres) |
| **Message Service** | Tar emot meddelanden via REST, resolvar mentions via gRPC mot User, lagrar, publicerar `message-published`. Accepterar både user-JWT (BFF-väg) och service-JWT (Bot-väg). | Spring Boot 4 | MessageDB (Postgres) |
| **Bot Service** | Konsumerar `message-published`, filtrerar på mentions med `is_system=true`, slår upp avsändarinfo via gRPC, anropar Nordic Dev Mentor-kärnan, postar svar via REST mot Message Service med service-JWT. | Spring Boot 4, **wrappar Nordic Dev Mentor** | — |
| **Frontend** | Login/signup, kanal-lista, kanalvy med trådar, polling 3s. | Next.js 16, React 19, TS, Tailwind 4 | — |

### 2.2 Kommunikationsmönster

```
                    +-----------------------+
                    |  Next.js (polling 3s) |
                    +----------+------------+
                               | REST + user-JWT
                               v
                    +-----------------------+
                    |          BFF          | (validerar JWT)
                    +-+---------+----------++
                 REST | REST    | REST + JWT propageras
                      v         v          v
              +----------+ +---------+ +--------------+
              |   Auth   | |   User  | |   Message    |
              |  Service | | Service | |   Service    |
              +----+-----+ +----^----+ +-+----+-------+
                   |            | gRPC   |    |
                   |            +--------+    | AMQP publish
              outbox|                          v
              poll  v
              +----------------------------------------+
              |              RabbitMQ                  |
              |  user.registered  |  message-published |
              +----+----------------------+------------+
                   | consume              | consume
                   v                      v
              +----------+          +--------------+
              |   User   |          | Bot Service  |
              | Service  |          | (wrappar     |
              | (creates |          |  Nordic Dev  |
              |  profile)|          |   Mentor)    |
              +----------+          +-+----+-------+
                                      |    |
                                      |    | gRPC (sender lookup)
                                      |    +--> User Service
                                      |
                                      | REST + service-JWT
                                      +-----> Message Service
                                              (POST /messages
                                               as_user_id=<mentor>)
```

### 2.3 Låsta designval

1. **Mentor-identitet:** hybrid system-users i User Service (`is_system=true`, kan inte logga in)
2. **Datamodell:** kanaler per team + trådar (`parent_message_id`), ingen DM
3. **Signup:** outbox pattern + event-driven choreography (Auth → outbox → RabbitMQ → User)
4. **Säkerhet:** per-service JWT-validering (delad public key), Bot Service har service-JWT i K8s Secret
5. **gRPC-användning:** Message → User (vid skriv för mention-resolution) och Bot → User (avsändar-uppslag)
6. **Bot → Message:** REST med service-JWT (Bot inkluderar `as_user_id` som måste peka på `is_system=true` user)
7. **Polling:** periodic 3s från klient mot BFF (long-polling och WebSockets avslagna)
8. **Spring Boot 4** överallt för konsistens med Nordic Dev Mentor
9. **Build-system:** Maven multi-module (parent POM + module POMs) för konsistens med Nordic Dev Mentor
10. **Lokal Kubernetes:** Minikube med Docker driver. Ingen ingress controller — port-forward för demon.

---

## 3. Datamodell

### 3.1 AuthDB

```sql
credentials (
  user_id        UUID PRIMARY KEY,
  email          VARCHAR(255) UNIQUE NOT NULL,
  password_hash  VARCHAR(255) NOT NULL,        -- BCrypt
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
)

outbox_events (
  id            BIGSERIAL PRIMARY KEY,
  event_type    VARCHAR(64) NOT NULL,          -- 'user.registered'
  payload       JSONB NOT NULL,                 -- hela event-payloaden
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  processed_at  TIMESTAMPTZ NULL                -- NULL = ej publicerad till MQ
)

CREATE INDEX idx_outbox_unprocessed
  ON outbox_events(created_at)
  WHERE processed_at IS NULL;
```

Partial index på `processed_at IS NULL` håller publishern snabb även när tabellen vuxit. En cron-rensning av `processed_at IS NOT NULL`-rader äldre än 30 dagar dokumenteras i ADR-0002.

### 3.2 UserDB

```sql
teams (
  id          UUID PRIMARY KEY,
  name        VARCHAR(100) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
)

users (
  user_id              UUID PRIMARY KEY,
  display_name         VARCHAR(100) NOT NULL,
  avatar_url           VARCHAR(500) NULL,
  team_id              UUID NOT NULL REFERENCES teams(id),
  is_system            BOOLEAN NOT NULL DEFAULT FALSE,
  mentor_personality   VARCHAR(50) NULL,        -- 'code-reviewer' osv. för is_system=true
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
)

CREATE INDEX idx_users_team ON users(team_id);
CREATE INDEX idx_users_team_name ON users(team_id, display_name);
```

`mentor_personality` är central: när Bot Service ser en mention med `is_system=true` behöver den veta vilken personlighet som ska aktiveras i Nordic Dev Mentor.

**Seed-data vid uppstart:**

- 1 team: "Devroom Demo Team"
- 4 mentor-users: junior-helper, senior-architect, code-reviewer, rubber-duck (alla `is_system=true`, alla i demo-teamet)

### 3.3 MessageDB

```sql
channels (
  id          UUID PRIMARY KEY,
  team_id     UUID NOT NULL,                    -- ingen FK, User Service äger teams
  name        VARCHAR(100) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (team_id, name)
)

messages (
  id                  UUID PRIMARY KEY,
  channel_id          UUID NOT NULL REFERENCES channels(id),
  sender_id           UUID NOT NULL,            -- ingen FK, User Service äger users
  body                TEXT NOT NULL,
  parent_message_id   UUID NULL REFERENCES messages(id),
  mentions            JSONB NOT NULL DEFAULT '[]'::jsonb,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
)

CREATE INDEX idx_messages_channel_created
  ON messages(channel_id, created_at DESC);
CREATE INDEX idx_messages_parent
  ON messages(parent_message_id)
  WHERE parent_message_id IS NOT NULL;
```

**Seed-data:** 3 kanaler i demo-teamet — `#general`, `#frontend`, `#backend`.

**Två designval värda att uppmärksamma:**

1. **Inga foreign keys över databas-gränser.** `messages.sender_id` pekar på en UUID i UserDB men databassystemet kan inte verifiera den. Application-level integritet ersätter database-level. (ADR-0005.)
2. **`mentions` som JSONB**, inte junction-tabell. Innehåller `[{user_id, is_system, personality}, ...]`. Mentions ändras aldrig efter skapande, JOIN behövs inte vid läsning, MQ-payload speglar exakt kolumninnehållet. (ADR-0006 reservpott.)

---

## 4. Säkerhet

### 4.1 JWT-claims

**User-JWT** (utfärdad av Auth Service vid login, exp 1 timme):

```json
{
  "iss": "auth-service",
  "sub": "<user_uuid>",
  "team_id": "<team_uuid>",
  "iat": 1715347200,
  "exp": 1715350800
}
```

**Service-JWT** (statisk i K8s Secret, exp 1 år för demon):

```json
{
  "iss": "auth-service",
  "sub": "bot-service",
  "roles": ["system"],
  "iat": 1715347200,
  "exp": 1746883200
}
```

**Signatur-algoritm:** RS256 med 2048-bitars RSA-nyckelpar.

- Private key: i AuthService K8s Secret. Bara Auth Service har den.
- Public key: i ConfigMap eller mountad som fil i alla services. Distribueras vid deploy.

### 4.2 Per-service-validering (defense-in-depth)

BFF validerar JWT från klient. När BFF anropar interna tjänster propagerar den hela JWT i `Authorization: Bearer`-header. Varje intern tjänst validerar JWT igen mot delad public key.

**Validering omfattar:**

1. Verifiera signatur mot public key
2. `exp` inte passerats
3. `iss == "auth-service"`
4. Extrahera `sub`, `team_id`, `roles` till `SecurityContext`

Logiken implementeras i delad Maven-modul `auth-starter` (en gemensam `JwtAuthenticationFilter` + Spring Security-config). Återanvänds av alla 5 services.

### 4.3 Service-token för Bot Service

Bot Service postar bot-svar mot Message Service via REST. Den autentiserar sig med en pre-issued service-JWT (`sub: bot-service`, `roles: [system]`) lagrad i K8s Secret. Token genereras manuellt med Auth Services private key och commitas inte till repo.

**Säkerhetscheck i Message Service vid POST /messages:**

- Om `roles` innehåller `system`: kräv att `body.as_user_id` finns och pekar på en user där `is_system=true` (verifieras via gRPC mot User Service).
- Om vanlig user-JWT: använd `sub` som `sender_id`, ignorera ev. `as_user_id`.

Detta förhindrar att service-token används för att posta som godtycklig user.

---

## 5. Asynkrona events

### 5.1 `user.registered`

Publicerad av Auth Service via outbox-pattern.

```json
{
  "event_id": "evt-abc123",
  "event_type": "user.registered",
  "occurred_at": "2026-05-10T14:30:00Z",
  "user_id": "9b8d2e1f-...",
  "email": "annika@example.com",
  "team_id": "1a2b3c4d-..."
}
```

User Service konsumerar och skapar profil. Idempotency: kollar `SELECT FROM users WHERE user_id = X` innan INSERT.

### 5.2 `message-published`

Publicerad av Message Service direkt **utan outbox**. Motivering: `user.registered` är en *sanning* som måste levereras (annars saknar användaren profil för evigt). `message-published` är en *notis* — om publishen failar efter DB-write returnerar Message Service 502 till klienten, klienten retryar, och användaren ser sitt meddelande dyka upp dubbelt om/när systemet återhämtar sig. Vi accepterar detta för att slippa bygga outbox-mekanism två gånger.

Konkret implementation: DB-write och RabbitMQ-publish sker i samma `@Transactional`-metod, där RabbitTemplate-publishen körs *efter* commit men *innan* metoden returnerar. Vid publish-fel: log + 502 till klient. Meddelandet finns kvar i DB.

```json
{
  "event_id": "evt-xyz789",
  "event_type": "message-published",
  "occurred_at": "2026-05-10T14:30:00Z",
  "message_id": "msg-uuid",
  "channel_id": "ch-uuid",
  "team_id": "team-uuid",
  "sender_id": "user-uuid",
  "body": "@code-reviewer kan du kolla det här?",
  "parent_message_id": null,
  "mentions": [
    {
      "user_id": "cr-uuid",
      "is_system": true,
      "personality": "code-reviewer"
    }
  ]
}
```

`mentions[]` innehåller resolved data — Bot Service behöver inte göra extra User-Service-anrop för att läsa personlighet, bara för avsändarens display_name.

### 5.3 Outbox-pattern för signup

Auth Service skriver `credentials`-rad och `outbox_events`-rad i samma DB-transaktion. Postgres garanterar atomicity.

Outbox-publisher (`@Scheduled` inuti Auth Service-JVM, var 500 ms):

1. `SELECT * FROM outbox_events WHERE processed_at IS NULL ORDER BY created_at LIMIT 100`
2. För varje rad: publicera till RabbitMQ exchange
3. `UPDATE outbox_events SET processed_at = NOW() WHERE id = ?`

**At-least-once-leverans:** publishern kan krascha mellan steg 2 och 3, vilket leder till dubbelpublicering. User Service-konsumentens idempotency-check hanterar detta.

**Dead Letter Queue:** events som consumern fortsätter rejecta hamnar i `user.registered.dlq`. Manuell övervakning, ingen auto-retry från DLQ.

### 5.4 RabbitMQ-konfiguration

| Egenskap | Värde |
|---|---|
| Queue durability | `durable=true` |
| Message persistence | `delivery_mode=2` (persistent) |
| Consumer ack-mode | `manual` (NACK vid fel → DLQ efter retry) |
| Prefetch count | 10 per consumer |
| Retry-policy | 3 försök med exponential backoff (1s, 2s, 4s) |
| Dead Letter Queue | Per event-typ (`user.registered.dlq`, `message-published.dlq`) |

---

## 6. Felhantering

| Scenario | Beteende |
|---|---|
| User Service nere när Auth publicerar `user.registered` | RabbitMQ behåller event (durable + persistent). Konsumeras när User Service kommer upp. Eventually consistent. |
| Outbox-publisher kraschar mellan publish och mark-processed | Event publiceras igen vid nästa körning. User Service idempotent → ingen duplicering. |
| LLM-anrop failar i Bot Service | Standard-retry från Nordic Dev Mentor (429/5xx). Vid alla retries failat: NACK → DLQ. Användarens meddelande är ändå sparat — bara bot-svaret saknas. |
| Bot Service postar svar mot Message Service som är nere | Bot Service retryar 3x med exponential backoff. Vid totalfel: NACK → DLQ. Idempotency-nyckel på POST /messages baserat på original-message-id förhindrar dubbelsvar. |
| gRPC mot User Service failar vid mention-resolution | Hård fail. Message Service returnerar 502, klienten retryar. Inget meddelande sparas. (ADR-0007 reservpott motiverar valet.) |
| Concurrent signup med samma email | AuthDB UNIQUE constraint → IntegrityException → BFF returnerar 409. |
| Mention på okänd mentor | User Service `ResolveMentions` returnerar tom lista. `mentions[]` får ingen `is_system=true` entry. Bot Service ignorerar. Användaren ser sitt meddelande men inget svar. |
| Expired JWT | 401 från valfri tjänst. Frontend tvingar omlogin. (Ingen refresh-token för demon.) |
| Frontend pollar och BFF nere | Frontend exponential backoff. Användaren ser stale data tills BFF återkommer. |

---

## 7. Repo-struktur

Maven multi-module monorepo:

```
devroom/
├── README.md                          # arkitektur, demo-GIF, snabbstart
├── pom.xml                            # parent POM: Spring Boot 4 BOM, Java 21
├── docker-compose.yml                 # full stack lokal
├── docker-compose.dev.yml             # bara infra (postgres + rabbitmq)
├── .github/workflows/ci.yml           # bygg + test alla moduler
│
├── docs/
│   ├── adr/                           # 5 huvud-ADR:er (+ reservpott)
│   ├── diagrams/                      # Mermaid-källor
│   └── superpowers/specs/             # designspec (denna fil)
│
├── proto/                             # delade .proto-filer för gRPC
│   └── user.proto                     # GetUser, ResolveMentions
│
├── modules/
│   └── auth-starter/                  # delad Spring Security/JWT-config
│       └── pom.xml
│
├── services/
│   ├── bff/pom.xml
│   ├── auth-service/pom.xml
│   ├── user-service/pom.xml
│   ├── message-service/pom.xml
│   └── bot-service/pom.xml
│
├── frontend/                          # Next.js 16 (sär-byggd, ej i Maven)
│
└── k8s/                               # Kubernetes manifests
    ├── namespace.yaml
    ├── postgres/                      # 3 separata Deployments för 3 DBs
    ├── rabbitmq/
    ├── secrets.yaml.template
    ├── configmaps.yaml
    └── (en yaml per service)
```

Varje service har sin egen `Dockerfile` (multi-stage: build + runtime). Frontend har egen `Dockerfile`.

---

## 8. Build-ordning (3,5 veckor heltid)

### 8.1 Vecka 1: Fundament + Auth + User (40h)

| Dag | Arbete |
|---|---|
| 1 | Repo-setup, Maven multi-module parent POM, docker-compose med Postgres + RabbitMQ, "hello world" Spring Boot för en service. **ADR-0001 Microservice decomposition** skrivs innan kod. |
| 2 | `auth-starter`-modulen (JWT-validering, RSA-nyckelgenerering), proto-definitioner för User Service gRPC, root-CI. **ADR-0003 JWT defense-in-depth** skrivs samma dag. |
| 3 | Auth Service: Flyway-migrations, `credentials`-tabell, signup-endpoint, BCrypt, JWT-utfärdande. **ADR-0005 Inga FK över DB-gränser** skrivs när första migration skapas. |
| 4 | Auth Service: login-endpoint, `outbox_events`-tabell, outbox-publisher (skriver lokalt — RabbitMQ-publish kommer i vecka 2). **ADR-0002 Outbox pattern** skrivs samma dag. |
| 5 | User Service: Flyway, `teams` + `users`-tabeller, seedade mentor-rader, gRPC-endpoint för `GetUser` och `ResolveMentions`. |

**Slutet av vecka 1:** signup via curl, JWT-validering i en dummy-tjänst, gRPC-anrop mot User Service med grpcurl. Två tjänster fungerar fristående. 4 av 5 ADR:er skrivna.

### 8.2 Vecka 2: MQ + Message + BFF + Bot (G-nivå klar) (40h)

| Dag | Arbete |
|---|---|
| 6 | RabbitMQ-integration: Auth-outbox-publisher publicerar `user.registered` på riktigt, User Service konsumerar idempotent. |
| 7 | Message Service: `channels` + `messages`-tabeller, POST /messages med JWT-validering, gRPC-anrop till User för mention-resolution. **ADR-0004 gRPC vs REST** skrivs samma dag. |
| 8 | Message Service: GET /messages?channel_id&since, MQ-publisher för `message-published`-event. |
| 9 | BFF: REST-fasad mot klient, JWT-validering, propagering till alla services. End-to-end via curl. |
| 10 | Bot Service: integrera Nordic Dev Mentor som dependency, MQ-consumer, mention-filter, service-JWT, REST-publisher till Message Service. |

**Slutet av vecka 2:** hela G-flödet fungerar end-to-end via curl. Mention triggar bot-svar. Alla 5 ADR:er skrivna.

### 8.3 Vecka 3: Frontend + integrationstester + K8s (VG-nivå klar) (40h)

| Dag | Arbete |
|---|---|
| 11 | Next.js-grunden: kopiera Tailwind-config, layout-shell, primitive-komponenter (Button/Input/Card) från `~/IdeaProjects/dev-mentor/frontend/`. Login/signup, lagra JWT, autentiserad fetch-wrapper, kanal-listvy. |
| 12 | Kanalvy med polling (3s), post-message-formulär, trådvy (klick-för-expandera), rendera mentor-svar med badge. |
| 13 | Testcontainers integrationstester: Auth (Postgres), User (Postgres + RabbitMQ-consumer), Message (Postgres + gRPC + MQ-publish), Bot (RabbitMQ-consume + WireMock för LLM). |
| 14 | Kubernetes-manifests: Deployments + Services + Secrets + ConfigMaps. Minikube-deploy. Verifiera intern DNS. |
| 15 | K8s-finputs: liveness/readiness probes, resource limits, port-forwards för demo, åtgärda alla "fungerar i compose men inte i k8s"-problem. |

**Slutet av vecka 3:** G + VG klart. Hela systemet kör på Minikube.

### 8.4 Halvvecka 4: Polish (16-22h)

| Dag | Arbete |
|---|---|
| 16 | Översyn och putsning av befintliga ADR:er (uppdatera om något ändrats under bygget). Reserve: skriv ev. ADR-0006 till ADR-0009 om tid finns (se sektion 13). |
| 17 | README med arkitekturdiagram (Mermaid), inspela demo-GIF (signup → mention → bot-svar), snabbstart-guide. |
| 18 (halv) | Buffert: buggfix, log-städning, kommentera kniviga delar, öva muntlig redovisning. |

---

## 9. Demo-scope

**Seed-data vid uppstart (idempotent):**

- 1 team: "Devroom Demo Team"
- 4 mentor-users (`is_system=true`)
- 3 kanaler: `#general`, `#frontend`, `#backend`

**Signup-flöde:** nya users hamnar automatiskt i demo-teamet. Ingen invitation-flöde, ingen team-skapande. Begränsning dokumenterad i README.

**Live-demo manus:**

1. Starta Minikube + deploya: `minikube start && kubectl apply -f k8s/`
2. Visa `minikube dashboard` → peka på alla pods
3. Port-forward frontend och BFF: `kubectl port-forward svc/frontend 3000:3000 & kubectl port-forward svc/bff 8080:8080 &`
4. Öppna http://localhost:3000 → signa upp en ny user
5. Visa RabbitMQ Management UI (http://localhost:15672) → peka på `user.registered`-trafiken
6. Logga in, navigera till `#general`
7. Skriv `@code-reviewer kan du förklara dependency injection?`
8. Visa eventet i RabbitMQ → bot-svar dyker upp i tråden ~5 sekunder senare
9. Visa loggar från Bot Service: `kubectl logs -f deploy/bot-service`

---

## 10. Frontend-strategi

**Stack:** Next.js 16 (App Router), React 19, TypeScript, Tailwind 4 — samma stack som Nordic Dev Mentor.

**Återanvändning från `~/IdeaProjects/dev-mentor/frontend/`:**

- Tailwind config och globala stilar
- Färgsystem och typografi
- Primitive-komponenter: Button, Input, Card, Avatar
- Layout-shell (header + main-grid)

**Nytt för Devroom (kan inte återanvändas):**

- Kanal-list-sidopanel
- Meddelandefeed med polling
- Tråd-panel som expanderar inline
- Mention-badge-rendering för `is_system=true` avsändare
- Login/signup-formulär (Nordic Dev Mentor är öppen, ingen inloggning)

**Polling-implementation:**

- `setInterval(3000)` för aktiv kanal
- `since=<last_message_id>` query-parameter mot BFF
- Stop polling när tab inte är aktiv (`document.visibilityState`)
- Exponential backoff vid fel (1s, 2s, 4s, max 30s)

---

## 11. Kubernetes-strategi (VG)

**Lokalt cluster:** Minikube med Docker driver.

```bash
brew install minikube
minikube start --driver=docker --memory=6144 --cpus=4
eval $(minikube docker-env)    # peka Docker CLI mot Minikubes daemon
```

**Manifests-struktur:**

- En `Deployment` + `Service` per backend-service (replicas=1 för demon)
- En `StatefulSet` per Postgres (3 stycken: auth-db, user-db, message-db)
- En `Deployment` + `Service` för RabbitMQ
- `Secret` för JWT private key, DB passwords, OpenRouter API key
- `ConfigMap` för JWT public key, app-konfiguration

**Intern DNS:** services pratar via DNS-namn (`auth-service:8080`, `user-service:9090`, `rabbitmq:5672`, `auth-db:5432`).

**Probes:**

- Liveness: `/actuator/health/liveness` per service
- Readiness: `/actuator/health/readiness` per service
- StartupProbe på Bot Service (Nordic Dev Mentor-init kan ta längre tid)

**Resource limits:** request 256Mi/100m CPU, limit 512Mi/500m CPU per service.

**Ingen ingress controller** — under demo använder vi `kubectl port-forward` för frontend (3000) och BFF (8080). Motiveras i K8s-ADR (reservpott) som "produktionssystem hade använt nginx-ingress eller motsvarande".

---

## 12. Test-strategi

**Per-service test-pyramid:**

- **Unit (Mockito + JUnit 5):** application-services, domain-logik, mention-parsers
- **Slice (MockMvc, @WebMvcTest):** REST-controllers utan full context
- **Integration (Testcontainers):** full Spring context + riktig Postgres + riktig RabbitMQ för relevanta tjänster + WireMock för externa API:er

**Testcontainers-täckning:**

- Auth Service: signup-flöde + outbox-publisher → riktig Postgres + riktig RabbitMQ
- User Service: konsumera `user.registered` idempotent → Postgres + RabbitMQ
- Message Service: POST /messages-flöde → Postgres + gRPC-mock mot User Service + RabbitMQ-publish
- Bot Service: konsumera `message-published` → RabbitMQ + WireMock för LLM + WireMock för Message Service POST

**Mål:** ~70% line coverage per service. Inte ett hårt KPI — bara en känsla av "har jag testat det viktiga".

---

## 13. ADR-lista

**Huvud-ADR:er (5 st, inflätade i bygg-ordningen):**

| Nr | Titel | Skrivs i |
|---|---|---|
| 0001 | Microservice decomposition: varför 5 tjänster och vilka gränser | Vecka 1 dag 1 |
| 0002 | Outbox pattern för signup-eventet (vs orchestration vs choreography) | Vecka 1 dag 4 |
| 0003 | JWT defense-in-depth: per-service-validering | Vecka 1 dag 2 |
| 0004 | gRPC vs REST: var och varför | Vecka 2 dag 7 |
| 0005 | Inga foreign keys över databas-gränser | Vecka 1 dag 3 |

**Reservpott (om tid finns i polish-veckan):**

- 0006: Mentions som JSONB i messages, inte junction-tabell
- 0007: Hård fail vid gRPC-resolution-miss vs degraderad write
- 0008: Periodic polling 3s vs long-polling vs WebSockets
- 0009: Minikube + port-forward vs ingress controller

---

## 14. Risker och scope-vakter

| Risk | Mitigation |
|---|---|
| Frontend-polering brinner timmar | Hårt cap: dag 11-12 är allt frontend får. Inga animations, ingen optimistic UI, inga dark mode-toggles. |
| gRPC första gången tar tid | 4h extra-budget i vecka 1. Använd grpcurl för manuell verifikation tidigt. |
| K8s-debugging "fungerar i compose men inte k8s" | 6-8h-budget i vecka 3. Dag 14 + 15 är båda K8s. |
| OpenRouter rate limits under demo | Använd billig modell (Haiku eller GPT-4o-mini). Spara cred till demo-dagen. |
| Nordic Dev Mentor-integration mer komplex än antaget | Läs README/ADR i `~/IdeaProjects/dev-mentor` innan vecka 2 dag 10. Justera approach då. |
| Demon kraschar | `minikube delete && minikube start` + `kubectl apply -f k8s/` ger rent state på 90 sekunder. Öva detta. |

---

## 15. Out of scope (explicit)

Följande är medvetet bortvalt och dokumenteras i README som "future work":

- Direct messages (DM) mellan users
- Trådar djupare än en nivå (svar på svar)
- Refresh-tokens (klient måste logga in om varje timme)
- Multi-team support (bara ett seedat team)
- Avatar-upload
- Markdown-rendering i meddelanden
- WebSockets / long-polling
- Ingress controller i Kubernetes (port-forward istället)
- mTLS mellan tjänster
- Outbox-pattern för `message-published` (bara för `user.registered`)
- CDC-baserad outbox-publisher (polling används istället)
- Rate limiting i BFF
- Admin-UI för team/channel-skapande (seed-data istället)
- Notification Service (push-notiser, email)
