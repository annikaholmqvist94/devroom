# ADR-0014: Tempo + Micrometer Tracing (distribuerad tracing)

**Status:** Accepted
**Date:** 2026-07-15

## Context

Fas B avslutas med tracing. Metrics och loggar finns i Grafana, men inget sätt att
följa ett request end-to-end genom HTTP, gRPC och RabbitMQ. Ingen tracing fanns.

## Decision

Instrumentera de 5 Spring-tjänsterna med **Micrometer Tracing** (Observation-API),
exportera **OTLP** till **Grafana Alloy** (enhetlig collector från Plan 14) som
vidarebefordrar till **Tempo** (single-binary). En Tempo-datakälla med
trace→logg-länkning provisioneras i Grafana. Full cross-transport (HTTP auto,
RabbitMQ via observation-enabled, gRPC via Micrometer-interceptors), 100 % sampling.

## Considered alternatives

### 1. Direkt till Tempo (ingen Alloy)
Enklast, men två olika vägar (Alloy för loggar, direkt för traces). Avvisat till
förmån för en enhetlig collector.

### 2. Jaeger som backend
Mycket använt, men eget UI. Avvisat för Grafana-stack-konsistens (metrics + loggar +
traces i samma UI, med korslänkning).

### 3. HTTP-only-instrumentering
Lägre risk men bryter kedjan vid gRPC/RabbitMQ. Avvisat — poängen är den
sammanhängande cross-transport-tracen.

## Consequences

- Tempo som egen release i `monitoring`; Alloy utökad med OTLP-receiver → Tempo.
  `install-logging.sh` installerar nu Loki + Alloy + Tempo.
- 100 % sampling passar demo; produktions-sampling är out-of-scope.
- **gRPC-observation auto-konfigureras av Spring Boot 4** (`GrpcServerObservationAutoConfiguration`
  + motsvarande klient-autoconfig) när tracing-bryggan finns — inga manuella interceptorer
  behövs. Plan 15:s manuella `GrpcServerConfig`/klient-interceptorer var onödiga och
  server-beanen krockade dessutom med Boots auto-registrerade bean
  (`BeanDefinitionOverrideException` → user-service startade inte), fångat av CI på GitHub
  och rättat i `fix/plan-15-grpc-observation` (koden borttagen; Boot sköter propageringen).
- Fas B (observability) komplett: metrics (Plan 13) + loggar (Plan 14) + traces
  (Plan 15), alla i Grafana med korslänkning.
