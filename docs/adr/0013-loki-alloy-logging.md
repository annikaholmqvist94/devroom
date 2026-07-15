# ADR-0013: Loki + Grafana Alloy + strukturerad JSON-loggning

**Status:** Accepted
**Date:** 2026-06-27

## Context

Fas B fortsätter med loggar. Tjänsterna loggar plain text till stdout, spridda per
pod (bara `kubectl logs`). Vi behöver aggregera loggar och göra dem sökbara,
helst korrelerbara med metrics i samma Grafana.

## Decision

Tjänsterna loggar **strukturerad JSON** (Spring Boot 4:s inbyggda
`logging.structured.format.console=ecs`). **Grafana Alloy** (DaemonSet) samlar
container-loggarna och pushar till **Loki** (single-binary). En Loki-datakälla
provisioneras i den befintliga Grafanan; loggar frågas med LogQL i Explore och
visas i en panel bredvid metrics.

## Considered alternatives

### 1. Promtail som collector
Lokis klassiska agent. Avvisad: deprecat:erad till förmån för Alloy (EOL ~2026).
Alloy är dessutom en enhetlig agent som återanvänds för traces i Plan 15.

### 2. ELK (Elasticsearch + Kibana)
Kraftfullt och vanligt i jobbannonser, men tungt (Elasticsearch-RAM) och ett eget
UI-ekosystem. Avvisat till förmån för Grafana-stacken: en UI för metrics + loggar +
traces. (Kan läggas till separat senare om Kibana-erfarenhet specifikt önskas.)

### 3. Plain-text-loggar
Enklast (ingen app-ändring), men ingen fält-frågning. Avvisat för JSON som låter
Loki filtrera på `log_level` m.m.

## Consequences

- Loki + Alloy som egna releaser i `monitoring`; `install-logging.sh` körs efter
  `install-monitoring.sh` (Plan 13) och före devroom-deployen.
- stdout blir JSON (mindre läsbart lokalt i `docker logs`/`mvn`); priset för
  maskin-frågbara loggar.
- Loki single-binary + filsystem räcker lokalt; objektlagring/retention är
  out-of-scope.
- Tracing (Tempo) i Plan 15 återanvänder Alloy som collector.
