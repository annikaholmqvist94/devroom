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
git branch    # plan-01-bootstrap (aktiv) + main
git log --oneline | head -5
```

**Plan 01 Task 1-3 klara** (parent POM, auth-starter scaffold, JwtClaims). Auth-starter sedan borttaget vid pivot — modulen finns inte längre.

**Pivot 2026-05-12 klart** — 8 commits dokumenterar omarbetet. Spec, ADR-0003, Plan 02 och Plan 06 helt omskrivna. Plans 05/07/08/10 har "Revision 2026-05-12"-banners i topp som dokumenterar diffar mot original.

**Nästa steg:** Plan 02 Task 1 — scaffold auth-service Maven-modul.

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
