# Plan 12 — Traefik ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `kubectl port-forward` with a Traefik ingress controller routing the whole stack via two hostnames (`devroom.local` + `auth.devroom.local`), so browser login works without per-service port-forwards.

**Architecture:** Traefik installed as its own Helm release. The devroom chart gets standard Kubernetes `Ingress` resources (behind `ingress.enabled`). A CoreDNS rewrite makes the ingress hostnames resolve to Traefik from inside the cluster too, so the OAuth issuer (`http://auth.devroom.local`) is one consistent URL for both browser and pods. Small config changes parameterize the previously-hardcoded localhost URLs.

**Tech Stack:** Traefik (Helm chart 41.0.0, app v3.7.5), Kubernetes Ingress, CoreDNS, Spring Security (gateway + auth-service), Next.js (frontend).

**Spec:** `docs/superpowers/specs/2026-06-24-plan-12-traefik-ingress-design.md`

**Branch:** `plan-12-traefik-ingress` (already created; spec already committed).

**Prerequisites:**
- Plan 11 merged (Helm chart exists at `helm/devroom`).
- `helm`, `kubectl`, `minikube`, `docker` installed. Minikube only needed for Task 8.

---

## File Structure

| File | Responsibility |
|---|---|
| `helm/devroom/templates/ingress.yaml` | Two `Ingress` objects (devroom.local + auth.devroom.local), behind `ingress.enabled` |
| `helm/devroom/values.yaml` | New `ingress.*` block + updated service env (issuer/frontend/redirect URLs) |
| `services/auth-service/.../application.yml` | Env-drive `redirect-uris` / `post-logout-redirect-uris` |
| `services/gateway/.../config/SecurityConfig.java` | Env-drive the frontend URL (CORS + 2 redirects) |
| `frontend/lib/api.ts` | Default gateway URL to `""` → relative, same-origin calls |
| `helm/install-traefik.sh` | Install Traefik as its own Helm release |
| `helm/configure-dns.sh` | Patch CoreDNS so pods resolve the ingress hostnames to Traefik |
| `helm/setup-ingress.sh` | One-time orchestrator: Traefik + DNS + hosts/tunnel instructions |
| `docs/adr/0011-traefik-ingress.md` | ADR |

---

## Task 1: Ingress template + values block

**Files:**
- Create: `helm/devroom/templates/ingress.yaml`
- Modify: `helm/devroom/values.yaml`

- [ ] **Step 1: Add an `ingress:` block to `helm/devroom/values.yaml`** (append at end of file)

```yaml
ingress:
  enabled: true
  className: traefik
  host: devroom.local
  authHost: auth.devroom.local
```

- [ ] **Step 2: Create `helm/devroom/templates/ingress.yaml`**

```
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: devroom
  labels:
    {{- include "devroom.labels" . | nindent 4 }}
spec:
  ingressClassName: {{ .Values.ingress.className }}
  rules:
  - host: {{ .Values.ingress.host }}
    http:
      paths:
      - path: /api
        pathType: Prefix
        backend:
          service:
            name: gateway
            port:
              number: 8080
      - path: /oauth2/authorization
        pathType: Prefix
        backend:
          service:
            name: gateway
            port:
              number: 8080
      - path: /login
        pathType: Prefix
        backend:
          service:
            name: gateway
            port:
              number: 8080
      - path: /logout
        pathType: Prefix
        backend:
          service:
            name: gateway
            port:
              number: 8080
      - path: /signup
        pathType: Prefix
        backend:
          service:
            name: gateway
            port:
              number: 8080
      - path: /
        pathType: Prefix
        backend:
          service:
            name: frontend
            port:
              number: 3000
  - host: {{ .Values.ingress.authHost }}
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: auth-service
            port:
              number: 8081
{{- end }}
```

- [ ] **Step 3: Verify the Ingress renders with both hosts**

Run: `helm template devroom helm/devroom --show-only templates/ingress.yaml | grep -E "host:|name: (gateway|frontend|auth-service)"`
Expected: `host: devroom.local`, `host: auth.devroom.local`, and the three backend service names appear.

