# Plan 10: Kubernetes (Minikube för VG)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Revision 2026-05-12 — OAuth2-pivot:** Manifestet ändras:
>
> - **Inget `jwt-signing-key`-Secret** — privat nyckel genereras in-memory i Auth Service-podden, behöver inte mountas.
> - **Nya Secrets:** `oauth-client-secrets` med `gateway-client-secret` och `bot-service-client-secret` (BCrypt-värdena lagras i Auth Service Flyway-seed; plaintext-värdena används av Gateway och Bot Service via env-var).
> - **Service-namn ändrat:** `services/bff/` → `services/gateway/`, `bff.yaml` → `gateway.yaml`, `devroom/bff` → `devroom/gateway`. Sed-substitutionen är redan gjord i denna fil för path-referenser.
> - **K8s service-spec för Gateway** ska inkludera env-var `GATEWAY_CLIENT_SECRET` från Secret, och `AUTH_SERVICE_ISSUER` ska peka på intern DNS `http://auth-service:8081`.
> - **Bot Service-spec** behöver `BOT_CLIENT_SECRET` env-var från Secret (inte längre `BOT_SERVICE_JWT_PATH`). `service-jwt-path`-mountning kan tas bort.
> - **Spring Cloud BOM-installation** krävs i parent POM (Task 1 av Plan 06). Inte K8s-relaterat men måste vara klart innan gateway-image byggs.
> - **Port-forward-stegen är oförändrade** — Gateway exponerar fortfarande på 8080 (samma port som BFF hade), bara namnet på Service har ändrats.

**Goal:** Containerisera alla 5 backend-services + frontend, deploya på Minikube. Tjänster kommunicerar via interna DNS-namn, ConfigMaps/Secrets för config + nycklar. Demon körs via `kubectl port-forward` (ingen ingress controller).

**Architecture:** Multi-stage Dockerfile per Spring Boot-service (build + runtime). Frontend Dockerfile med Next.js standalone-output. Per service: en Deployment + Service. Postgres deployas som tre StatefulSets (auth/user/message). RabbitMQ som Deployment. JWT public key i ConfigMap, private key + service-JWT + DB-passwords i Secrets.

**Tech Stack:** Docker, Kubernetes manifests, Minikube + Docker driver, kubectl.

**Refererar spec:** sektion 11.

**Pre-conditions:** plan 01-09 klara. `minikube` installerat lokalt.

---

## File Structure

```
devroom/
├── services/auth-service/Dockerfile
├── services/user-service/Dockerfile
├── services/message-service/Dockerfile
├── services/gateway/Dockerfile
├── services/bot-service/Dockerfile
├── frontend/Dockerfile
├── docker-compose.yml                   # uppdateras med service-build-config
└── k8s/
    ├── namespace.yaml
    ├── configmaps/
    │   └── auth-public-key.yaml         # genereras från keys/public.pem
    ├── secrets/
    │   ├── secrets.yaml.template        # alla secrets samlade
    │   └── README.md                    # hur man fyller i secrets lokalt
    ├── postgres/
    │   ├── auth-db.yaml                 # StatefulSet + Service
    │   ├── user-db.yaml
    │   └── message-db.yaml
    ├── rabbitmq/
    │   └── rabbitmq.yaml                # Deployment + Service
    ├── auth-service.yaml
    ├── user-service.yaml
    ├── message-service.yaml
    ├── gateway.yaml
    ├── bot-service.yaml
    ├── frontend.yaml
    └── deploy.sh                        # one-command deploy script
```

---

## Task 1: Multi-stage Dockerfile per Spring Boot-service

Mall för alla Spring Boot-services (anpassa `<service>` per modul):

