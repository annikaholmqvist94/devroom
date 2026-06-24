# ADR-0012: kube-prometheus-stack för metrics-observability

**Status:** Accepted
**Date:** 2026-06-24

## Context

Fas B börjar med metrics. Tjänsterna har Actuator men ingen metrics-pipeline.
Vi behöver välja hur Prometheus + Grafana installeras och hur Prometheus hittar
tjänsternas `/actuator/prometheus`-endpoints.

## Decision

Installera **kube-prometheus-stack** (prometheus-community) som egen Helm-release
i namespace `monitoring`. Tjänsterna exponerar metrics via
`micrometer-registry-prometheus`. Prometheus hittar dem via en **ServiceMonitor**
(CRD) i devroom-chartet som selekterar tjänsterna på label `devroom.io/metrics`.
Grafana nås via Traefik-ingress (`grafana.devroom.local`).

## Considered alternatives

### 1. Lättvikts standalone Prometheus + Grafana
En enkel Prometheus-Deployment + scrape-config med pod-annotationer. Lägre RAM,
färre rörliga delar, mer synlig scrape-config. Avvisat: ingen Operator/
ServiceMonitor — kube-prometheus-stack är branschstandarden och termerna
('Prometheus Operator', 'ServiceMonitor') är just det rekryterare frågar om.

### 2. Grafana Cloud / hosted
Avvisat — projektet ska kunna köras helt lokalt.

### 3. Scrape-annotationer istället för ServiceMonitor
Fungerar men matchar inte Operator-mönstret; ServiceMonitor-CRD är det
idiomatiska sättet med kube-prometheus-stack.

## Consequences

- En ny kluster-release (`monitoring`) + ServiceMonitor-CRD; devroom-chartet får
  en ServiceMonitor + en dashboard-ConfigMap bakom `metrics.enabled`.
- Minikube höjs till 8 GB RAM (stacken drar ~1.5–2.5Gi).
- `install-monitoring.sh` körs efter Plan 12:s `setup-ingress.sh` (Grafana-ingress
  kräver Traefik) och före devroom-deployen (ServiceMonitor-CRD måste finnas).
- Egna domän-counters (`messages.published`, `bot.replies`) exponeras som
  `messages_published_total` / `bot_replies_total`.
- Loggar (Loki) och tracing (Tempo) byggs i Plan 14–15 på samma Grafana.
```
