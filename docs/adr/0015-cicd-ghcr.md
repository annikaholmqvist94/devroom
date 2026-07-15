# ADR-0015: CI/CD — bygg + publicera images till GHCR

**Status:** Accepted
**Date:** 2026-07-15

## Context

Fas C börjar med CI/CD. Devroom hade CI (mvn verify + helm lint/template) men ingen
CD — images byggdes bara lokalt. Vi vill att pipelinen publicerar container-images.
GitHub Actions-runners kan inte nå den lokala Minikuben, så CI kan inte deploya lokalt.

## Decision

Lägg ett `images`-matrisjobb i `ci.yml` som bygger de 6 Devroom-imagesarna och pushar
till **GHCR** (`ghcr.io/annikaholmqvist94/<tjänst>`) med SHA- + `latest`-taggar, bara på
push till `main` och först efter att `build` + `helm`-jobben passerat (`needs`). Auth via
inbyggda `GITHUB_TOKEN` (`packages: write`). En `values-ghcr.yaml` wire:ar chartet att dra
images från GHCR. Kluster-deploy hör till Fas D (EKS, där GitHub kan nå klustret).

## Considered alternatives

### 1. Docker Hub
Kräver externa secrets och ger ingen fördel över GHCR (som är gratis + GITHUB_TOKEN).

### 2. Self-hosted runner på laptopen
Skulle låta GitHub nå Minikube, men mer drift och säkerhetsyta. Avvisat.

### 3. GitOps / ArgoCD (pull-modell)
Löser "GitHub når inte klustret" genom att ArgoCD i klustret drar från git + GHCR.
Kraftfullt men mer komplexitet; uppskjutet som egen framtida plan för att hålla Plan 16
fokuserad.

## Consequences

- PR:er kör `build` + `helm` (test/lint) men publicerar inga images; bara mergad kod
  (push till main) publiceras, och först efter gröna build/helm-jobb.
- Chartet får en andra values-fil: lokal (`devroom`/`Never`) vs GHCR
  (`ghcr.io/...`/`IfNotPresent`) — inga mall-ändringar (samma portabilitet som Fas D).
- **dev-mentor** (externt repo) publiceras inte av denna CD; en ren GHCR-deploy måste
  förse dess image separat.
- Verifierbart LIVE på GitHub (till skillnad från Plan 13–15): imagesarna dyker upp under
  repots Packages efter merge till main.