```dockerfile
# services/<service>/Dockerfile
# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY pom.xml .
COPY modules/ modules/
COPY proto/ proto/
COPY services/ services/
RUN mvn -B -pl services/<service> -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 1001 spring
USER spring
WORKDIR /app
COPY --from=builder /workspace/services/<service>/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

OBS: Build-context är repo-roten (för Maven multi-module). Kör därför Docker build från repo-roten:

```bash
docker build -f services/auth-service/Dockerfile -t devroom/auth-service:latest .
```

- [ ] **Step 1: Skapa Dockerfile per service** (5 filer, varianten `<service>` byts ut)
- [ ] **Step 2: Bygg lokalt och verifiera** för varje:

```bash
docker build -f services/auth-service/Dockerfile -t devroom/auth-service:latest .
docker run --rm -p 8081:8081 devroom/auth-service:latest
```

- [ ] **Step 3: Commit alla Dockerfiles**.

---

## Task 2: Frontend Dockerfile

```dockerfile
# frontend/Dockerfile
FROM node:22-alpine AS builder
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
```

OBS: `next.config.ts` måste ha `output: 'standalone'`. Lägg till om det saknas.

- [ ] **Step 1: Uppdatera `next.config.ts`** + bygg + verifiera + commit.

---

## Task 3: Uppdatera docker-compose.yml för full stack

Inkludera nu alla services så `docker compose up` ger hela stacken lokalt utan K8s. Använd Dockerfiles ovan.

```yaml
# docker-compose.yml
include:
  - docker-compose.dev.yml

services:
  auth-service:
    build:
      context: .
      dockerfile: services/auth-service/Dockerfile
    depends_on:
      auth-db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://auth-db:5432/authdb
      SPRING_DATASOURCE_USERNAME: dbuser
      SPRING_DATASOURCE_PASSWORD: dbpass
      SPRING_RABBITMQ_HOST: rabbitmq
      AUTH_PRIVATE_KEY_PATH: file:/keys/private.pem
    volumes:
      - ./keys:/keys:ro
    ports:
      - "8081:8081"

  # ... liknande för user-service, message-service, bff, bot-service, frontend
```

Commit.

---

## Task 4: Minikube setup

```bash
brew install minikube       # om ej installerat
minikube start --driver=docker --memory=6144 --cpus=4
eval $(minikube docker-env) # peka Docker CLI mot Minikubes daemon

# Bygg alla images direkt in i Minikubes Docker
docker build -f services/auth-service/Dockerfile -t devroom/auth-service:latest .
docker build -f services/user-service/Dockerfile -t devroom/user-service:latest .
docker build -f services/message-service/Dockerfile -t devroom/message-service:latest .
docker build -f services/gateway/Dockerfile -t devroom/gateway:latest .
docker build -f services/bot-service/Dockerfile -t devroom/bot-service:latest .
docker build -f frontend/Dockerfile -t devroom/frontend:latest .

# Verifiera
kubectl config use-context minikube
kubectl get nodes
```

---

## Task 5: Namespace + Secrets + ConfigMaps

```yaml
# k8s/namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: devroom
```

```yaml
# k8s/secrets/secrets.yaml.template
apiVersion: v1
kind: Secret
metadata:
  name: db-credentials
  namespace: devroom
type: Opaque
stringData:
  username: dbuser
  password: dbpass
---
apiVersion: v1
kind: Secret
metadata:
  name: jwt-private-key
  namespace: devroom
type: Opaque
data:
  private.pem: <base64-encoded-private-key>
---
apiVersion: v1
kind: Secret
metadata:
  name: bot-service-jwt
  namespace: devroom
type: Opaque
stringData:
  bot-service.jwt: <bot-service-jwt-token>
---
apiVersion: v1
kind: Secret
metadata:
  name: rabbitmq-credentials
  namespace: devroom
type: Opaque
stringData:
  username: devroom
  password: devroom
```

`secrets/README.md`:

```markdown
# Lokala secrets

För att fylla i `secrets.yaml.template`:

```bash
# Generera secrets från lokala filer
JWT_PRIV_B64=$(base64 -i ../../keys/private.pem | tr -d '\n')
BOT_JWT=$(cat ../../keys/bot-service.jwt)

sed -e "s|<base64-encoded-private-key>|$JWT_PRIV_B64|" \
    -e "s|<bot-service-jwt-token>|$BOT_JWT|" \
    secrets.yaml.template > secrets.yaml

kubectl apply -f secrets.yaml
rm secrets.yaml  # commitas inte
```
```

```yaml
# k8s/configmaps/auth-public-key.yaml (genereras likt secrets)
apiVersion: v1
kind: ConfigMap
metadata:
  name: jwt-public-key
  namespace: devroom
data:
  public.pem: |
    -----BEGIN PUBLIC KEY-----
    MIIBIj...
    -----END PUBLIC KEY-----
```

Skript `k8s/render-secrets.sh` som genererar dessa från lokala filer:

```bash
#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"

