# Plan 15 — Tempo (distribuerad tracing): Designspecifikation

**Datum:** 2026-07-15
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** B — Observability (del 3 av 3: metrics → loggar → **tracing**)

---

## 1. Kontext och mål

Efter Plan 13–14 har Devroom metrics och loggar i Grafana, men ingen tracing. När ett
request rör sig genom flera tjänster finns inget sätt att följa det end-to-end. Ingen
tjänst har tracing/OpenTelemetry-beroenden idag.

**Mål:** Ge Devroom distribuerad tracing: en enda trace följer ett request genom **HTTP,
gRPC och RabbitMQ**, lagras i **Tempo**, och visas som ett waterfall i Grafana — med
länkning trace↔logg. Sista planen i Fas B (observability komplett).

**Icke-mål:** Metrics-exemplars, tail-sampling, trace-baserade larm, frontend-tracing.

Pipeline: tjänst instrumenteras (Micrometer Tracing / Observation) → OTLP → **Grafana Alloy**
(enhetlig collector, återanvänd från Plan 14) → **Tempo** → **Grafana** (waterfall).

---

## 2. Arkitektur-översikt

### 2.1 Tre delar

**Del 1 — Instrumentering (app-ändring, de 5 Spring-tjänsterna)**

- **Beroenden** (per service-pom, samma mönster som `micrometer-registry-prometheus` i Plan 13):
  - `io.micrometer:micrometer-tracing-bridge-otel` — bryggar Spring Boots Observation-API till
    OpenTelemetry.
  - `io.opentelemetry:opentelemetry-exporter-otlp` — exporterar spans via OTLP.
- **Config** (`application.yml`, per tjänst):
  - `management.tracing.sampling.probability: 1.0` (100 % — demo, allt spåras).
  - `management.otlp.tracing.endpoint: http://alloy.monitoring.svc:4318/v1/traces` (OTLP/HTTP
    mot Alloys receiver).
- **HTTP:** auto-instrumenteras av Spring Boot Observation (WebMVC-server + RestClient). Trace
  context propageras via W3C `traceparent`-header.
- **RabbitMQ:** `spring.rabbitmq.template.observation-enabled: true` +
  `spring.rabbitmq.listener.observation-enabled: true` → `traceparent` propageras via
  meddelande-headers över publish→consume (Auth outbox, Message→Bot).
- **gRPC:** Spring gRPC 1.0.3 — observation-interceptors (klient + server) wire:as så
  trace-kontexten följer med i gRPC-metadata (message-service → user-service `ResolveMentions`,
  bot-service → user-service). **Känsligaste biten — se risk (§4).**

**Del 2 — Tempo + Alloy (kluster-infra)**

- **Tempo** (`grafana/tempo` Helm-chart, monolithic-läge — en pod, filsystem) som egen release
  i `monitoring`-namespacet.
- **Alloy** (från Plan 14) utökas med en trace-pipeline i `alloy-values.yaml`:
  `otelcol.receiver.otlp` (grpc :4317 + http :4318) → `otelcol.exporter.otlp` mot Tempos
  OTLP-receiver (`tempo.monitoring.svc:4317`). Alloy blir *en* enhetlig collector för loggar
  **och** traces.
- En **ClusterIP-service** exponerar Alloys OTLP-portar (4317/4318) så tjänsterna kan skicka
  dit via `alloy.monitoring.svc`. Konfigureras i alloy-chartets `service`/`extraPorts`.
- Installationen sker via ett **utökat `helm/install-logging.sh`** (Tempo + Alloy bor i samma
  `monitoring`-ns som Loki → en observability-install; skriptet döps ev. om men förblir ett).

**Del 3 — Grafana-koppling**

- **Tempo-datakälla** provisioneras som ConfigMap i `monitoring` (label `grafana_datasource:
  "1"`, uid `tempo`), pekande på `http://tempo.monitoring.svc:3100`.
- **Trace→logg-länkning:** Tempo-datakällans `tracesToLogsV2` konfigureras mot Loki-datakällan
  (uid `loki`) så man hoppar från en span till motsvarande loggar. De tre pelarna kopplade.
