# ADR-0009: Minikube med port-forward, ingen ingress controller

**Status:** Accepted
**Date:** 2026-05-21

## Context

Plan 10 ska kunna demo:a hela Devroom-stacken på Kubernetes från ett enda kommando. Frontend ska vara nåbar i en browser, RabbitMQ management-UI och Auth Service ska kunna inspekteras. Vi behöver välja en strategi för att exponera tjänster utåt från clustret.

Detta är en lokal demo-uppsättning, inte produktion. Kraven är:

- En kollega ska kunna klona repot och köra demoen utan extra konfiguration utöver Minikube-installation
- Demo-flödet (signup → mention → bot-svar) ska fungera end-to-end
- Inspektionsverktyg (RabbitMQ UI, Minikube dashboard) ska vara tillgängliga
- Setup-tid ska vara minuter, inte timmar

## Decision

Använda **Minikube med Docker driver** och exponera tjänster via **`kubectl port-forward`** istället för att installera en ingress controller eller använda `LoadBalancer`-Services.

Konkret innebär detta:

- `minikube start --driver=docker --memory=6144 --cpus=4` startar klustret
- `bash k8s/deploy.sh` bygger images direkt i Minikubes Docker daemon och applicerar manifesten
- Demoen körs genom att starta `kubectl port-forward` mot frontend, gateway, auth-service och rabbitmq i bakgrunden
- Browser besöker `http://localhost:3000`, RabbitMQ UI på `http://localhost:15672`

## Considered alternatives

### 1. nginx-ingress via Helm

Installera nginx-ingress-controller via `helm install`, definiera `Ingress`-resurser för varje exponerad tjänst, lägg till `127.0.0.1 devroom.local` i `/etc/hosts`.

- **+** Produktionsrealistisk URL-struktur (`devroom.local/api/messages`)
- **+** Ett TLS-cert kan användas för flera tjänster
- **−** Helm-installation + cert-manager-setup = 3-4 timmar
- **−** Användaren måste editera `/etc/hosts` manuellt
- **−** Stor ytterligare yta att underhålla utan motsvarande demo-värde

### 2. LoadBalancer service-typ

Sätta `type: LoadBalancer` på frontend och gateway, köra `minikube tunnel` för att simulera en moln-LB.

- **+** Närmare moln-prod-mönster
- **−** Kräver `sudo` för `minikube tunnel` (binder priviligerade portar)
- **−** Tunneln måste hållas igång i en separat terminalflik
- **−** Funkar inte bra med flera exponerade tjänster

### 3. NodePort

Använda `type: NodePort` så Minikube exponerar tjänsterna på höga portar (30000+) på Minikubes IP.

- **+** Inget ingress eller tunnel behövs
- **−** URL:erna blir `http://$(minikube ip):30080` — fula och inte deterministiska
- **−** Cross-platform-issues (port-mapping fungerar olika på macOS vs Linux)
- **−** Förvirrande tillsammans med `kubectl port-forward`-stegen som ändå finns i README

### 4. Minikube + port-forward (vald)

`kubectl port-forward` skapar en lokal TCP-tunnel från `localhost:PORT` till en specifik tjänst inom clustret.

- **+** Inget extra installeras — `kubectl` har det inbyggt
- **+** URL:erna är alltid `http://localhost:PORT` — förutsägbart och dokumenterat
- **+** Snabbt att starta och stoppa (en `pkill kubectl port-forward` städar)
- **+** Minikube dashboard räcker som visuellt verktyg istället för ingress-baserad routing
- **−** Inga produktionsrealistiska URL:er
- **−** Browser-OAuth-flödet kräver att browserns issuer-URL (`http://localhost:8081`) matchar vad cluster-internt också använder, vilket är en designbegränsning men hanterbar

## Consequences

### Positiva

- En kollega kan demo:a Devroom genom att köra två kommandon: `minikube start ...` och `bash k8s/deploy.sh`. Sedan port-forwarda enligt instruktioner i scriptets slutoutput.
- Demoen är reproducerbar — samma URL:er varje gång, samma steg
- Setup-tid är minuter, inte timmar
- Minikubes inbyggda dashboard (`minikube dashboard`) ger en visuell vy utan ytterligare installation

### Negativa

- Vi har inga produktionsrealistiska URL-strukturer (`devroom.local/api/...`) — det är dokumenterat som future work
- Port-forward-processerna måste hållas igång; om de dör (eller terminalen stängs) måste de startas om
- Om man vill simulera realistic latency mellan Internet och Gateway behöver man en ingress-baserad approach

### Framtida arbete

Vid migration till en riktig molnmiljö (EKS/GKE/AKS) byter vi till en cloud-managed ingress (ALB / GKE Ingress / Application Gateway) och tar bort port-forward-stegen. Manifesten själva ändras inte — bara hur Service:erna exponeras utåt.

## References

- Designspec sektion 11 — Deployment
- Plan 10 task 10 — verifierad deploy med just denna strategi
- [ADR-0007](0007-gateway-webmvc-variant.md) — Gateway WebMVC-variant (samma servlet-konsolideringsspår)