PUBLIC_KEY=$(cat ../keys/public.pem | sed 's/^/    /')
sed "s|<<PUBLIC_KEY>>|$PUBLIC_KEY|" configmaps/auth-public-key.yaml.template > configmaps/auth-public-key.yaml
# ... etc.
```

Commit (inte de genererade filerna).

---

## Task 6: Postgres-StatefulSets

Mall för en Postgres-StatefulSet (anpassa namn för auth/user/message):

```yaml
# k8s/postgres/auth-db.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: auth-db
  namespace: devroom
spec:
  serviceName: auth-db
  replicas: 1
  selector:
    matchLabels:
      app: auth-db
  template:
    metadata:
      labels:
        app: auth-db
    spec:
      containers:
      - name: postgres
        image: postgres:16-alpine
        env:
        - name: POSTGRES_DB
          value: authdb
        - name: POSTGRES_USER
          valueFrom: { secretKeyRef: { name: db-credentials, key: username } }
        - name: POSTGRES_PASSWORD
          valueFrom: { secretKeyRef: { name: db-credentials, key: password } }
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: data
          mountPath: /var/lib/postgresql/data
        readinessProbe:
          exec:
            command: ["pg_isready", "-U", "dbuser", "-d", "authdb"]
          initialDelaySeconds: 5
        resources:
          requests: { memory: 256Mi, cpu: 100m }
          limits: { memory: 512Mi, cpu: 500m }
  volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: [ "ReadWriteOnce" ]
      resources:
        requests:
          storage: 1Gi
---
apiVersion: v1
kind: Service
metadata:
  name: auth-db
  namespace: devroom
spec:
  selector:
    app: auth-db
  ports:
  - port: 5432
    targetPort: 5432
  clusterIP: None
```

(3 filer: auth-db, user-db, message-db). Commit.

---

## Task 7: RabbitMQ

```yaml
# k8s/rabbitmq/rabbitmq.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rabbitmq
  namespace: devroom
spec:
  replicas: 1
  selector:
    matchLabels: { app: rabbitmq }
  template:
    metadata:
      labels: { app: rabbitmq }
    spec:
      containers:
      - name: rabbitmq
        image: rabbitmq:4-management-alpine
        env:
        - name: RABBITMQ_DEFAULT_USER
          valueFrom: { secretKeyRef: { name: rabbitmq-credentials, key: username } }
        - name: RABBITMQ_DEFAULT_PASS
          valueFrom: { secretKeyRef: { name: rabbitmq-credentials, key: password } }
        ports:
        - containerPort: 5672
        - containerPort: 15672
        readinessProbe:
          exec:
            command: ["rabbitmq-diagnostics", "ping"]
          initialDelaySeconds: 15
          periodSeconds: 10
---
apiVersion: v1
kind: Service
metadata:
  name: rabbitmq
  namespace: devroom
spec:
  selector: { app: rabbitmq }
  ports:
  - name: amqp
    port: 5672
    targetPort: 5672
  - name: management
    port: 15672
    targetPort: 15672
```

Commit.

---

## Task 8: Service-deployments

Mall (per service, anpassa namn/port/env):

```yaml
# k8s/auth-service.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: auth-service
  namespace: devroom
spec:
  replicas: 1
  selector: { matchLabels: { app: auth-service } }
  template:
    metadata: { labels: { app: auth-service } }
    spec:
      containers:
      - name: auth-service
        image: devroom/auth-service:latest
        imagePullPolicy: Never        # använd lokal image
        ports: [ { containerPort: 8081 } ]
        env:
        - name: SPRING_DATASOURCE_URL
          value: jdbc:postgresql://auth-db:5432/authdb
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom: { secretKeyRef: { name: db-credentials, key: username } }
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom: { secretKeyRef: { name: db-credentials, key: password } }
        - name: SPRING_RABBITMQ_HOST
          value: rabbitmq
        - name: SPRING_RABBITMQ_USERNAME
          valueFrom: { secretKeyRef: { name: rabbitmq-credentials, key: username } }
        - name: SPRING_RABBITMQ_PASSWORD
          valueFrom: { secretKeyRef: { name: rabbitmq-credentials, key: password } }
        - name: AUTH_PRIVATE_KEY_PATH
          value: file:/etc/keys/private.pem
        volumeMounts:
        - name: jwt-private
          mountPath: /etc/keys
          readOnly: true
        readinessProbe:
          httpGet: { path: /actuator/health/readiness, port: 8081 }
          initialDelaySeconds: 20
          periodSeconds: 5
        livenessProbe:
          httpGet: { path: /actuator/health/liveness, port: 8081 }
          initialDelaySeconds: 30
          periodSeconds: 10
        resources:
          requests: { memory: 384Mi, cpu: 100m }
          limits: { memory: 768Mi, cpu: 500m }
      volumes:
      - name: jwt-private
        secret:
          secretName: jwt-private-key
