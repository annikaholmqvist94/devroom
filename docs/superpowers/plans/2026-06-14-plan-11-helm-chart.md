# Plan 11 — Helm-chart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package the existing raw `k8s/*.yaml` manifests into one parameterized Helm chart so the whole Devroom stack installs with a single `helm upgrade --install`, and the same chart later deploys to EKS by swapping values.

**Architecture:** A single chart `helm/devroom` with a generic Deployment+Service template that ranges over `.Values.services` (the 7 application services), generic Postgres StatefulSet+Service templates that range over `.Values.databases`, and a one-off RabbitMQ template — both gated by an `infra.enabled` toggle. Secrets are Helm-managed from `.Values.secrets` with dev defaults overridable for real values. No orchestrated startup ordering: readiness probes + app-level retry handle dependency availability.

**Tech Stack:** Helm 3 (Go templates), Kubernetes (Minikube locally), existing Spring Boot 4 + Next.js + Postgres 16 + RabbitMQ 4 images.

**Spec:** `docs/superpowers/specs/2026-06-14-plan-11-helm-chart-design.md`

**Branch:** `plan-11-helm-chart` (already created; spec already committed).

**Prerequisites (verify before Task 1):**
- `helm version` → Helm v3.x installed. If missing: `brew install helm`.
- `minikube` + `kubectl` + `docker` installed (used only in Task 11).
- The 7 existing raw manifests in `k8s/` remain the source of truth for the values transcription. Do NOT delete them in this plan.

---

## File Structure

| File | Responsibility |
|---|---|
| `helm/devroom/Chart.yaml` | Chart metadata (name, version, appVersion) |
| `helm/devroom/values.yaml` | All config: globals, 7 services, 3 databases, infra, secrets |
| `helm/devroom/templates/_helpers.tpl` | Shared label/selector helpers |
| `helm/devroom/templates/secret.yaml` | Renders Secret objects from `.Values.secrets` |
| `helm/devroom/templates/app-deployment.yaml` | Generic Deployment, ranges over `.Values.services` |
| `helm/devroom/templates/app-service.yaml` | Generic Service, ranges over `.Values.services` |
| `helm/devroom/templates/postgres-statefulset.yaml` | Generic Postgres StatefulSet, ranges over `.Values.databases` (if `infra.enabled`) |
| `helm/devroom/templates/postgres-service.yaml` | Generic headless Postgres Service (if `infra.enabled`) |
| `helm/devroom/templates/rabbitmq.yaml` | RabbitMQ Deployment+Service (if `infra.enabled`) |
| `helm/devroom/templates/NOTES.txt` | Post-install port-forward instructions |
| `helm/deploy.sh` | One-command wrapper: build images + `helm upgrade --install` |
| `docs/adr/0010-helm-vs-kustomize.md` | ADR for packaging choice |
| `.github/workflows/ci.yml` | Add `helm` job (lint + template render) |
| `.gitignore` | Ignore `helm/devroom/values-secrets.yaml` |

---

## Task 1: Chart skeleton

**Files:**
- Create: `helm/devroom/Chart.yaml`
- Create: `helm/devroom/templates/_helpers.tpl`
- Create: `helm/devroom/values.yaml` (globals + empty maps)

- [ ] **Step 1: Create `helm/devroom/Chart.yaml`**

```yaml
apiVersion: v2
name: devroom
description: Devroom — distributed chat with @-mentionable AI mentors
type: application
version: 0.1.0
appVersion: "1.0.0"
```

- [ ] **Step 2: Create `helm/devroom/templates/_helpers.tpl`**

```
{{- define "devroom.labels" -}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
```

- [ ] **Step 3: Create `helm/devroom/values.yaml` (skeleton only)**

```yaml
global:
  imageRegistry: devroom
  imageTag: latest
  imagePullPolicy: Never

services: {}

databases: {}

infra:
  enabled: true
  postgres:
    image: postgres:16-alpine
    resources:
      requests: { memory: 256Mi, cpu: 100m }
      limits:   { memory: 512Mi, cpu: 500m }
  rabbitmq:
    image: rabbitmq:4-management-alpine
    resources:
      requests: { memory: 256Mi, cpu: 100m }
      limits:   { memory: 512Mi, cpu: 500m }

secrets: {}
```