- [ ] **Step 4: Verify the toggle disables it**

Run: `helm template devroom helm/devroom --set ingress.enabled=false | grep -c "kind: Ingress" || echo 0`
Expected: `0`

- [ ] **Step 5: Lint**

Run: `helm lint helm/devroom`
Expected: `0 chart(s) failed`

- [ ] **Step 6: Commit**

```bash
git add helm/devroom/templates/ingress.yaml helm/devroom/values.yaml
git commit -m "feat(plan-12): add Ingress template for devroom.local + auth.devroom.local"
```

---

## Task 2: Auth-service — env-drive redirect URIs

The auth-server's registered-client `redirect-uris` and `post-logout-redirect-uris` are hardcoded to `localhost`. Parameterize them so ingress can supply the real hostnames, keeping the old values as defaults (local dev unchanged).

**Files:**
- Modify: `services/auth-service/src/main/resources/application.yml`

- [ ] **Step 1: Replace the hardcoded redirect URIs**

Find:

```yaml
              redirect-uris:
                - "http://localhost:8080/login/oauth2/code/auth-service"
              post-logout-redirect-uris:
                - "http://localhost:3000/"
```

Replace with:

```yaml
              redirect-uris:
                - "${GATEWAY_REDIRECT_URI:http://localhost:8080/login/oauth2/code/auth-service}"
              post-logout-redirect-uris:
                - "${FRONTEND_REDIRECT_URI:http://localhost:3000/}"
```

- [ ] **Step 2: Verify the YAML is still valid and the placeholders are present**

Run: `python3 -c "import yaml; yaml.safe_load(open('services/auth-service/src/main/resources/application.yml')); print('valid yaml')"`
Expected: `valid yaml`

Run: `grep -E "GATEWAY_REDIRECT_URI|FRONTEND_REDIRECT_URI" services/auth-service/src/main/resources/application.yml`
Expected: both placeholders appear.

- [ ] **Step 3: Verify the app still boots with defaults (existing tests)**

Run: `mvn -q -pl services/auth-service verify`
Expected: BUILD SUCCESS (the 5 Testcontainers tests boot the app with default values — proves the placeholder syntax is valid and defaults match the old behavior).

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/src/main/resources/application.yml
git commit -m "feat(plan-12): env-drive auth-service redirect URIs for ingress hostnames"
```

---

## Task 3: Gateway — env-drive the frontend URL

`SecurityConfig.java` hardcodes `http://localhost:3000` in three places (logout target, post-login success URL, CORS origin). Replace all three with a single injected property `gateway.frontend-url` (env `GATEWAY_FRONTEND_URL`), defaulting to the current value.

**Files:**
- Modify: `services/gateway/src/main/java/com/devroom/gateway/config/SecurityConfig.java`

- [ ] **Step 1: Add the `@Value` import**

Find:

```java
import org.springframework.context.annotation.Bean;
```

Add directly above it:

```java
import org.springframework.beans.factory.annotation.Value;
```

- [ ] **Step 2: Add the injected field** (directly after the class declaration `public class SecurityConfig {`)

```java
    @Value("${gateway.frontend-url:http://localhost:3000}")
    private String frontendUrl;
```

- [ ] **Step 3: Replace the logout target** (line ~33)

Find:

```java
        logoutHandler.setDefaultTargetUrl("http://localhost:3000");
```

Replace with:

```java
        logoutHandler.setDefaultTargetUrl(frontendUrl + "/");
```

- [ ] **Step 4: Replace the post-login success URL** (line ~55)

Find:

```java
            .oauth2Login(login -> login.defaultSuccessUrl("http://localhost:3000/", true))
```

Replace with:

```java
            .oauth2Login(login -> login.defaultSuccessUrl(frontendUrl + "/", true))
```

- [ ] **Step 5: Replace the CORS allowed origin** (line ~71)

Find:

```java
        config.setAllowedOrigins(List.of("http://localhost:3000"));
```

Replace with:

```java
        config.setAllowedOrigins(List.of(frontendUrl));
```

- [ ] **Step 6: Verify it compiles and the gateway tests pass**

Run: `mvn -q -pl services/gateway verify`
Expected: BUILD SUCCESS (gateway integration tests boot the security context with the default frontend URL).

- [ ] **Step 7: Commit**

```bash
git add services/gateway/src/main/java/com/devroom/gateway/config/SecurityConfig.java
git commit -m "feat(plan-12): env-drive gateway frontend URL (CORS + redirects)"
```

---

## Task 4: Frontend — relative same-origin URLs

With frontend and gateway behind the same host (`devroom.local`), API calls should be relative. All four call sites reference `GATEWAY_URL` from `lib/api.ts`, so changing its default to `""` makes every call relative at once. The env var stays overridable for split-origin local dev (`npm run dev` against a port-forwarded gateway).

**Files:**
- Modify: `frontend/lib/api.ts`

- [ ] **Step 1: Change the default to empty string**

Find:

```typescript
export const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ?? "http://localhost:8080";
```

Replace with:

```typescript
// Default "" → relative same-origin calls (frontend + gateway share the
// ingress host). Override with NEXT_PUBLIC_GATEWAY_URL for split-origin
// local dev (npm run dev against a port-forwarded gateway on :8080).
export const GATEWAY_URL =
  process.env.NEXT_PUBLIC_GATEWAY_URL ?? "";
```

- [ ] **Step 2: Verify the build succeeds with relative URLs baked in**

Run: `cd frontend && npm run build && cd ..`
Expected: build succeeds (`✓ Compiled`).

- [ ] **Step 3: Verify no absolute localhost gateway URL is baked into the build output**

Run: `grep -rl "http://localhost:8080" frontend/.next 2>/dev/null | head || echo "none"`
Expected: `none` (the old absolute URL is gone; calls are now relative).

- [ ] **Step 4: Commit**

```bash
git add frontend/lib/api.ts
git commit -m "feat(plan-12): default frontend to relative same-origin gateway calls"
```

---

## Task 5: Wire the ingress hostnames into values

Point every issuer-facing env var at `http://auth.devroom.local`, give the gateway the frontend URL + auth redirect envs to the auth-service, and drop the build-time-only `NEXT_PUBLIC_GATEWAY_URL` from the frontend. Downstream service-to-service URLs (gateway→user/message, bot→message/dev-mentor) stay on internal cluster DNS — they don't go through ingress.

**Files:**
- Modify: `helm/devroom/values.yaml`

- [ ] **Step 1: Update `auth-service` env** — change `AUTH_ISSUER_URI` and add two redirect envs:

```yaml
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://auth-db:5432/authdb
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_ISSUER_URI: http://auth.devroom.local
      GATEWAY_REDIRECT_URI: http://devroom.local/login/oauth2/code/auth-service
      FRONTEND_REDIRECT_URI: http://devroom.local/
```

- [ ] **Step 2: Update `user-service` env** — change `AUTH_ISSUER_URI`:

```yaml
      AUTH_ISSUER_URI: http://auth.devroom.local
```

- [ ] **Step 3: Update `message-service` env** — change the two auth URLs (leave `USER_SERVICE_GRPC` internal):

```yaml
      AUTH_SERVICE_ISSUER: http://auth.devroom.local
      AUTH_SERVICE_JWKS_URI: http://auth.devroom.local/oauth2/jwks
```

- [ ] **Step 4: Update `gateway` env** — change `AUTH_SERVICE_ISSUER` and add `GATEWAY_FRONTEND_URL` (leave `USER_SERVICE_URI`/`MESSAGE_SERVICE_URI`/`AUTH_SERVICE_URI` internal):

```yaml
      AUTH_SERVICE_ISSUER: http://auth.devroom.local
      GATEWAY_FRONTEND_URL: http://devroom.local
```