---
apiVersion: v1
kind: Service
metadata:
  name: auth-service
  namespace: devroom
spec:
  selector: { app: auth-service }
  ports:
  - port: 8081
    targetPort: 8081
```

Liknande filer för:
- `user-service.yaml` (port 8082 + grpc 9082, behöver public-key från ConfigMap)
- `message-service.yaml` (port 8083, public-key + grpc-client mot user-service:9082)
- `gateway.yaml` (port 8080, public-key)
- `bot-service.yaml` (mounta `bot-service-jwt`-Secret)
- `frontend.yaml` (port 3000, NEXT_PUBLIC_GATEWAY_URL=http://gateway:8080)

Commit alla.

---

## Task 9: deploy.sh — one-command deploy

```bash
#!/usr/bin/env bash
# k8s/deploy.sh
set -e

cd "$(dirname "$0")/.."

echo "==> Setting up Docker context for Minikube"
eval $(minikube docker-env)

echo "==> Building all Docker images"
for svc in auth-service user-service message-service bff bot-service; do
  docker build -f services/$svc/Dockerfile -t devroom/$svc:latest .
done
docker build -f frontend/Dockerfile -t devroom/frontend:latest .

echo "==> Applying namespace"
kubectl apply -f k8s/namespace.yaml

echo "==> Generating and applying secrets + configmaps"
bash k8s/render-secrets.sh
kubectl apply -f k8s/secrets/secrets.yaml
kubectl apply -f k8s/configmaps/

echo "==> Applying infra"
kubectl apply -f k8s/postgres/
kubectl apply -f k8s/rabbitmq/

echo "==> Waiting for infra readiness"
kubectl wait -n devroom --for=condition=ready pod -l app=auth-db --timeout=120s
kubectl wait -n devroom --for=condition=ready pod -l app=user-db --timeout=120s
kubectl wait -n devroom --for=condition=ready pod -l app=message-db --timeout=120s
kubectl wait -n devroom --for=condition=ready pod -l app=rabbitmq --timeout=120s

echo "==> Applying services"
kubectl apply -f k8s/auth-service.yaml
kubectl apply -f k8s/user-service.yaml
kubectl apply -f k8s/message-service.yaml
kubectl apply -f k8s/gateway.yaml
kubectl apply -f k8s/bot-service.yaml
kubectl apply -f k8s/frontend.yaml

echo "==> Waiting for service readiness"
kubectl wait -n devroom --for=condition=available deployment --all --timeout=180s

echo "==> Deploy complete. Run 'minikube dashboard' for visual."
echo "==> Port-forward for demo:"
echo "    kubectl port-forward -n devroom svc/frontend 3000:3000 &"
echo "    kubectl port-forward -n devroom svc/gateway 8080:8080 &"
echo "    kubectl port-forward -n devroom svc/rabbitmq 15672:15672 &"
```

```bash
chmod +x k8s/deploy.sh
```

Commit.

---

## Task 10: Verifiera deploy från scratch

```bash
minikube delete  # rent state
minikube start --driver=docker --memory=6144 --cpus=4
bash k8s/deploy.sh

# Vänta tills allt är running
kubectl get pods -n devroom

# Port-forward
kubectl port-forward -n devroom svc/frontend 3000:3000 &
kubectl port-forward -n devroom svc/gateway 8080:8080 &

# Öppna http://localhost:3000 → testa hela flödet (signup → mention → bot-svar)
```

---

## Task 11: Skriv ADR-0009 (Minikube + port-forward vs ingress)

```markdown
# ADR-0009: Minikube med port-forward, ingen ingress controller

**Status:** Accepted
**Date:** 2026-05-...

## Decision

Vi kör Minikube med Docker driver och använder `kubectl port-forward` för demon istället för en ingress controller.

## Considered alternatives