- [ ] **Step 4: Run lint to verify the skeleton is valid**

Run: `helm lint helm/devroom`
Expected: `1 chart(s) linted, 0 chart(s) failed` (an `[INFO]` about missing icon is fine).

- [ ] **Step 5: Commit**

```bash
git add helm/devroom/Chart.yaml helm/devroom/templates/_helpers.tpl helm/devroom/values.yaml
git commit -m "feat(plan-11): scaffold devroom Helm chart skeleton"
```

---

## Task 2: Secret template

**Files:**
- Create: `helm/devroom/templates/secret.yaml`
- Modify: `helm/devroom/values.yaml` (fill `secrets:`)

- [ ] **Step 1: Create `helm/devroom/templates/secret.yaml`**

```
{{- range $name, $data := .Values.secrets }}
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ $name }}
  labels:
    {{- include "devroom.labels" $ | nindent 4 }}
type: Opaque
stringData:
{{- range $key, $value := $data }}
  {{ $key }}: {{ $value | quote }}
{{- end }}
{{- end }}
```

- [ ] **Step 2: Fill `secrets:` in `helm/devroom/values.yaml`** (replace `secrets: {}`)

These dev defaults match `k8s/secrets/render-secrets.sh` so `helm install` works locally without overrides. `openrouter-api-key` has no usable default — it must be supplied via override (see Task 8 / smoke test).

```yaml
secrets:
  db-credentials:
    username: dbuser
    password: dbpass
  rabbitmq-credentials:
    username: devroom
    password: devroom
  oauth-client-secrets:
    gateway-client-secret: dev-gateway-secret
    bot-service-client-secret: dev-bot-secret
  dev-mentor-secrets:
    openrouter-api-key: ""
```

- [ ] **Step 3: Render and verify all 4 Secrets appear**

Run: `helm template devroom helm/devroom | grep -E "^  name: (db-credentials|rabbitmq-credentials|oauth-client-secrets|dev-mentor-secrets)$"`
Expected: 4 lines, one per secret name.

- [ ] **Step 4: Verify a known key/value renders**

Run: `helm template devroom helm/devroom | grep "bot-service-client-secret:"`
Expected: `bot-service-client-secret: "dev-bot-secret"`

- [ ] **Step 5: Commit**

```bash
git add helm/devroom/templates/secret.yaml helm/devroom/values.yaml
git commit -m "feat(plan-11): render Secrets from values with dev defaults"
```

---

## Task 3: Generic Deployment template + all 7 services

**Files:**
- Create: `helm/devroom/templates/app-deployment.yaml`
- Modify: `helm/devroom/values.yaml` (fill `services:`)

- [ ] **Step 1: Create `helm/devroom/templates/app-deployment.yaml`**