- Traces frågas i Grafana **Explore** → Tempo (sök på tjänst/varaktighet → waterfall).

### 2.2 Dataflöde (showpiece)

Posta ett meddelande → **en trace** med spans:
1. gateway (HTTP-server, root-span)
2. → message-service `POST /messages` (HTTP, `traceparent`-header)
3. → user-service `ResolveMentions` (gRPC, kontext i metadata)
4. → RabbitMQ publish `message.published` (`traceparent` i header)
5. → bot-service consume (fortsätter samma trace)
6. → dev-mentor `/api/v1/chat` (HTTP)
7. → message-service `POST /messages` (bot-svar, HTTP)

Ett vattenfall över HTTP + gRPC + RabbitMQ i Grafana.

### 2.3 Låsta designval (från brainstorming 2026-07-15)

| Val | Beslut | Motivering |
|---|---|---|
| Ambition | Full cross-transport (HTTP + gRPC + RabbitMQ) | Den äkta mikrotjänst-showpiecen |
| Exportväg | Via Grafana Alloy (OTLP-receiver → Tempo) | Enhetlig collector (loggar + traces), återanvänder Plan 14 |
| Tempo-läge | Monolithic (en pod, filsystem) | Enklast för Minikube |
| Sampling | 100 % (`probability: 1.0`) | Demo — varje request spåras |
| Grafana | Tempo-datakälla + trace→logg-länkning | Kopplar de tre pelarna |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `services/*/pom.xml` (×5) | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` |
| `services/*/src/main/resources/application.yml` (×5) | tracing-sampling + OTLP-endpoint + rabbit observation |
| `services/{user,message,bot}-service/.../config/*` | gRPC observation-interceptors (klient/server) |
| `helm/tempo-values.yaml` | Tempo-chart-värden (monolithic) |
| `helm/alloy-values.yaml` | Utöka med OTLP-receiver → Tempo-exporter |
| `helm/grafana-tempo-datasource.yaml` | Tempo-datakälla (ConfigMap, trace→logg) |
| `helm/install-logging.sh` | Utöka: installera Tempo + applicera datakälla |
| `docs/adr/0014-tempo-tracing.md` | ADR |

---

## 4. Risk

**gRPC-hoppet är osäkrast.** Spring gRPC 1.0.3:s observation-stöd kan kräva manuellt wire:ade
`ObservationGrpcClientInterceptor`/`ObservationGrpcServerInterceptor` (från
`micrometer-tracing`/`grpc`-integrationen). Om kontexten inte propagerar korrekt bryts kedjan
vid gRPC — message→user blir en *separat* trace istället för samma. **Fallback:** best-effort
gRPC-span; HTTP- och RabbitMQ-delarna av kedjan är robusta och ger ändå ett tydligt
cross-transport-flöde. Verifieras vid live-körning; om interceptorerna inte finns i Spring gRPC
1.0.3 dokumenteras gRPC-hoppet som känt gap i ADR-0014.

Sekundärt: **Docker/kluster är nere** i nuvarande miljö → live-verifiering skjuts upp (som Plan
13–14); statisk verifiering (helm template, mvn-boot, YAML) görs fullt ut.

---

## 5. Verifiering

- **Statiskt:** `helm template` av Tempo-chart + Alloy-config med trace-pipelinen renderar;
  `mvn -pl <tjänst> verify` bootar med tracing-beroenden; datakälla-YAML giltig; `helm lint`.
- **Live (uppskjuten):** posta ett meddelande → Grafana Explore → Tempo visar en trace som
  spänner gateway → message (HTTP) → user (gRPC) → RabbitMQ → bot → dev-mentor; trace→logg-hopp
  fungerar.

---

## 6. Out of scope (explicit)

- Metrics-exemplars (Prometheus-histogram → trace-länk).
- Tail-sampling / sampling-strategier bortom 100 %.
- Trace-baserade larm.
- Frontend/browser-tracing (Next.js).
- Objektlagring/retention för Tempo (filsystem räcker lokalt).
