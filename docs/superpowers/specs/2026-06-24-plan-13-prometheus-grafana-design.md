# Plan 13 — Prometheus + Grafana (metrics): Designspecifikation

**Datum:** 2026-06-24
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** B — Observability (del 1 av 3: metrics → loggar → tracing)

---

## 1. Kontext och mål

Efter Plan 12 kör Devroom på Helm med Traefik-ingress, men har **ingen observability**.
Alla 5 Spring-tjänster har redan `spring-boot-starter-actuator`, men ingen har
Micrometer-Prometheus och exposure är bara `health,info` — så inga metrics exponeras i
Prometheus-format.

**Mål:** Ge Devroom metrics-observability end-to-end: varje Spring-tjänst exponerar
Prometheus-metrics, **kube-prometheus-stack** skrapar och lagrar tidsserierna, och **Grafana**
visar dashboards — nåbart via `grafana.devroom.local`. Inklusive minst en egen instrumenterad
domän-metrik.

**Icke-mål:** Loggar (Loki, Plan 14), tracing (Tempo, Plan 15), Alertmanager-larmregler,
långtidslagring, frontend-metrics.

Detta är första planen i Fas B (observability). Pipeline-konceptet: tjänst exponerar
`/actuator/prometheus` → Prometheus skrapar var ~15:e sekund → Grafana ritar.

---

## 2. Arkitektur-översikt

### 2.1 Tre delar

**Del 1 — Metrics-exponering (app-ändringar)**

- **Parent-POM:** lägg `io.micrometer:micrometer-registry-prometheus` (runtime scope). Delad
  dependency → alla 5 tjänster ärver den. Aktiverar `/actuator/prometheus`-endpointen.
- **Varje tjänsts `application.yml`:** lägg `prometheus` till
  `management.endpoints.web.exposure.include` (idag `health,info`; gateway även `gateway`).
- **Egna domän-metrics:** en Micrometer `Counter`
  - `messages_published_total` i Message Service — ökas efter att ett meddelande
    persisterats och publicerats till RabbitMQ (i publisher-vägen).
  - `bot_replies_total` i Bot Service — ökas efter att boten postat ett svar till Message
    Service (i poster-vägen).
  Liten kodändring i två tjänster (injicera `MeterRegistry`, skapa `Counter` i konstruktorn,
  `increment()` på rätt ställe).

**Del 2 — Monitoring-stacken (kluster-infra)**

- **kube-prometheus-stack** (prometheus-community Helm-chart) som **egen Helm-release** i
  namespace `monitoring` — samma mönster som Traefik (Plan 12). Buntar Prometheus Operator +
  Prometheus + Grafana + Alertmanager + node-exporter + kube-state-metrics + färdiga
  k8s-dashboards. Skript `helm/install-monitoring.sh`.
- **Grafana-ingress** aktiveras i stack-värdena: `grafana.ingress.enabled=true`,
  host `grafana.devroom.local`, `ingressClassName: traefik`. Ingress-objektet hamnar i
  monitoring-namespacet (en Ingress kan bara peka på tjänster i sitt eget namespace, och
  Grafana-servicen ligger där).
- Prometheus konfigureras att plocka upp våra ServiceMonitors oavsett namespace/label:
  `prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false`.

**Del 3 — Wiring (devroom-chartet)**

- En **ServiceMonitor** (`templates/servicemonitor.yaml`, bakom `metrics.enabled`-toggle,
  default `true`) som selekterar de 5 Spring-tjänsterna via en label (t.ex.
  `devroom.io/metrics: "true"`) och skrapar deras `/actuator/prometheus`.
- **Port-namngivning:** en ServiceMonitor refererar Service-porten via *namn*. Vi namnger
  tjänsternas primära HTTP-port `http` i values (user-service har redan det) och lägger en
  `metrics: true`-flagga per Spring-tjänst. app-deployment/app-service-mallarna utökas att
  rendera port-namnet + en `devroom.io/metrics`-label när flaggan är satt. Frontend och
  dev-mentor får INTE flaggan (ingen Actuator-prometheus där).
- **Dashboards** provisioneras som ConfigMaps med label `grafana_dashboard: "1"` som Grafanas
  sidecar auto-laddar: (a) en färdig community-JVM/Spring-Boot-dashboard, (b) en liten egen
  dashboard/panel för `messages_published_total` / `bot_replies_total`.

### 2.2 Dataflöde

