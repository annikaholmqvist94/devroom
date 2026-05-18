# ADR-0004: gRPC vs REST — var och varför

**Status:** Accepted
**Date:** 2026-05-18
**Context:** Devroom består av fem backend-services som behöver kommunicera både med varandra och med en frontend. Vi behöver en konsistent regel för när intern trafik går över gRPC respektive REST.

## Sammanhang

Tre olika kategorier av trafik förekommer i Devroom:

1. **Klient → Gateway** — frontend (Next.js i webbläsare) anropar Gateway via HTTP/JSON med cookie-baserad auth.
2. **Gateway → backend-services** — Gateway relayar requests till Auth/Message/User Service med Bearer-token i header.
3. **Service → service (intern read-trafik)** — Message Service slår upp users via User Service vid mention-resolution och `as_user_id`-verifiering. Bot Service (Plan 7) kommer göra samma sak för avsändaruppslag.

För kategori 1 är HTTP/JSON det enda rimliga alternativet — webbläsare talar inte gRPC. För kategori 2 och 3 finns ett verkligt val. Plan 3 stod vi inför frågan när vi ritade User Service:s ingress: skulle dess interna API vara REST (`GET /users/{id}`) eller gRPC (`UserGrpcService.GetUser`)?

## Alternativ

| # | Alternativ | Klient ↔ Gateway | Gateway ↔ Services | Service ↔ Service (read) | Status |
|---|---|---|---|---|---|
| A | gRPC överallt utom från browsern | REST | gRPC | gRPC | Förkastat |
| B | REST överallt | REST | REST | REST | Förkastat |
| C | gRPC för intern read, REST för writes och klient-trafik | REST | REST | gRPC | **Valt** |

### Alternativ A — gRPC överallt utom från browsern

Bot Service och Gateway är båda Java-services, så de skulle kunna prata gRPC mot Auth/Message/User. Det skulle ge enhetlig typning på serversidan och en enda IDL (proto) som kontrakt.

**Förkastas av två skäl:**
- **Write-paths skulle behöva två gränssnitt.** Frontend måste kunna skapa meddelanden via Gateway → Message Service. Om Gateway → Message Service var gRPC behövde Message Service exponera *både* en gRPC-skriv-endpoint och en REST-skriv-endpoint (för debug, k8s-probes, integrationstester med curl). Duplicering utan vinst.
- **Operativ komplexitet.** Varje gRPC-endpoint kräver proto-fil + version-management + generation-pipeline. För skriv-trafik där datan ändå serialiseras som JSON i bot-flödet och i HTTP-payloads är kostnaden onödig.

### Alternativ B — REST överallt

Enklast tänkbara stack: en enda protokoll, JSON för alla payloads, alla endpoints går att curl:a och observera utan extra verktyg.

**Förkastas av två skäl:**
- **Förlust av kontrakts-styrka.** Mention-resolution är en read-tung operation som anropas på *varje* `POST /messages`. JSON-deserialisering med Jackson är dyrt jämfört med proto-binär; och vi förlorar typsäkerheten som proto ger (`ResolveMentionsRequest.teamId` är `String`, men varje sida vet att det är en UUID).
- **Kurs-krav.** Devroom-kursen kräver att gRPC används någonstans i arkitekturen. Att utesluta det helt vore inkonsistent med specifikationen.

### Alternativ C (valt) — gRPC för intern read, REST för writes och klient-trafik

Konkret fördelning:

| Trafik | Protokoll | Var |
|---|---|---|
| Frontend → Gateway | REST + cookie | Plan 6 |
| Gateway → Auth (signup/login) | REST + Bearer | Plan 6 |
| Gateway → Message Service (POST/GET /messages) | REST + Bearer | Plan 5 + Plan 6 |
| Bot Service → Message Service (POST /messages som system-user) | REST + Bearer (scope `bot:write`) | Plan 5 + Plan 7 |
| Message Service → User Service (mention-resolution, as_user_id-verifiering) | **gRPC** | Plan 3 + Plan 5 |
| Bot Service → User Service (avsändaruppslag) | **gRPC** | Plan 7 |
| Auth Service → User Service (`user.registered`-event) | RabbitMQ (separat ADR-0002) | Plan 4 |

Regeln i ord: **gRPC används bara service-internt, bara för read-operationer.** Allt annat är REST.

## Beslut

Alternativ C — gRPC för intern service-till-service read-trafik, REST för writes och för all trafik som passerar Gateway.

## Konsekvenser

**Positivt:**

- **Stark typning där det betyder mest.** Intern trafik mellan services har skarpa kontrakt via proto-filer i `proto/user.proto`. Stub:ar genereras vid build-time, så fel som "fel namn på fält" upptäcks vid compile, inte vid runtime.
- **Operativ enkelhet där det betyder mest.** REST/JSON för klient-trafik betyder att frontend kan `fetch()` mot Gateway, k8s liveness-probes kan vara HTTP, och varje endpoint kan curl:as i en smoke-test (se Plan 5 Task 14).
- **Prestanda för hot path.** `POST /messages` triggar gRPC mot User Service i samma request-cykel — binär proto + HTTP/2 är märkbart snabbare än REST/JSON för den anropet, särskilt under last.
- **Lättare för frontend att inte hantera proto.** Ingen `grpc-web`-bundle i webbläsaren. Cookies + JSON är allt frontend behöver veta.

**Negativt:**

- **Två protokoll att underhålla.** Proto-filer ligger i `proto/` med Maven plugin per service. REST-DTOs ligger i `web/`-paketet per service. Dokumentation måste täcka båda.
- **Mappnings-lager krävs.** Eftersom proto-typer inte ska läcka in i affärslogiken har vi `MentionResolver` och `ServiceTokenSenderResolver` i Plan 5 som översätter protobuf → domän-typer. Det är extra kod, men håller core decoupled.
- **gRPC kräver mer tooling.** Maven plugin (`protobuf-maven-plugin`), `os-maven-plugin` för plattforms-binärer, plus runtime-deps (`grpc-protobuf`, `grpc-stub`). Plan 1 fixade detta en gång; nyare services ärver via parent POM.

## Referenser

- Plan 3: User Service:s `UserGrpcService` (server-sidan).
- Plan 5: Message Service:s `MentionResolver` och `ServiceTokenSenderResolver` (klient-sidan + wrappers).
- ADR-0006: Vilken Spring-starter vi använder för att implementera gRPC.
- Devroom spec, sektion 3.3 (gRPC-anrop) och sektion 4.1-4.3 (Message Service:s endpoints).