The template iterates over `env` (plain key/values) and `secretEnv` (secretKeyRef). Probes are explicit per service (handles both Actuator services and the frontend's `/` probe). `quote` on env values forces them to strings (required by Kubernetes).

```
{{- range $name, $svc := .Values.services }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- include "devroom.labels" $ | nindent 4 }}
spec:
  replicas: {{ $svc.replicas | default 1 }}
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
    spec:
      containers:
      - name: {{ $name }}
        image: "{{ $.Values.global.imageRegistry }}/{{ $svc.image }}:{{ $svc.tag | default $.Values.global.imageTag }}"
        imagePullPolicy: {{ $.Values.global.imagePullPolicy }}
        ports:
        {{- range $svc.ports }}
        - containerPort: {{ .containerPort }}
          {{- if .name }}
          name: {{ .name }}
          {{- end }}
        {{- end }}
        {{- if or $svc.env $svc.secretEnv }}
        env:
        {{- range $key, $value := $svc.env }}
        - name: {{ $key }}
          value: {{ $value | quote }}
        {{- end }}
        {{- range $svc.secretEnv }}
        - name: {{ .name }}
          valueFrom:
            secretKeyRef:
              name: {{ .secret }}
              key: {{ .key }}
        {{- end }}
        {{- end }}
        readinessProbe:
          httpGet:
            path: {{ $svc.probe.readiness.path }}
            port: {{ $svc.probe.readiness.port }}
          initialDelaySeconds: {{ $svc.probe.readiness.initialDelaySeconds }}
          periodSeconds: {{ $svc.probe.readiness.periodSeconds }}
        livenessProbe:
          httpGet:
            path: {{ $svc.probe.liveness.path }}
            port: {{ $svc.probe.liveness.port }}
          initialDelaySeconds: {{ $svc.probe.liveness.initialDelaySeconds }}
          periodSeconds: {{ $svc.probe.liveness.periodSeconds }}
        resources:
{{ toYaml $svc.resources | indent 10 }}
{{- end }}
```

- [ ] **Step 2: Fill `services:` in `helm/devroom/values.yaml`** (replace `services: {}`)

Transcribed exactly from the existing `k8s/*.yaml` manifests. Plain env values stay in `env`; every `secretKeyRef` becomes a `secretEnv` entry.

```yaml
services:
  auth-service:
    image: auth-service
    ports:
      - { containerPort: 8081 }
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://auth-db:5432/authdb
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_ISSUER_URI: http://auth-service:8081
    secretEnv:
      - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: db-credentials, key: password }
      - { name: SPRING_RABBITMQ_USERNAME, secret: rabbitmq-credentials, key: username }
      - { name: SPRING_RABBITMQ_PASSWORD, secret: rabbitmq-credentials, key: password }
      - { name: GATEWAY_CLIENT_SECRET, secret: oauth-client-secrets, key: gateway-client-secret }
      - { name: BOT_CLIENT_SECRET, secret: oauth-client-secrets, key: bot-service-client-secret }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8081, initialDelaySeconds: 30, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8081, initialDelaySeconds: 60, periodSeconds: 10 }
    resources:
      requests: { memory: 512Mi, cpu: 200m }
      limits:   { memory: 1Gi,   cpu: 1 }

  user-service:
    image: user-service
    ports:
      - { name: http, containerPort: 8082 }
      - { name: grpc, containerPort: 9082 }
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://user-db:5432/userdb
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_ISSUER_URI: http://auth-service:8081
    secretEnv:
      - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: db-credentials, key: password }
      - { name: SPRING_RABBITMQ_USERNAME, secret: rabbitmq-credentials, key: username }
      - { name: SPRING_RABBITMQ_PASSWORD, secret: rabbitmq-credentials, key: password }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8082, initialDelaySeconds: 30, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8082, initialDelaySeconds: 60, periodSeconds: 10 }
    resources:
      requests: { memory: 512Mi, cpu: 200m }
      limits:   { memory: 1Gi,   cpu: 1 }

  message-service:
    image: message-service
    ports:
      - { containerPort: 8083 }
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://message-db:5432/messagedb
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_SERVICE_ISSUER: http://auth-service:8081
      AUTH_SERVICE_JWKS_URI: http://auth-service:8081/oauth2/jwks
      USER_SERVICE_GRPC: static://user-service:9082
    secretEnv:
      - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: db-credentials, key: password }
      - { name: SPRING_RABBITMQ_USERNAME, secret: rabbitmq-credentials, key: username }
      - { name: SPRING_RABBITMQ_PASSWORD, secret: rabbitmq-credentials, key: password }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8083, initialDelaySeconds: 30, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8083, initialDelaySeconds: 60, periodSeconds: 10 }
    resources:
      requests: { memory: 512Mi, cpu: 200m }
      limits:   { memory: 1Gi,   cpu: 1 }

  gateway:
    image: gateway
    ports:
      - { containerPort: 8080 }
    env:
      AUTH_SERVICE_ISSUER: http://auth-service:8081
      USER_SERVICE_URI: http://user-service:8082
      MESSAGE_SERVICE_URI: http://message-service:8083
      AUTH_SERVICE_URI: http://auth-service:8081
    secretEnv:
      - { name: GATEWAY_CLIENT_SECRET, secret: oauth-client-secrets, key: gateway-client-secret }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8080, initialDelaySeconds: 30, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8080, initialDelaySeconds: 60, periodSeconds: 10 }
    resources:
      requests: { memory: 384Mi, cpu: 100m }
      limits:   { memory: 768Mi, cpu: 500m }

  bot-service:
    image: bot-service
    ports:
      - { containerPort: 8084 }
    env:
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_SERVICE_ISSUER: http://auth-service:8081
      MESSAGE_SERVICE_URL: http://message-service:8083
      NORDIC_DEV_MENTOR_URL: http://dev-mentor:8090
      USER_SERVICE_GRPC: static://user-service:9082
    secretEnv:
      - { name: SPRING_RABBITMQ_USERNAME, secret: rabbitmq-credentials, key: username }
      - { name: SPRING_RABBITMQ_PASSWORD, secret: rabbitmq-credentials, key: password }
      - { name: BOT_CLIENT_SECRET, secret: oauth-client-secrets, key: bot-service-client-secret }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8084, initialDelaySeconds: 30, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8084, initialDelaySeconds: 60, periodSeconds: 10 }
    resources:
      requests: { memory: 384Mi, cpu: 100m }
      limits:   { memory: 768Mi, cpu: 500m }

  frontend:
    image: frontend
    ports:
      - { containerPort: 3000 }
    env:
      NEXT_PUBLIC_GATEWAY_URL: http://localhost:8080
      HOSTNAME: "0.0.0.0"
      PORT: "3000"
    probe:
      readiness: { path: /, port: 3000, initialDelaySeconds: 10, periodSeconds: 5 }
      liveness:  { path: /, port: 3000, initialDelaySeconds: 30, periodSeconds: 10 }
    resources:
      requests: { memory: 128Mi, cpu: 50m }
      limits:   { memory: 384Mi, cpu: 500m }

  dev-mentor:
    image: dev-mentor
    ports:
      - { containerPort: 8090 }
    env:
      SERVER_PORT: "8090"
      DEVMENTOR_STORE_TYPE: in-memory
    secretEnv:
      - { name: OPENROUTER_API_KEY, secret: dev-mentor-secrets, key: openrouter-api-key }
    probe:
      readiness: { path: /actuator/health/readiness, port: 8090, initialDelaySeconds: 15, periodSeconds: 5 }
      liveness:  { path: /actuator/health/liveness,  port: 8090, initialDelaySeconds: 30, periodSeconds: 10 }
    resources:
      requests: { memory: 384Mi, cpu: 100m }
      limits:   { memory: 768Mi, cpu: 500m }
```

- [ ] **Step 3: Verify all 7 Deployments render**

Run: `helm template devroom helm/devroom | grep -c "^kind: Deployment$"`
Expected: `7` — the 7 application services. (RabbitMQ's Deployment is added in Task 6, which will bump this to 8.)

- [ ] **Step 4: Verify the generic image string and a secretKeyRef render correctly**

Run: `helm template devroom helm/devroom | grep "image: \"devroom/auth-service:latest\""`
Expected: one match.

Run: `helm template devroom helm/devroom | grep -A2 "name: BOT_CLIENT_SECRET"`
Expected: shows `secretKeyRef` with `name: oauth-client-secrets` and `key: bot-service-client-secret`.

- [ ] **Step 5: Verify user-service renders both named ports**

Run: `helm template devroom helm/devroom | grep -E "name: (http|grpc)"`
Expected: both `name: http` and `name: grpc` appear.

- [ ] **Step 6: Commit**

```bash
git add helm/devroom/templates/app-deployment.yaml helm/devroom/values.yaml
git commit -m "feat(plan-11): generic Deployment template over all 7 services"
```

---

## Task 4: Generic Service template

**Files:**
- Create: `helm/devroom/templates/app-service.yaml`

- [ ] **Step 1: Create `helm/devroom/templates/app-service.yaml`**

```
{{- range $name, $svc := .Values.services }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- include "devroom.labels" $ | nindent 4 }}
spec:
  selector:
    app: {{ $name }}
  ports:
  {{- range $svc.ports }}
  - port: {{ .containerPort }}
    targetPort: {{ .containerPort }}
    {{- if .name }}
    name: {{ .name }}
    {{- end }}
  {{- end }}
{{- end }}
```

- [ ] **Step 2: Verify all 7 app Services render**

Run: `helm template devroom helm/devroom | grep -c "^kind: Service$"`
Expected: `7` (rabbitmq + postgres services not added yet).

- [ ] **Step 3: Verify user-service Service has both ports**

Run: `helm template devroom helm/devroom | awk '/name: user-service/,/^---/' | grep -E "port: (8082|9082)"`
Expected: both `port: 8082` and `port: 9082`.

- [ ] **Step 4: Commit**

```bash
git add helm/devroom/templates/app-service.yaml
git commit -m "feat(plan-11): generic Service template over all 7 services"
```

---

## Task 5: Postgres StatefulSet + Service (infra toggle)

**Files:**
- Create: `helm/devroom/templates/postgres-statefulset.yaml`
- Create: `helm/devroom/templates/postgres-service.yaml`
- Modify: `helm/devroom/values.yaml` (fill `databases:`)

- [ ] **Step 1: Fill `databases:` in `helm/devroom/values.yaml`** (replace `databases: {}`)

```yaml
databases:
  auth-db:    { database: authdb,    storage: 1Gi }
  user-db:    { database: userdb,    storage: 1Gi }
  message-db: { database: messagedb, storage: 1Gi }
```

- [ ] **Step 2: Create `helm/devroom/templates/postgres-statefulset.yaml`**

```
{{- if .Values.infra.enabled }}
{{- range $name, $db := .Values.databases }}
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- include "devroom.labels" $ | nindent 4 }}
spec:
  serviceName: {{ $name }}
  replicas: 1
  selector:
    matchLabels:
      app: {{ $name }}
  template:
    metadata:
      labels:
        app: {{ $name }}
    spec:
      containers:
      - name: postgres
        image: {{ $.Values.infra.postgres.image }}
        env:
        - name: POSTGRES_DB
          value: {{ $db.database | quote }}
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        ports:
        - containerPort: 5432
          name: postgres
        volumeMounts:
        - name: data
          mountPath: /var/lib/postgresql/data
          subPath: pgdata
        readinessProbe:
          exec:
            command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""]
          initialDelaySeconds: 5
          periodSeconds: 5
        livenessProbe:
          exec:
            command: ["sh", "-c", "pg_isready -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\""]
          initialDelaySeconds: 30
          periodSeconds: 10
        resources:
{{ toYaml $.Values.infra.postgres.resources | indent 10 }}
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: {{ $db.storage }}
{{- end }}
{{- end }}
```

- [ ] **Step 3: Create `helm/devroom/templates/postgres-service.yaml`**

```
{{- if .Values.infra.enabled }}
{{- range $name, $db := .Values.databases }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- include "devroom.labels" $ | nindent 4 }}
spec:
  selector:
    app: {{ $name }}
  clusterIP: None
  ports:
  - port: 5432
    targetPort: 5432
    name: postgres
{{- end }}
{{- end }}
```

- [ ] **Step 4: Verify 3 StatefulSets render**

Run: `helm template devroom helm/devroom | grep -c "^kind: StatefulSet$"`
Expected: `3`

- [ ] **Step 5: Verify the infra toggle disables them**

Run: `helm template devroom helm/devroom --set infra.enabled=false | grep -c "^kind: StatefulSet$"`
Expected: `0`

- [ ] **Step 6: Commit**

```bash
git add helm/devroom/templates/postgres-statefulset.yaml helm/devroom/templates/postgres-service.yaml helm/devroom/values.yaml
git commit -m "feat(plan-11): Postgres StatefulSet+Service templates behind infra toggle"
```

---

## Task 6: RabbitMQ (infra toggle)

**Files:**
- Create: `helm/devroom/templates/rabbitmq.yaml`

- [ ] **Step 1: Create `helm/devroom/templates/rabbitmq.yaml`**

```
{{- if .Values.infra.enabled }}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rabbitmq
  labels:
    app: rabbitmq
    {{- include "devroom.labels" . | nindent 4 }}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rabbitmq
  template:
    metadata:
      labels:
        app: rabbitmq
    spec:
      containers:
      - name: rabbitmq
        image: {{ .Values.infra.rabbitmq.image }}
        env:
        - name: RABBITMQ_DEFAULT_USER
          valueFrom:
            secretKeyRef:
              name: rabbitmq-credentials
              key: username
        - name: RABBITMQ_DEFAULT_PASS
          valueFrom:
            secretKeyRef:
              name: rabbitmq-credentials
              key: password
        ports:
        - containerPort: 5672
          name: amqp
        - containerPort: 15672
          name: management
        readinessProbe:
          exec:
            command: ["rabbitmq-diagnostics", "-q", "ping"]
          initialDelaySeconds: 15
          periodSeconds: 10
          timeoutSeconds: 5
        livenessProbe:
          exec:
            command: ["rabbitmq-diagnostics", "-q", "status"]
          initialDelaySeconds: 60
          periodSeconds: 30
          timeoutSeconds: 10
        resources:
{{ toYaml .Values.infra.rabbitmq.resources | indent 10 }}
---
apiVersion: v1
kind: Service
metadata:
  name: rabbitmq
  labels:
    app: rabbitmq
    {{- include "devroom.labels" . | nindent 4 }}
spec:
  selector:
    app: rabbitmq
  ports:
  - name: amqp
    port: 5672
    targetPort: 5672
  - name: management
    port: 15672
    targetPort: 15672
{{- end }}
```

- [ ] **Step 2: Verify final object counts (full stack)**

Run: `helm template devroom helm/devroom | grep -c "^kind: Deployment$"`
Expected: `8` (7 app services + RabbitMQ)

Run: `helm template devroom helm/devroom | grep -c "^kind: Service$"`
Expected: `11` (7 app + 3 Postgres + RabbitMQ)

Run: `helm template devroom helm/devroom | grep -c "^kind: StatefulSet$"`
Expected: `3`

- [ ] **Step 3: Verify lint still passes on the full chart**

Run: `helm lint helm/devroom`
Expected: `0 chart(s) failed`

- [ ] **Step 4: Commit**

```bash
git add helm/devroom/templates/rabbitmq.yaml
git commit -m "feat(plan-11): RabbitMQ Deployment+Service behind infra toggle"
```

---

## Task 7: NOTES.txt

**Files:**
- Create: `helm/devroom/templates/NOTES.txt`

- [ ] **Step 1: Create `helm/devroom/templates/NOTES.txt`**

```
Devroom installed as release "{{ .Release.Name }}" in namespace "{{ .Release.Namespace }}".

Watch pods come up:
  kubectl get pods -n {{ .Release.Namespace }} -w

Open the demo (run each in its own terminal, or append &):
  kubectl port-forward -n {{ .Release.Namespace }} svc/frontend     3000:3000
  kubectl port-forward -n {{ .Release.Namespace }} svc/gateway      8080:8080
  kubectl port-forward -n {{ .Release.Namespace }} svc/auth-service 8081:8081
  kubectl port-forward -n {{ .Release.Namespace }} svc/rabbitmq     15672:15672

Then visit:
  - http://localhost:3000      frontend (signup + chat)
  - http://localhost:15672     RabbitMQ management UI

{{- if not (index .Values.secrets "dev-mentor-secrets" "openrouter-api-key") }}

WARNING: secrets.dev-mentor-secrets.openrouter-api-key is empty.
Bot replies will fail with 503 LLM_UNAVAILABLE until you set it:
  helm upgrade --install {{ .Release.Name }} helm/devroom -n {{ .Release.Namespace }} \
    --set secrets.dev-mentor-secrets.openrouter-api-key=YOUR_KEY
{{- end }}

Uninstall with:
  helm uninstall {{ .Release.Name }} -n {{ .Release.Namespace }}
```

- [ ] **Step 2: Verify NOTES render without template errors**

Run: `helm template devroom helm/devroom --show-only templates/NOTES.txt -n devroom`
Expected: the rendered notes print, including the WARNING block (because the default openrouter key is empty). No template parse error.

Note: `--show-only` for NOTES may not be supported on all Helm versions; if it errors with "could not find template", instead run `helm install devroom helm/devroom -n devroom --dry-run | sed -n '/NOTES:/,$p'` and confirm the notes appear.

- [ ] **Step 3: Commit**

```bash
git add helm/devroom/templates/NOTES.txt
git commit -m "feat(plan-11): add post-install NOTES with port-forward + key warning"
```

---

## Task 8: One-command deploy wrapper + gitignore

**Files:**
- Create: `helm/deploy.sh`
- Modify: `.gitignore`

- [ ] **Step 1: Add ignore rule to `.gitignore`** (append at end)

```
# Real Helm secret values (dev defaults live in helm/devroom/values.yaml)
helm/devroom/values-secrets.yaml
```

- [ ] **Step 2: Create `helm/deploy.sh`**

```bash
#!/usr/bin/env bash
# One-command deploy of Devroom on Minikube via Helm.
#
# Prerequisites:
#   - minikube running:  minikube start --driver=docker --memory=6144 --cpus=4
#   - helm, kubectl, docker installed
#   - Nordic Dev Mentor cloned at ${DEV_MENTOR_PATH:-~/IdeaProjects/dev-mentor}
#
# Optional:
#   - OPENROUTER_API_KEY set in the environment → injected so bot replies work
#   - helm/devroom/values-secrets.yaml present → passed via -f for real secrets
#
# Usage:
#   bash helm/deploy.sh

set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
DEV_MENTOR_PATH="${DEV_MENTOR_PATH:-${HOME}/IdeaProjects/dev-mentor}"

echo "==> Verifying prerequisites"
command -v minikube >/dev/null || { echo "minikube not installed"; exit 1; }
command -v kubectl  >/dev/null || { echo "kubectl not installed"; exit 1; }
command -v helm     >/dev/null || { echo "helm not installed"; exit 1; }
command -v docker   >/dev/null || { echo "docker not installed"; exit 1; }
minikube status >/dev/null || { echo "minikube is not running. Run: minikube start --driver=docker --memory=6144 --cpus=4"; exit 1; }
[ -d "$DEV_MENTOR_PATH" ] || { echo "Nordic Dev Mentor not found at $DEV_MENTOR_PATH (override with DEV_MENTOR_PATH)"; exit 1; }

echo "==> Pointing Docker CLI at Minikube's Docker daemon and building images"
eval "$(minikube docker-env)"
for svc in auth-service user-service message-service gateway bot-service; do
  echo "  → devroom/${svc}:latest"
  DOCKER_BUILDKIT=1 docker build -f "services/${svc}/Dockerfile" -t "devroom/${svc}:latest" "$REPO_ROOT" >/dev/null
done
DOCKER_BUILDKIT=1 docker build -f frontend/Dockerfile -t devroom/frontend:latest "$REPO_ROOT" >/dev/null
DOCKER_BUILDKIT=1 docker build -t devroom/dev-mentor:latest "$DEV_MENTOR_PATH" >/dev/null

echo "==> Installing/upgrading Helm release"
HELM_ARGS=(upgrade --install devroom helm/devroom -n devroom --create-namespace)
[ -f helm/devroom/values-secrets.yaml ] && HELM_ARGS+=(-f helm/devroom/values-secrets.yaml)
[ -n "${OPENROUTER_API_KEY:-}" ] && HELM_ARGS+=(--set "secrets.dev-mentor-secrets.openrouter-api-key=${OPENROUTER_API_KEY}")
helm "${HELM_ARGS[@]}"

echo "==> Waiting for all deployments to become available (timeout 300s)"
kubectl wait -n devroom --for=condition=available deployment --all --timeout=300s

kubectl get pods -n devroom
```

- [ ] **Step 3: Make it executable and verify it parses**

Run: `chmod +x helm/deploy.sh && bash -n helm/deploy.sh && echo OK`
Expected: `OK` (syntax check only; does not run the deploy).

- [ ] **Step 4: Commit**

```bash
git add helm/deploy.sh .gitignore
git commit -m "feat(plan-11): one-command Helm deploy wrapper + ignore values-secrets"
```

---

## Task 9: ADR-0010 (Helm vs Kustomize)

**Files:**
- Create: `docs/adr/0010-helm-vs-kustomize.md`

- [ ] **Step 1: Create `docs/adr/0010-helm-vs-kustomize.md`**

```markdown
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

Använda **Helm 3** med ett enda chart (`helm/devroom`) som har en generisk
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
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0010-helm-vs-kustomize.md
git commit -m "docs(plan-11): ADR-0010 Helm vs Kustomize"
```

---

## Task 10: CI — helm lint + template job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add a `helm` job to `.github/workflows/ci.yml`**

Append this job under the existing `jobs:` map (sibling of `build:`, same indentation):

```yaml
  helm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6

      - name: Set up Helm
        uses: azure/setup-helm@v4
        with:
          version: v3.16.0

      - name: Helm lint
        run: helm lint helm/devroom

      - name: Helm template (render check)
        run: helm template devroom helm/devroom > /dev/null
```

- [ ] **Step 2: Verify the workflow YAML is valid**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('valid yaml')"`
Expected: `valid yaml`

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(plan-11): add helm lint + template render job"
```

---

## Task 11: End-to-end smoke test on Minikube

This task requires a running Minikube and is the real-world verification. It mirrors the Plan 10 smoke tests.

**Files:** none (verification only)

- [ ] **Step 1: Ensure Minikube is running**

Run: `minikube status || minikube start --driver=docker --memory=6144 --cpus=4`
Expected: `host: Running`, `kubelet: Running`, `apiserver: Running`.

- [ ] **Step 2: Deploy via the Helm wrapper**

Run: `OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-}" bash helm/deploy.sh`
Expected: images build, `helm upgrade --install` succeeds, and `kubectl wait` reports all deployments available. Final `kubectl get pods -n devroom` shows 11 pods.

- [ ] **Step 3: Verify pod and deployment counts**

Run: `kubectl get pods -n devroom --no-headers | grep -c Running`
Expected: `11`

Run: `kubectl get deployment -n devroom`
Expected: 8 deployments listed, all showing `READY 1/1`.

- [ ] **Step 4: Smoke test — frontend returns 200**

Run (in a second terminal): `kubectl port-forward -n devroom svc/frontend 3000:3000`
Then: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:3000`
Expected: `200`

- [ ] **Step 5: Smoke test — gateway /api/me returns 401 when not logged in**

Run: `kubectl port-forward -n devroom svc/gateway 8080:8080`
Then: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/me`
Expected: `401`

- [ ] **Step 6: Smoke test — auth-service OIDC discovery returns correct issuer**

Run: `kubectl port-forward -n devroom svc/auth-service 8081:8081`
Then: `curl -s http://localhost:8081/.well-known/openid-configuration | grep -o '"issuer":"[^"]*"'`
Expected: `"issuer":"http://auth-service:8081"` and the response also contains a `jwks_uri`.

- [ ] **Step 7: Verify idempotency**

Run: `helm upgrade --install devroom helm/devroom -n devroom`
Expected: succeeds with `STATUS: deployed`, revision incremented, no errors.

- [ ] **Step 8: Record the result**

No commit (verification only). Note the pod count and the three HTTP codes (200 / 401 / issuer) in the PR description.

---

## Task 12: Documentation

**Files:**
- Modify: `CLAUDE.md` (append a Plan 11 status block)
- Modify: `README.md` (add Helm quickstart, if a README exists at repo root)

- [ ] **Step 1: Append a Plan 11 status block to `CLAUDE.md`**

Add after the Plan 10 block, in the same style (Swedish, bullet summary). Include: chart layout, the generic-template + infra-toggle decisions, ADR-0010, that `helm/deploy.sh` is the new one-command path, raw `k8s/*.yaml` retained as reference, and that smoke tests passed (pod count + 200/401/issuer).

- [ ] **Step 2: Add a Helm quickstart to `README.md`** (if present)

```markdown
### Kör på Kubernetes med Helm

```bash
minikube start --driver=docker --memory=6144 --cpus=4
bash helm/deploy.sh           # bygger images + helm upgrade --install
# Bot-svar kräver en LLM-nyckel:
#   OPENROUTER_API_KEY=sk-... bash helm/deploy.sh
```
```

If no root `README.md` exists, skip this step and note it.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs(plan-11): document Helm chart deploy and Plan 11 status"
```

---

## Self-Review Notes

- **Spec coverage:** chart layout (Tasks 1–7), generic service template (Task 3), infra toggle (Tasks 5–6), portability globals (Task 1/3), Helm-managed secrets with dev defaults + openrouter exception (Tasks 2, 7, 8), probe+retry startup / no orchestration (inherent — no `kubectl wait` gating in chart; wrapper waits only post-install), deploy.sh replacement (Task 8), ADR-0010 (Task 9), verification incl. render checks + Minikube smoke (Tasks 3–6, 11), CI lint/template (Task 10). All spec sections map to a task.
- **Object-count expectations:** 8 Deployments (7 app + RabbitMQ), 11 Services (7 app + 3 Postgres + RabbitMQ), 3 StatefulSets, 4 Secrets. These are asserted incrementally (note the count changes between Task 3/4 before RabbitMQ exists and Task 6 after).
- **Out of scope (per spec):** ingress/Traefik (Plan 12), observability (Fas B), chart deploy from CI (Plan 16), real secret backend (Fas D), HPA/NetworkPolicies, deleting the raw `k8s/` manifests.
```
