# Plan 11 — Helm-chart: Designspecifikation

**Datum:** 2026-06-14
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** A — Mogna lokala Kubernetes (del 1 av roadmap mot observability, CI/CD och AWS)

---

## 1. Kontext och mål

Devroom kör idag på Kubernetes via **råa YAML-manifest** (`k8s/*.yaml` + `deploy.sh`) på
Minikube (Plan 10, ADR-0009). Manifesten är funktionella men har två svagheter inför den
fortsatta resan mot observability, CI/CD och AWS:

- **Duplicering:** de fem backend-tjänsterna är nästan identiska (Deployment + Service som
  bara skiljer i namn, image, port, env och resources), men beskrivs i separata filer.
  Frontend och dev-mentor är också Deployment + Service och passar samma mall, men med
  per-tjänst-skillnader (frontend saknar Actuator, dev-mentor har egen secret). Totalt sju
  applikationstjänster.
- **Ingen miljö-parametrisering:** `imagePullPolicy: Never` och `devroom/x:latest` är
  hårdkodade Minikube-värden. Samma manifest kan inte deployas till EKS utan att skrivas om.

**Mål:** Paketera manifesten till **ett enda, parametriserat Helm-chart** så att:

1. Hela stacken installeras med ett kommando: `helm upgrade --install`.
2. *Samma chart* senare kan deployas till EKS (Fas D) genom att enbart byta `values` —
   image-registry, pull-policy, in-cluster-infra → managed RDS/Amazon MQ.
3. DRY-templating (en generisk mall för alla tjänster) demonstrerar Helm-skicklighet.

**Icke-mål:** Detta är ren **paketering**. Tjänsternas drift-attribut (readiness/liveness-probes
och `resources` requests/limits) finns redan i manifesten och ändras inte. Ingen ny
funktionalitet, inga nya tjänster.

Detta är den första planen i en roadmap (Plan 11–18+) som leder från lokala k8s →
observability (Grafana-stacken) → CI/CD → AWS/EKS. Se sektion 8.

---

## 2. Arkitektur-översikt

### 2.1 Chart-layout

```
helm/devroom/
  Chart.yaml                  # name: devroom, version, appVersion
  values.yaml                 # dev/minikube-defaults
  templates/
    _helpers.tpl              # namn, labels, selectorLabels-helpers
    app-deployment.yaml       # range över .Values.services → Deployment
    app-service.yaml          # range över .Values.services → Service
    postgres-statefulset.yaml # range över .Values.databases (if infra.enabled)
    postgres-service.yaml     # range över .Values.databases (if infra.enabled)
    rabbitmq.yaml             # engångs-resurs (if infra.enabled)
    secret.yaml               # Secret(s) från .Values.secrets (dev-defaults)
    NOTES.txt                 # post-install: port-forward-kommandon
```

### 2.2 Generisk service-mall (kärnan)

En enda `app-deployment.yaml` itererar `range $name, $svc := .Values.services` och renderar
ett Deployment per applikationstjänst. `app-service.yaml` gör samma för Service-objekten.
Tjänsterna beskrivs deklarativt i `values.yaml`:

```yaml
services:
  auth-service:
    image: auth-service        # kombineras med global.imageRegistry → devroom/auth-service
    tag: latest                # default; override med global.imageTag
    port: 8081
    env:                       # plain key/value
      AUTH_ISSUER_URI: http://auth-service:8081
    secretEnv:                 # secretKeyRef-injektioner
      - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: db-credentials, key: password }
    resources:
      requests: { memory: 512Mi, cpu: 200m }
      limits:   { memory: 1Gi,   cpu: 1 }
```

Mallen itererar över **både** `env` (plain) och `secretEnv` (secretKeyRef). Detta är chartets
enda reella komplexitet — env-formerna skiljer mest mellan tjänster (gateway har
routing-URI:er, auth har klient-secrets, frontend har en publik gateway-URL).

