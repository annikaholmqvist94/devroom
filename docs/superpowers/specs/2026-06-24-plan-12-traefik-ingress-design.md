# Plan 12 — Traefik ingress: Designspecifikation

**Datum:** 2026-06-24
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** A — Mogna lokala Kubernetes (del 2 av 2)

---

## 1. Kontext och mål

Efter Plan 11 deployas Devroom som ett Helm-chart, men åtkomst sker fortfarande via
`kubectl port-forward` (ADR-0009). Det fungerar för en snabb smoke-test men har två
problem för en riktig demo:

- **Browser-login kräver hack.** OAuth2-issuern (`AUTH_ISSUER_URI`) spelar två roller:
  den är `iss`-claimet nedströms-tjänster validerar *och* den URL browsern redirectas
  till vid login. Idag är den `http://auth-service:8081` (intern DNS), så browsern når
  den bara via `/etc/hosts auth-service→127.0.0.1` + tre port-forwards. NodePort bröt
  issuern (intern namn ≠ extern adress).
- **Ingen enhetlig entry-URL.** Varje tjänst kräver en egen port-forward.

**Mål:** Installera en **ingress controller (Traefik)** och route:a hela stacken via två
stabila hostnamn, så att **browser-login fungerar utan port-forwards eller per-tjänst
`/etc/hosts`-hack**. Detta löser issuer-knuten som ADR-0009 medvetet sköt upp.

**Icke-mål:** HTTPS/TLS (egen senare plan), AWS ALB (Fas D), Traefik-middleware
(rate-limiting etc.).

Detta är andra och sista planen i Fas A. Efter den börjar Fas B (observability).

---

## 2. Arkitektur-översikt

### 2.1 Routing-tabell

**`http://devroom.local`** (Host-baserad routing via Traefik):

| Path | → tjänst | Anmärkning |
|---|---|---|
| `/` + `/_next/**` (default) | frontend:3000 | Next.js-appen |
| `/api/**` | gateway:8080 | REST mot user/message via TokenRelay |
| `/oauth2/authorization/**` | gateway:8080 | Startar Authorization Code-flödet |
| `/login/**` | gateway:8080 | OAuth-callback `/login/oauth2/code/auth-service` |
| `/logout` | gateway:8080 | BFF-logout |
| `/signup/**` | gateway:8080 | Proxas vidare till auth (publik) |

**`http://auth.devroom.local`** → auth-service:8081 — hela Spring Authorization Server
(`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/.well-known/openid-configuration`,
samt `/login`-formuläret).

**Varför subdomän för auth:** gateway och auth-server delar path-prefixet `/oauth2/...`
(gateway har `/oauth2/authorization/...` som klient, auth-servern har `/oauth2/authorize`
m.fl. som server) och båda har en `/login`-yta. Subdomänen eliminerar all path-kollision
och låter Spring Authorization Server köra på sin host-rot (där icke-rot-issuer-buggar
undviks).

### 2.2 De fyra rörliga delarna

**1. Traefik som egen Helm-release.** En ingress controller är kluster-infrastruktur,
installeras en gång — inte del av devroom-chartet. Ett skript `helm/install-traefik.sh`
kör `helm repo add traefik ...` + `helm install traefik traefik/traefik -n traefik
--create-namespace`. Traefik exponeras som `LoadBalancer` (nås via `minikube tunnel`).

**2. Standard `Ingress`-resurser i devroom-chartet.** Ny `templates/ingress.yaml` bakom en
`ingress.enabled`-toggle (default `true` lokalt). Vi använder **vanliga Kubernetes
`Ingress`-objekt**, inte Traefiks `IngressRoute`-CRD:er — då fungerar samma manifest med
AWS ALB i Fas D (bara andra annoteringar via `ingress.className` + annotations i values).
Samma portabilitets-princip som ADR-0010.

**3. CoreDNS split-horizon.** Skript `helm/configure-dns.sh` patchar CoreDNS ConfigMap i
`kube-system` med ett `hosts`-block så poddarna resolvar `devroom.local` +
`auth.devroom.local` → Traefiks ClusterIP. Då når gateway (OIDC-discovery) och resource
servers (JWKS) issuern internt med *samma* namn browsern använder. Detta är kärnan i
"ett hostnamn betyder samma sak inuti och utanför klustret".

**4. App-konfig parametriseras (ingen affärslogik ändras).**
- `AUTH_ISSUER_URI` → `http://auth.devroom.local`
- auth-serverns `redirect-uris` / `post-logout-redirect-uris` → env-drivna
  (`http://devroom.local/login/oauth2/code/auth-service` resp. `http://devroom.local/`)
- frontend gateway-URL → `http://devroom.local` (se nyans nedan)
- gateways CORS-`allowedOrigins` → `http://devroom.local`

**Nyans — frontend och same-origin:** `NEXT_PUBLIC_*`-variabler i Next.js bakas in vid
*build-time*, inte runtime — att bara ändra env-värdet i Kubernetes räcker alltså inte
(imagen måste byggas om med rätt värde). Men eftersom frontend och gateway nu delar origin
(`devroom.local`) är den renare lösningen att frontend använder **relativa URL:er** (`/api/...`)
istället för `${GATEWAY}/api/...`. Då blir API-anropen same-origin (ingen CORS behövs ens),
och variabeln blir överflödig. Planen väljer relativa URL:er som primär väg; ombyggnad av
imagen med `NEXT_PUBLIC_GATEWAY_URL=http://devroom.local` är fallback om något anrop måste
vara absolut. Detta är en liten frontend-kodändring utöver auth-serverns redirect-uris.