- [ ] **Step 5: Update `bot-service` env** — change `AUTH_SERVICE_ISSUER`:

```yaml
      AUTH_SERVICE_ISSUER: http://auth.devroom.local
```

- [ ] **Step 6: Remove the build-time-only `NEXT_PUBLIC_GATEWAY_URL` from `frontend` env**

Find:

```yaml
    env:
      NEXT_PUBLIC_GATEWAY_URL: http://localhost:8080
      HOSTNAME: "0.0.0.0"
      PORT: "3000"
```

Replace with (drop the first line — it's inlined at build time, so a runtime value has no effect; relative URLs are used now):

```yaml
    env:
      HOSTNAME: "0.0.0.0"
      PORT: "3000"
```

- [ ] **Step 7: Verify the issuer is consistent everywhere and renders**

Run: `helm template devroom helm/devroom | grep -c "auth.devroom.local"`
Expected: `6` — auth-service issuer, user-service issuer, message-service issuer + JWKS, gateway issuer, bot-service issuer. (The two redirect envs use `devroom.local`, not `auth.devroom.local`, so they don't count here.)

Run: `helm template devroom helm/devroom | grep -E "GATEWAY_FRONTEND_URL|GATEWAY_REDIRECT_URI|FRONTEND_REDIRECT_URI"`
Expected: all three appear with `devroom.local` values.

Run: `helm lint helm/devroom`
Expected: `0 chart(s) failed`

- [ ] **Step 8: Commit**

```bash
git add helm/devroom/values.yaml
git commit -m "feat(plan-12): point issuer/frontend/redirect env at ingress hostnames"
```

---

## Task 6: Ingress infrastructure scripts

Three scripts: install Traefik (own release), patch CoreDNS (split-horizon), and an orchestrator. These run against a live cluster (used in Task 8); here we only author and syntax-check them.

**Files:**
- Create: `helm/install-traefik.sh`
- Create: `helm/configure-dns.sh`
- Create: `helm/setup-ingress.sh`

- [ ] **Step 1: Create `helm/install-traefik.sh`**

```bash
#!/usr/bin/env bash
# Installs Traefik as its own Helm release (cluster ingress controller).
set -euo pipefail

echo "==> Adding Traefik Helm repo"
helm repo add traefik https://traefik.github.io/charts >/dev/null 2>&1 || true
helm repo update traefik >/dev/null

echo "==> Installing/upgrading Traefik (chart 41.0.0)"
helm upgrade --install traefik traefik/traefik \
  --version 41.0.0 \
  -n traefik --create-namespace \
  --set ingressClass.enabled=true \
  --set ingressClass.isDefaultClass=true

echo "==> Waiting for Traefik to be ready"
kubectl -n traefik rollout status deployment traefik --timeout=120s
kubectl get svc -n traefik traefik
```

- [ ] **Step 2: Create `helm/configure-dns.sh`**

```bash
#!/usr/bin/env bash
# Split-horizon DNS: make in-cluster pods resolve the ingress hostnames to
# Traefik, so the OAuth issuer (http://auth.devroom.local) is the same URL
# inside and outside the cluster. Adds CoreDNS rewrite rules.
set -euo pipefail

HOST1=devroom.local
HOST2=auth.devroom.local
TARGET=traefik.traefik.svc.cluster.local

if kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}' | grep -q "rewrite name ${HOST1}"; then
  echo "==> CoreDNS already patched"
else
  echo "==> Patching CoreDNS: rewrite ${HOST1}/${HOST2} -> ${TARGET}"
  kubectl get configmap coredns -n kube-system -o json > /tmp/coredns.json
  python3 - "$HOST1" "$HOST2" "$TARGET" <<'PY' > /tmp/coredns-patched.json
import json, sys
host1, host2, target = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open('/tmp/coredns.json'))
cf = d['data']['Corefile']
inject = f'    rewrite name {host1} {target}\n    rewrite name {host2} {target}\n'
lines = cf.splitlines(keepends=True)
out, done = [], False
for ln in lines:
    out.append(ln)
    if not done and ln.strip() == 'ready':
        out.append(inject); done = True
if not done:                       # fallback: insert before first 'forward'
    out, done = [], False
    for ln in lines:
        if not done and ln.strip().startswith('forward'):
            out.append(inject); done = True
        out.append(ln)
d['data']['Corefile'] = ''.join(out)
json.dump(d, open('/tmp/coredns-patched.json', 'w'))
PY
  kubectl apply -f /tmp/coredns-patched.json
  kubectl -n kube-system rollout restart deployment coredns
  kubectl -n kube-system rollout status deployment coredns --timeout=60s
fi
```

- [ ] **Step 3: Create `helm/setup-ingress.sh`**

```bash
#!/usr/bin/env bash
# One-time ingress setup: Traefik + CoreDNS. Run once per cluster, BEFORE
# deploying the app — so app pods resolve the issuer hostname from first boot.
set -euo pipefail
cd "$(dirname "$0")"

bash install-traefik.sh
bash configure-dns.sh

cat <<'EOF'

============================================================
Ingress installed. Two manual steps remain (need sudo):

1. Map the hostnames to localhost in /etc/hosts:
     echo "127.0.0.1 devroom.local auth.devroom.local" | sudo tee -a /etc/hosts

2. Start the Minikube tunnel (keep it running in its own terminal):
     minikube tunnel

Then open http://devroom.local in a browser.
============================================================
EOF
```

- [ ] **Step 4: Make executable and syntax-check all three**

Run: `chmod +x helm/install-traefik.sh helm/configure-dns.sh helm/setup-ingress.sh && for s in install-traefik configure-dns setup-ingress; do bash -n helm/$s.sh && echo "$s OK"; done`
Expected: `install-traefik OK`, `configure-dns OK`, `setup-ingress OK`.

- [ ] **Step 5: Commit**

```bash
git add helm/install-traefik.sh helm/configure-dns.sh helm/setup-ingress.sh
git commit -m "feat(plan-12): Traefik install + CoreDNS split-horizon scripts"
```

---

## Task 7: ADR-0011

**Files:**
- Create: `docs/adr/0011-traefik-ingress.md`

- [ ] **Step 1: Create `docs/adr/0011-traefik-ingress.md`**

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0011-traefik-ingress.md
git commit -m "docs(plan-12): ADR-0011 Traefik ingress + CoreDNS split-horizon"
```

---

## Task 8: End-to-end on Minikube (incl. browser login)

Live verification. Requires Minikube. This is the real proof: browser login through one URL, no port-forwards.

**Files:** none (verification only)

- [ ] **Step 1: Ensure Minikube is running**

Run: `minikube status || minikube start --driver=docker --memory=6144 --cpus=4`
Expected: `host: Running`.

- [ ] **Step 2: Set up ingress FIRST (Traefik + CoreDNS)**

Run: `bash helm/setup-ingress.sh`
Expected: Traefik deployment ready, CoreDNS patched and restarted, hosts/tunnel instructions printed.

Why first: app pods do OAuth discovery against `http://auth.devroom.local` at startup. If CoreDNS/Traefik aren't up, `deploy.sh`'s `kubectl wait` would time out. With ingress up first, pods resolve the issuer from first boot (retrying only until auth-service itself is ready — normal self-healing).

- [ ] **Step 3: Deploy the app (rebuilds images with the config changes)**

Run: `OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-}" bash helm/deploy.sh`
Expected: `helm upgrade --install` succeeds; all 8 deployments become available (gateway/user/message/bot may restart a few times until auth-service is ready, then settle).

- [ ] **Step 4: Add the hosts entries and start the tunnel** (manual, need sudo)

Run: `echo "127.0.0.1 devroom.local auth.devroom.local" | sudo tee -a /etc/hosts`
Then, in a separate terminal: `minikube tunnel` (leave running).

- [ ] **Step 5: Smoke — frontend via the ingress host**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://devroom.local/`
Expected: `200`

- [ ] **Step 6: Smoke — auth issuer is the ingress hostname**

Run: `curl -s http://auth.devroom.local/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'`
Expected: `"issuer":"http://auth.devroom.local"`

- [ ] **Step 7: Smoke — a pod resolves the issuer internally (proves CoreDNS split-horizon)**

Run: `kubectl run dnstest --rm --attach --restart=Never --image=curlimages/curl -n devroom -- curl -s -o /dev/null -w "%{http_code}\n" http://auth.devroom.local/oauth2/jwks`
Expected: `200` (a throwaway pod reaches auth via the same hostname the browser uses — resolved by CoreDNS, routed by Traefik. Uses a curl image so it doesn't depend on app images having curl.)

- [ ] **Step 8: The real test — browser login**

Open `http://devroom.local` in a browser → click login → land on `auth.devroom.local`'s login form → sign in → land back on `http://devroom.local/` logged in. No `kubectl port-forward` anywhere.

- [ ] **Step 9: Record the result**

No commit. Note in the PR: frontend 200, issuer `http://auth.devroom.local`, in-pod JWKS 200, browser login works through one URL.

---

## Task 9: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Append a Plan 12 status block to `CLAUDE.md`** (after the Plan 11 block, same Swedish bullet style)

Cover: Traefik own release; standard Ingress behind `ingress.enabled`; two hostnames; CoreDNS split-horizon (rewrite → traefik.traefik.svc); issuer now `http://auth.devroom.local` everywhere; the three app config changes (auth redirect-uris env, gateway frontendUrl, frontend relative URLs); `setup-ingress.sh` + `/etc/hosts` + `minikube tunnel`; ADR-0011; smoke results (frontend 200, issuer, in-pod JWKS 200, browser login). End with **Nästa steg:** merge + Plan 13 (Prometheus/Grafana).

- [ ] **Step 2: Update `README.md`** — add an "Ingress (rekommenderad åtkomst)" subsection after the Helm quickstart:

```markdown
### Ingress med Traefik (rekommenderad åtkomst)

Ersätter port-forward med en riktig ingress (se [ADR-0011](docs/adr/0011-traefik-ingress.md)).

```bash
bash helm/setup-ingress.sh    # FÖRST: installerar Traefik + patchar CoreDNS
bash helm/deploy.sh           # SEN: deploya appen (når issuern från start)

echo "127.0.0.1 devroom.local auth.devroom.local" | sudo tee -a /etc/hosts
minikube tunnel               # eget terminalfönster, kräver sudo

# Öppna http://devroom.local — login fungerar end-to-end utan port-forward
```
```

Add a row to the status table: `| 12 | Traefik ingress (CoreDNS split-horizon + ADR-0011) | 12 | 2026-06-24 |`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs(plan-12): document Traefik ingress and Plan 12 status"
```

---

## Self-Review Notes

- **Spec coverage:** routing table (Task 1 ingress.yaml), Traefik own release (Task 6), standard Ingress + toggle (Task 1), CoreDNS split-horizon (Task 6 configure-dns.sh), app config — auth redirect-uris (Task 2), gateway frontend URL (Task 3), frontend relative URLs (Task 4), issuer/env wiring (Task 5), external access tunnel/hosts (Tasks 6/8), ADR-0011 (Task 7), dataflow + browser test (Task 8), docs (Task 9). All spec sections map to a task.
- **The same-origin nuance** (spec §2.2) is implemented in Task 4 (relative URLs) + Task 5 Step 6 (drop build-time env), with the rebuild covered by deploy.sh in Task 8.
- **Startup ordering:** Task 8 Step 4 explicitly restarts app pods after CoreDNS/Traefik are up — the deeper issuer dependency (through ingress) means some pods restart before the issuer is reachable; self-healing handles it (ADR-0010).
- **Out of scope (spec §6):** TLS/HTTPS, AWS ALB, Traefik middleware, removing port-forward from NOTES.txt.
```