Probes härleds från `port` + standardpath `/actuator/health/{readiness,liveness}`. De fem
backend-tjänsterna och dev-mentor har alla Actuator och passar defaulten. Frontend (Next.js)
saknar Actuator — den får en enklare TCP- eller HTTP-`/`-probe via ett valbart `probe`-block i
values (default = Actuator-path, override per tjänst).

### 2.3 Infra bakom toggle

`infra.enabled: true` (dev-default) renderar:

- **Postgres** — en generisk `postgres-statefulset.yaml` som loopar över `.Values.databases`
  (auth-db, user-db, message-db), var och en med eget `db`-namn och `storage`.
- **RabbitMQ** — en engångsresurs (`rabbitmq.yaml`).

I Fas D sätts `infra.enabled: false` → applikationstjänsternas `env`/`secretEnv` pekar mot
managed RDS/Amazon MQ via en `values-eks.yaml`, utan att chartets mallar ändras.

### 2.4 Portabilitet (payoff för Fas D)

Globala värden styr miljö-skillnaderna:

```yaml
global:
  namespace: devroom
  imageRegistry: devroom        # → "<account>.dkr.ecr.<region>.amazonaws.com/devroom" i AWS
  imageTag: latest
  imagePullPolicy: Never        # → IfNotPresent / Always i AWS
```

Byte av miljö = byte av values-fil, aldrig av mallar.

### 2.5 Låsta designval (från brainstorming 2026-06-14)

| Val | Beslut | Motivering |
|---|---|---|
| Chart-arkitektur | Ett chart, en generisk service-mall som loopar över `values` | Mest DRY, starkast Helm-skicklighet, lättast att utöka |
| Infra | I chartet, bakom `infra.enabled`-toggle | "helm install" ger hela stacken lokalt; ren RDS-väg i Fas D |
| Secrets | Helm-mall med dev-defaults, riktiga värden via override | "helm install" funkar direkt; ren väg till External Secrets i Fas D |
| Startordning | Förlita på readiness-probes + app-retry, ingen orkestrering | Kubernetes-idiomatiskt; rätt läxa för molnet |

---

## 3. Secrets

`secret.yaml` genererar `Secret`-objekt (`db-credentials`, `rabbitmq-credentials`,
`oauth-client-secrets`) från `.Values.secrets`. Dev-defaults (`change-me`) ligger i
`values.yaml` så att `helm install` fungerar direkt lokalt:

```yaml
secrets:
  db-credentials:        { username: devroom, password: change-me }
  rabbitmq-credentials:  { username: devroom, password: change-me }
  oauth-client-secrets:
    gateway-client-secret: change-me
    bot-service-client-secret: change-me
  dev-mentor-secrets:
    openrouter-api-key: ""     # ingen användbar dev-default — måste override:as
```

Riktiga värden injiceras via `--set` eller en **gitignorerad** `values-secrets.yaml`
(`helm upgrade --install ... -f values-secrets.yaml`). Detta sätter upp den rena vägen till
External Secrets Operator / AWS Secrets Manager i Fas D — då byts `secret.yaml`-mallen mot en
`ExternalSecret`-resurs utan att tjänsternas `secretEnv`-referenser ändras.

**Undantag — `openrouter-api-key`:** till skillnad från övriga dev-defaults är detta en
*riktig* extern credential (LLM-provider). Det finns ingen meningsfull `change-me`-default —
utan en giltig nyckel returnerar dev-mentor `503 LLM_UNAVAILABLE` när Bot Service försöker
generera svar. Den måste därför alltid levereras via override. Chartet renderar ett tomt
default-värde så `helm install` inte kraschar, men demo-flödet (bot-svar) kräver att nyckeln
sätts.

---

## 4. Startordning

Ingen orkestrering. Tjänster startar i godtycklig ordning; **readiness-probes** håller dem
ur trafik tills de är friska, och applikationslagret retriar mot beroenden
(Spring/Hikari mot Postgres, RabbitMQ-listener-retry, Resource Servers hämtar JWKS lat vid
första anrop). Detta ersätter `deploy.sh`:s `kubectl wait`-gating.

