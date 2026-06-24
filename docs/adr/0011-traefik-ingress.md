# ADR-0011: Traefik ingress + CoreDNS split-horizon

**Status:** Accepted
**Date:** 2026-06-24

## Context

ADR-0009 valde `kubectl port-forward` för Plan 10 och sköt upp ingress-frågan.
Det gör browser-login krångligt: OAuth2-issuern (`AUTH_ISSUER_URI`) är både
`iss`-claim (intern DNS) och browser-redirect-mål (måste vara browser-nåbar).
Idag krävs `/etc/hosts auth-service→127.0.0.1` + tre port-forwards; NodePort
bröt issuern. Vi vill ha en enhetlig entry-URL och rent browser-login.

## Decision

Installera **Traefik** som egen Helm-release och exponera Devroom via två
hostnamn — `devroom.local` (frontend + gateway) och `auth.devroom.local`
(auth-server). Använda **standard Kubernetes `Ingress`** (inte Traefiks
`IngressRoute`-CRD). Lösa issuern med **CoreDNS split-horizon**: en rewrite gör
att poddar resolvar samma hostnamn → Traefik internt, så issuern blir EN
konsekvent URL inifrån och utifrån.

## Considered alternatives

### 1. nginx-ingress
Vanligast i fältet. Avvisat till förmån för Traefik: enklare Helm-install,
CRD-ekosystem värt att utforska, och bra dokumentation. Ingress-objekten är
ändå controller-agnostiska.

### 2. Traefik IngressRoute-CRD
Mer funktioner (middleware, viktning). Avvisat: ej portabelt — samma manifest
ska fungera med AWS ALB i Fas D. Standard `Ingress` flyttar med (andra
annoteringar via `ingress.className`).

### 3. Frikopplade OAuth-URI:er (intern JWKS, extern issuer)
Undviker CoreDNS-ändring men kräver explicit split-URI-konfig i 3–4 tjänster.
Avvisat: mer app-konfig att hålla rätt; CoreDNS-vägen håller issuern som EN URL.

### 4. Behålla port-forward
Avvisat — målet är just att bli av med det.

## Consequences

- `helm/setup-ingress.sh` (Traefik + CoreDNS) körs en gång per kluster;
  `/etc/hosts` + `minikube tunnel` är manuella engångssteg (sudo).
- Issuern är `http://auth.devroom.local` överallt; app-konfig (redirect-uris,
  frontend-URL, CORS) parametriserad via env.
- Tjänsternas startberoende på issuern går nu via Traefik + CoreDNS — readiness
  + self-healing (ADR-0010/Plan 11) hanterar uppstartsordningen.
- `port-forward`-instruktionerna behålls som fallback i NOTES.txt; README pekar
  på ingress som primär väg.
- Ersätter ADR-0009 som primär access-strategi lokalt; ADR-0009:s
  Minikube-driver-val gäller fortfarande.
