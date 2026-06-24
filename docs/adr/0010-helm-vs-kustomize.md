# ADR-0010: Helm för paketering och multi-miljö-deploy

**Status:** Accepted
**Date:** 2026-06-14

## Context

Plan 10 deployar Devroom på Minikube via råa `k8s/*.yaml` + `deploy.sh`. Inför
fortsättningen (observability, CI/CD, AWS/EKS) har manifesten två svagheter: de
sju applikationstjänsterna är nästan identiska men beskrivs i separata filer, och
Minikube-specifika värden (`imagePullPolicy: Never`, `devroom/x:latest`) är
hårdkodade så samma manifest inte kan deployas till EKS utan omskrivning.

Vi behöver en paketerings- och miljö-parametriserings-strategi.

## Decision

Använda **Helm 3+** med ett enda chart (`helm/devroom`) som har en generisk
service-mall som loopar över `.Values.services`, samt infra (Postgres + RabbitMQ)
bakom en `infra.enabled`-toggle. Globala värden (`imageRegistry`, `imageTag`,
`imagePullPolicy`) gör att samma chart deployas till både Minikube och EKS genom
att enbart byta values.

## Considered alternatives

### 1. Kustomize (overlays)
Inbyggt i `kubectl`, ingen extra binär. Patchar base-manifest per miljö via
overlays. Men: ingen loop/templating — de sju likadana tjänsterna skulle förbli
sju separata filer, och duplikeringen vi vill bli av med kvarstår.

### 2. Behålla råa manifest + envsubst
Minst nytt att lära. Men ingen release-livscykel, ingen `rollback`/`history`, och
miljö-substitution via `envsubst` blir skört och odokumenterat.

### 3. Helm (vald)
Templating + `values` + release-livscykel (`upgrade`/`rollback`/`history`).
Loopen ger DRY-vinsten, toggeln sätter upp RDS-bytet i Fas D rent, och Helm är
de-facto-standard i jobbannonser. Trade-off: Go-template-syntaxen är mindre läsbar
än rå YAML — accepteras för paketerings- och portabilitetsvinsten.

## Consequences

- `helm upgrade --install` ersätter `kubectl apply`-kedjan; `helm/deploy.sh`
  behåller image-bygget.
- De råa `k8s/*.yaml` behålls tills vidare som referens men är inte längre
  primär deploy-väg; kan tas bort i en senare städning.
- Startordningen (auth-service före övriga) hanteras inte längre av `kubectl wait`
  utan av readiness-probes + app-retry (se spec sektion 4).
- ADR-0009 (Minikube + port-forward) gäller fortfarande; Traefik-ingress kommer i
  Plan 12.