**Risk + mitigering:** om någon tjänst visar sig *inte* tåla att ett beroende saknas vid
uppstart (t.ex. en eager JWKS- eller DB-anslutning som failar utan retry), läggs en punktvis
`initContainer` till för just den tjänsten — inte en generell ordnings-mekanism. Verifieras
under implementation (sektion 7).

---

## 5. Vad som ersätter `deploy.sh`

Image-bygget behålls (samma `docker build` mot Minikubes daemon). `kubectl apply ...`-kedjan
+ `kubectl wait`-gatingen ersätts av:

```bash
helm upgrade --install devroom helm/devroom -n devroom --create-namespace
```

`templates/NOTES.txt` skriver ut port-forward-kommandona efter install (samma demo-URL:er som
idag — frontend `:3000`, gateway `:8080`, auth `:8081`, RabbitMQ UI `:15672`). Ett tunt
wrapper-skript (`helm/deploy.sh` eller uppdaterat `k8s/deploy.sh`) kedjar image-bygge +
`helm upgrade --install` för "ett kommando"-upplevelsen.

---

## 6. ADR

**ADR-0010 — Helm vs Kustomize** för paketering och multi-miljö-deploy.

- **Kustomize:** overlays utan templating, inbyggt i `kubectl`. Bra för enkel
  miljö-patchning men ingen loop/templating — de likadana tjänsterna skulle förbli separata
  filer.
- **Helm (vald):** templating + `values` + release-livscykel (`helm upgrade`/`rollback`/
  `history`). Loopen över `.Values.services` ger den DRY-vinst designen bygger på, och Helm är
  de-facto-standard i jobbannonser. Trade-off: Go-template-syntaxen är mindre läsbar än rå
  YAML — accepteras för paketerings- och portabilitetsvinsten.

---

## 7. Verifiering

- **`helm lint`** + **`helm template`** (rendering utan kluster) — körs lokalt och blir ett
  CI-steg.
- **Render-diff:** jämför `helm template`-output mot nuvarande `k8s/*.yaml` så ingen
  oavsiktlig drift-regression smyger in (samma images, portar, env, probes, resources).
- **End-to-end på Minikube:** `helm upgrade --install` → alla 11 pods Running, 8/8 deployments
  Available, och samma smoke-tester som Plan 10:
  - frontend HTTP 200
  - gateway `/api/me` HTTP 401 (ej inloggad)
  - auth-service OIDC-discovery returnerar korrekt issuer + JWKS-URL
- **Idempotens:** en andra `helm upgrade --install` ger inga oväntade förändringar.

---

## 8. Plats i roadmappen

| Plan | Fas | Innehåll |
|---|---|---|
| **11 (denna)** | A | Helm-chart (paketering + portabilitet) |
| 12 | A | Traefik ingress (ersätter port-forward, ADR-0009) |
| 13 | B | Metrics: Actuator/Micrometer → Prometheus → Grafana |
| 14 | B | Loggar: Loki + Alloy → Grafana |
| 15 | B | Tracing: OpenTelemetry → Tempo → Grafana (mikrotjänst-showpiece) |
| 16 | C | CI/CD: GitHub Actions bygger → pushar images → deployar chartet |
| 17 | D | AWS-fundament + EKS via Terraform (samma chart till molnet) |
| 18 | D | Managed data: RDS + ALB/Route53 (`infra.enabled: false`) |
| 19 (valfri) | D | Kibana/ELK för explicit Elastic-erfarenhet |

Plan 11 är fundamentet: observability (13–15) och CI/CD (16) deployar *via* chartet, och
Fas D *lyfter* samma chart till EKS.

---

## 9. Out of scope (explicit)

- Ingress/Traefik — Plan 12.
- Observability (metrics/loggar/tracing) — Fas B.
- CI-deploy av chartet (bara `helm lint`/`template` i CI nu) — Plan 16.
- Riktig secret-backend (External Secrets / AWS Secrets Manager) — Fas D.
- HorizontalPodAutoscaler, NetworkPolicies, cert-manager — senare faser om de behövs.
- Helm-hooks för DB-migrationer (Flyway körs redan vid app-uppstart) — ej aktuellt.