- nginx-ingress via Helm: ~3-4h installation + config för marginell vinst.
- LoadBalancer service-typ: fungerar i Minikube men är "ofint" för flera services.

## Consequences

+ Demon kräver ingen extra konfiguration utöver port-forward.
+ Minikubes inbyggda dashboard ersätter nödvändigheten av en ingress-baserad UI-routing.
- Inga produktionsrealistiska URL-strukturer (`devroom.local/api`) — dokumenterat som future work.

## References

- Spec sektion 11
```

Commit.

---

## Task 12: Uppdatera README med snabbstart, demo-GIF, arkitekturdiagram

`README.md` — full version (ersätter den minimala från plan 01):

```markdown
# Devroom

Distributed chat with @-mentionable AI mentors. Laboration 2 i microservices-kursen.

[demo-gif här]

## Arkitektur

[mermaid-diagram här — kopiera från designspec sektion 2.2]

5 backend-microservices + Next.js-frontend, RabbitMQ för events, gRPC för intern read-trafik, JWT (RS256) för autentisering, outbox-pattern för signup, deployas på Kubernetes.

## Snabbstart

### Lokalt utan K8s

```bash
docker compose up --build
# Öppna http://localhost:3000
```

### På Minikube (VG)

```bash
minikube start --driver=docker --memory=6144 --cpus=4
bash k8s/deploy.sh
kubectl port-forward -n devroom svc/frontend 3000:3000 &
kubectl port-forward -n devroom svc/gateway 8080:8080 &
# Öppna http://localhost:3000
```

## Tjänster

- **BFF** (port 8080) — REST-fasad
- **Auth Service** (8081) — signup, login, JWT
- **User Service** (8082 HTTP, 9082 gRPC) — profiler, mentor-uppslag
- **Message Service** (8083) — meddelanden, mention-resolution
- **Bot Service** (8084) — wrappar Nordic Dev Mentor

## Designdokument

- [Design spec](docs/superpowers/specs/2026-05-10-devroom-design.md)
- [ADR:er](docs/adr/)

## Out of scope

- DM, refresh-tokens, multi-team, avatar-upload, WebSockets, mTLS — se designspec sektion 15.
```

- [ ] **Step 1: Spela in demo-GIF** med `ttygif`, `licecap`, eller skärminspelning + GIF-konvertering.
- [ ] **Step 2: Spara som `docs/diagrams/demo.gif`** och länka i README.
- [ ] **Step 3: Inkludera Mermaid-diagram** för arkitekturöversikt.
- [ ] **Step 4: Commit**.

---

## Task 13: ADR-översyn (polish-vecka 4)

- [ ] Läs igenom alla 5 huvud-ADR:er (0001-0005), uppdatera om något ändrats i implementationen.
- [ ] Skriv ADR-0009 (denna plan)
- [ ] Reservpott (om tid finns): ADR-0006 (mentions JSONB), 0007 (hård fail mention-resolution), 0008 (polling 3s vs alternativ).

Commit.

---

## Task 14: Plan-slut: full demo-rehearsal

Kör hela live-demo-manuset från designspec sektion 9 från grunden:

```bash
minikube delete
minikube start --driver=docker --memory=6144 --cpus=4
bash k8s/deploy.sh
minikube dashboard &
kubectl port-forward -n devroom svc/frontend 3000:3000 &
kubectl port-forward -n devroom svc/gateway 8080:8080 &
kubectl port-forward -n devroom svc/rabbitmq 15672:15672 &
```

I webbläsaren:
1. Öppna minikube dashboard, peka på alla pods running
2. Öppna http://localhost:3000, signa upp
3. Öppna http://localhost:15672 för RabbitMQ, peka på trafik
4. Tillbaka till http://localhost:3000, posta `@code-reviewer kan du förklara DI?`
5. Vänta ~5-8 sekunder, peka på bot-svaret
6. Öppna kubectl logs i terminal: `kubectl logs -n devroom -f deploy/bot-service`

Total demo-tid bör vara <5 minuter. Öva tills den är smidig.

---

## Plan 10 — slut

**Devroom är komplett.** Vid godkänd verifikation:

- G-betyg: alla microservice-krav uppfyllda
- VG-betyg: hela systemet kör på Kubernetes
- Portfolio: 5+ ADR:er, integrationstester, full README med diagram + GIF

Sista steget: muntlig redovisning + kodgranskning. Lycka till.