### 2.3 Extern åtkomst

`minikube tunnel` ger Traefiks `LoadBalancer`-service en adress på `127.0.0.1`. `/etc/hosts`
mappar båda hostnamnen dit:

```
127.0.0.1 devroom.local auth.devroom.local
```

→ rena URL:er utan portnummer. Tunnel + hosts-redigering kräver `sudo` (engångs,
dokumenterat). Internt (poddar → Traefik) går trafiken via ClusterIP:80, oberoende av
tunneln.

### 2.4 Dataflöde: ett browser-login

1. Browser → `http://devroom.local/` → Traefik → frontend.
2. Klick "logga in" → `http://devroom.local/oauth2/authorization/auth-service` → gateway.
3. Gateway redirectar browsern → `http://auth.devroom.local/oauth2/authorize?...` → auth-server.
4. Browser visar auth-serverns `/login`-formulär, postar credentials.
5. Auth-server redirectar → `http://devroom.local/login/oauth2/code/auth-service?code=...`
   → gateway byter code mot token (server-till-server mot `http://auth.devroom.local/oauth2/token`,
   resolvas via CoreDNS internt).
6. Gateway sätter HttpOnly-session-cookie, redirectar → `http://devroom.local/` (inloggad).
7. Resource servers validerar JWT: `iss` = `http://auth.devroom.local`, JWKS hämtas från
   samma host (CoreDNS internt). Allt stämmer eftersom issuern är *en* konsekvent URL.

### 2.5 Låsta designval (från brainstorming 2026-06-24)

| Val | Beslut | Motivering |
|---|---|---|
| Ambition | En URL + rent browser-login, HTTP (ej TLS) | Löser den intressanta knuten utan cert-komplexitet |
| Issuer-resolution | Ett hostnamn + CoreDNS split-horizon | Minimal app-konfig; lär ut ingress + k8s-DNS |
| Hostnamn-layout | Subdomän `auth.devroom.local` | Undviker `/oauth2`- och `/login`-kollision |
| Ingress-resurstyp | Standard `Ingress` (ej `IngressRoute`) | Portabelt till AWS ALB i Fas D |
| Traefik-install | Egen Helm-release | Ingress controller är kluster-infra, installeras en gång |
| Extern åtkomst | `minikube tunnel` → 127.0.0.1 | Rena URL:er utan portnummer |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `helm/install-traefik.sh` | Installerar Traefik som egen Helm-release |
| `helm/configure-dns.sh` | Patchar CoreDNS ConfigMap (split-horizon) |
| `helm/devroom/templates/ingress.yaml` | `Ingress`-objekt för de två hostnamnen (toggle) |
| `helm/devroom/values.yaml` | Nya `ingress.*`-värden (enabled, host, authHost, className) |
| `services/auth-service/.../application.yml` | Env-driva `redirect-uris` / `post-logout` |
| `frontend/` (API-anrop) | Relativa URL:er (`/api/...`) istället för `${GATEWAY}/api/...` |
| `helm/devroom/values.yaml` (services-block) | Uppdaterade env: issuer, CORS-origin |
| `helm/deploy.sh` | Lägg till Traefik- + DNS-stegen (eller dokumentera ordning) |
| `docs/adr/0011-traefik-ingress.md` | ADR |

---

## 4. ADR-0011

**Traefik ingress + standard Ingress + CoreDNS split-horizon.** Avlöser ADR-0009:s
uppskjutna issuer-fråga (port-forward → ingress). Övervägda alternativ: nginx-ingress
(vanligast, men Traefik valt för enklare Helm-install och CRD-ekosystem att utforska),
Traefik `IngressRoute`-CRD (mer funktioner men ej portabelt till ALB), samt att behålla
port-forward (avvisat — målet är just att bli av med det).

---

## 5. Verifiering

- **Statiskt:** `helm lint` + `helm template` renderar `Ingress`-objekten med rätt hostar
  och paths. Toggle `ingress.enabled=false` → inga Ingress-objekt.
- **Infra:** efter `install-traefik.sh` + `configure-dns.sh`, bekräfta Traefik-pod Running
  och CoreDNS-ConfigMap innehåller hosts-blocket.
- **Routing (curl mot Traefik):** `curl -H "Host: devroom.local" http://127.0.0.1/` → 200
  (frontend); `curl http://auth.devroom.local/.well-known/openid-configuration` →
  `"issuer":"http://auth.devroom.local"`.
- **Internt issuer-resolution:** `kubectl exec` i en gateway-pod → `curl
  http://auth.devroom.local/oauth2/jwks` lyckas (bevisar CoreDNS-rewrite).
- **Det riktiga testet:** öppna `http://devroom.local` i browsern → login → auth-serverns
  formulär → inloggad på `http://devroom.local/`, **utan ett enda `port-forward`**.

---

## 6. Out of scope (explicit)

- HTTPS/TLS (cert-hantering) — egen senare plan.
- AWS ALB / cloud-ingress — Fas D.
- Traefik-middleware (rate-limiting, auth-middleware, retries).
- Borttagning av de gamla `port-forward`-instruktionerna ur NOTES.txt — behålls som
  fallback, men README pekar på ingress-vägen som primär.
- Wildcard-cert eller riktig DNS (vi använder `/etc/hosts` lokalt).
