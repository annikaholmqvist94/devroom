# Plan 14 — Loki (loggaggregering): Designspecifikation

**Datum:** 2026-06-24
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** B — Observability (del 2 av 3: metrics → **loggar** → tracing)

---

## 1. Kontext och mål

Efter Plan 13 har Devroom metrics i Grafana, men ingen loggaggregering. Tjänsterna
loggar idag **plain text till stdout** (Spring Boots default; inga logback-konfigfiler,
bara loggnivåer i gateway). Loggarna är spridda per pod och nås bara via `kubectl logs`.

**Mål:** Ge Devroom logg-observability: tjänsterna loggar **strukturerad JSON**, Grafana
Alloy samlar containerloggarna och pushar till **Loki**, och loggarna söks/korreleras i
samma Grafana som metrics. Andra planen i Fas B.

**Icke-mål:** Tracing (Tempo, Plan 15), loggbaserade larm, retention-tuning, audit-loggar,
frontend-loggar.

Pipeline-konceptet: tjänst → stdout (JSON) → Alloy (DaemonSet, läser nodens container-loggar,
klistrar på k8s-labels) → Loki (lagrar, indexerar på labels) → Grafana (frågar via LogQL).

---

## 2. Arkitektur-översikt

### 2.1 Tre delar

**Del 1 — Strukturerad loggning (app-ändring)**

- En property per tjänst: `logging.structured.format.console=ecs` i de 5 Spring-tjänsternas
  `application.yml`. Spring Boot 4:s **inbyggda** strukturerade loggning gör då varje
  stdout-rad till ett ECS-JSON-objekt (Elastic Common Schema: `@timestamp`, `log.level`,
  `log.logger`, `message`, samt MDC-fält). Ingen ny dependency.
- Trade-off: stdout blir JSON (mindre läsbart i `docker logs`/`mvn`-output lokalt). Det är
  priset för maskin-frågbara loggar; de är avsedda för Loki. (Lokal dev kan vid behov
  override:a property:n till tom för plain text.)

**Del 2 — Logg-stacken (kluster-infra)**

- **Loki** (`grafana/loki` Helm-chart) i **single-binary-läge** (`deploymentMode:
  SingleBinary`, en replica, filsystem-lagring). Lagom för Minikube. Egen Helm-release i
  `monitoring`-namespacet (bredvid Prometheus/Grafana från Plan 13).
- **Grafana Alloy** (`grafana/alloy` Helm-chart) som **DaemonSet** — en config som läser
  `/var/log/pods/*`, discoverar pods via Kubernetes-API, klistrar på labels (`namespace`,
  `pod`, `container`, `app`) och pushar till Loki:s push-endpoint
  (`http://loki.monitoring.svc:3100/loki/api/v1/push`).
- Skript `helm/install-logging.sh` installerar båda i `monitoring`-namespacet.

**Del 3 — Grafana-koppling**

- En **Loki-datakälla** provisioneras i den befintliga (kube-prometheus-stack-)Grafanan via
  en **ConfigMap** med label `grafana_datasource: "1"` (Grafanas sidecar plockar upp den),
  pekande på `http://loki.monitoring.svc:3100`. ConfigMappen appliceras av
  `install-logging.sh` i `monitoring`-namespacet — Grafanas *datasource*-sidecar letar bara
  där by default (Plan 13 satte `searchNamespace=ALL` enbart för dashboards, inte datakällor).
  Dashboard-panelen (showpiece) ligger däremot kvar i devroom-chartet eftersom
  dashboard-sidecaren har `searchNamespace=ALL`.
- Loggar frågas i Grafana **Explore** med LogQL, t.ex.
  `{namespace="devroom", app="auth-service"} | json | log_level="ERROR"`.

### 2.2 Dataflöde

1. auth-service loggar en rad → ECS-JSON till stdout.
2. Kubelet skriver den till `/var/log/pods/devroom_auth-service-.../auth-service/0.log`.
3. Alloy (DaemonSet på noden) läser filen, taggar med `namespace=devroom`, `app=auth-service`,
   pushar till Loki.
4. Loki lagrar och indexerar på labels.
5. Grafana frågar Loki; Explore visar loggarna, och man kan tidskorrelera med en metric-panel.

### 2.3 Showpiece

En **logs-panel** läggs till i "Devroom Overview"-dashboarden (Plan 13) — t.ex. senaste
WARN/ERROR-rader över alla devroom-tjänster — så metrics och loggar syns sida vid sida.

### 2.4 Låsta designval (från brainstorming 2026-06-24)

| Val | Beslut | Motivering |
|---|---|---|
| Loggformat | Strukturerad JSON via Boot-property (`ecs`) | Fält-frågbart i Loki; nästan gratis i Boot 4 |
| Collector | Grafana Alloy (DaemonSet) | Framtidssäker (Promtail EOL); återanvänds för Tempo i Plan 15 |
| Loki-läge | Single-binary | Enklast/lättast för Minikube |
| Grafana-datakälla | Sidecar-ConfigMap | Decouplat från Plan 13:s monitoring-install |
| Showpiece | Logs-panel i befintlig dashboard | Metrics + loggar sida vid sida |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `services/*/src/main/resources/application.yml` (×5) | `logging.structured.format.console=ecs` |
| `helm/install-logging.sh` | Installera Loki (single-binary) + Alloy (DaemonSet) |
| `helm/loki-values.yaml` | Loki-chart-värden (single-binary, filesystem) |
| `helm/alloy-values.yaml` | Alloy-config: k8s-pod-loggar → Loki |
| `helm/grafana-loki-datasource.yaml` | Datasource-ConfigMap (appliceras i `monitoring` av install-logging.sh) |
| `helm/devroom/templates/grafana-dashboard.yaml` | Lägg en logs-panel |
| `docs/adr/0013-loki-alloy-logging.md` | ADR |

---

## 4. ADR-0013

**Loki + Grafana Alloy + strukturerad JSON-loggning.** Övervägda alternativ: Promtail
(Lokis klassiska agent — deprecat:erad till förmån för Alloy, avvisad); ELK/Kibana
(Elasticsearch + Kibana — kraftfullt men tungt och ett eget UI-ekosystem, avvisat till
förmån för Grafana-stacken: en UI för metrics + loggar + traces); plain-text-loggar (avvisat
för JSON som ger fält-frågning).

---

## 5. Verifiering

- **Statiskt:** `helm lint`/`template` renderar datasource-ConfigMap + dashboard-panel;
  `mvn -pl <tjänst> verify` grön efter property-ändringen (appen bootar).
- **JSON-loggning:** `kubectl logs` på en tjänst visar giltig ECS-JSON (en rad parsas av
  `python -m json.tool`).
- **Insamling:** Alloy-DaemonSet kör; Loki-pod Running.
- **Grafana:** Explore med datakällan Loki visar loggar för `{namespace="devroom"}`, och en
  fält-fråga (`| json | log_level="ERROR"`) fungerar.

---

## 6. Out of scope (explicit)

- Tracing/OpenTelemetry/Tempo — Plan 15 (Alloy återanvänds då).
- Loggbaserade larm (Loki ruler/Alertmanager).
- Retention/komprimering/objektlagring (single-binary + filsystem räcker lokalt).
- Audit-/säkerhetsloggning, PII-maskering.
- Frontend-loggar (Next.js).