1. Message Service postar ett meddelande → `messages_published_total`-räknaren ökar.
2. `/actuator/prometheus` på message-service exponerar räknaren + JVM/HTTP/RabbitMQ-metrics.
3. ServiceMonitor talar om för Prometheus att skrapa message-service:s `http`-port,
   path `/actuator/prometheus`, var 15:e sekund.
4. Prometheus lagrar tidsserierna.
5. Grafana frågar Prometheus och ritar panelerna; nås på `grafana.devroom.local`.

### 2.3 Åtkomst

`grafana.devroom.local` läggs till `/etc/hosts` (browser) och CoreDNS-rewriten (vi utökar
`helm/configure-dns.sh` med ett tredje hostnamn — symmetri, även om ingen pod anropar Grafana).
Grafana-login: `admin` + lösenord satt i stack-värdena.

### 2.4 Drift

kube-prometheus-stack drar ~1.5–2.5Gi RAM. Vi höjer Minikube till `--memory=8192` (från 6144).
Dokumenteras i deploy-stegen.

### 2.5 Låsta designval (från brainstorming 2026-06-24)

| Val | Beslut | Motivering |
|---|---|---|
| Install-approach | kube-prometheus-stack (Operator + ServiceMonitors) | Industristandard, välkänt |
| Grafana-åtkomst | Via Traefik ingress (`grafana.devroom.local`) | Återanvänder Plan 12, sammanhållen story |
| Metrics-scope | Automatiska + egna domän-counters | "Egna affärsmått"-portföljpoäng |
| Placering | Egen release i `monitoring`-namespace | Samma mönster som Traefik |
| Scraping | En delad ServiceMonitor som selekterar de 5 Spring-tjänsterna | DRY; exkluderar frontend/dev-mentor |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `pom.xml` (parent) | Lägg `micrometer-registry-prometheus` |
| `services/*/src/main/resources/application.yml` (×5) | Lägg `prometheus` till exposure |
| `services/message-service/.../` (publisher) | `messages_published_total` Counter |
| `services/bot-service/.../` (poster) | `bot_replies_total` Counter |
| `helm/install-monitoring.sh` | Installera kube-prometheus-stack + Grafana-ingress |
| `helm/devroom/templates/servicemonitor.yaml` | ServiceMonitor för Spring-tjänsterna (toggle) |
| `helm/devroom/templates/app-service.yaml` | `devroom.io/metrics`-label på Service när `metrics: true` (ServiceMonitor selekterar Services) |
| `helm/devroom/values.yaml` | `metrics`-flagga + namngiven `http`-port per Spring-tjänst + `metrics.enabled` |
| `helm/devroom/templates/dashboards/*` (ConfigMaps) | JVM/Spring + egen domän-dashboard |
| `helm/configure-dns.sh` | Lägg `grafana.devroom.local` i CoreDNS-rewriten |
| `docs/adr/0012-kube-prometheus-stack.md` | ADR |

---

## 4. ADR-0012

**kube-prometheus-stack + ServiceMonitor-baserad scraping.** Övervägda alternativ:
lättvikts-standalone Prometheus + Grafana (enklare, lägre RAM, men ingen Operator/ServiceMonitor
— mindre branschstandard), och Grafana Cloud/hosted (avvisat — vi vill köra lokalt).
ServiceMonitor-CRD valt över scrape-annotationer för att matcha Operator-mönstret.

---

## 5. Verifiering

- **Statiskt:** `helm lint`/`template` renderar ServiceMonitor + dashboards; `mvn verify` grön
  efter dependency + config + counter-ändringar.
- **Metrics-exponering:** `curl .../actuator/prometheus` på en tjänst returnerar metrics inkl.
  `messages_published_total`.
- **Scraping:** Prometheus *Targets*-vyn visar de 5 tjänsterna som `UP`.
- **Grafana:** dashboarden visar JVM/HTTP-metrics, och de egna räknarna ökar synligt i en panel
  när man postar ett meddelande (via API eller browser).

---

## 6. Out of scope (explicit)

- Loggar/Loki — Plan 14.
- Tracing/OpenTelemetry/Tempo — Plan 15.
- Alertmanager-larmregler + notifikationer.
- Långtidslagring/remote-write av metrics (Thanos/Mimir).
- Frontend-metrics (Next.js — ingen Micrometer).
- Custom metrics utöver de två räknarna (kan utökas senare).
