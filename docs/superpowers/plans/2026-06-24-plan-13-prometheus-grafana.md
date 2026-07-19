# Plan 13 — Prometheus + Grafana (metrics) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Devroom metrics observability: each Spring service exposes `/actuator/prometheus`, kube-prometheus-stack scrapes it via a ServiceMonitor, and Grafana shows a dashboard at `grafana.devroom.local` — including two custom domain counters.

**Architecture:** Add `micrometer-registry-prometheus` to the 5 Spring services and expose the `prometheus` actuator endpoint. Two custom Micrometer counters (`messages.published`, `bot.replies`). Install kube-prometheus-stack as its own Helm release in `monitoring`; a ServiceMonitor in the devroom chart (label-selected) points Prometheus at the services. A provisioned Grafana dashboard combines auto + custom metrics.

**Tech Stack:** Micrometer + Spring Boot Actuator, kube-prometheus-stack (chart 87.1.0, Operator v0.92.0), Prometheus, Grafana, Traefik ingress (Plan 12).

**Spec:** `docs/superpowers/specs/2026-06-24-plan-13-prometheus-grafana-design.md`

**Branch:** `plan-13-prometheus-grafana` (already created; spec already committed).

**Prerequisites:** Plan 11 + 12 merged. `helm`, `kubectl`, `minikube`, `docker` installed. Minikube only needed for Task 10.

**Micrometer → Prometheus naming note (read before Tasks 2–3, 7):** a Micrometer `Counter` named `messages.published` is exposed by the Prometheus registry as **`messages_published_total`** (dots → underscores, counters get a `_total` suffix). Code uses the dotted name; PromQL/dashboards use the `_total` form.

---

## File Structure

| File | Responsibility |
|---|---|
| `services/*/pom.xml` (×5) | Add `micrometer-registry-prometheus` next to actuator |
| `services/*/src/main/resources/application.yml` (×5) | Add `prometheus` to actuator exposure |
| `services/message-service/.../MessageEventPublisher.java` | `messages.published` counter |
| `services/bot-service/.../MessagePoster.java` | `bot.replies` counter |
| `helm/devroom/values.yaml` | Named `http` port + `metrics: true` on the 5 Spring services + `metrics.enabled` |
| `helm/devroom/templates/app-service.yaml` | Conditional `devroom.io/metrics` label |
| `helm/devroom/templates/servicemonitor.yaml` | ServiceMonitor selecting the metrics label |
| `helm/devroom/templates/grafana-dashboard.yaml` | Dashboard ConfigMap (auto + custom panels) |
| `helm/install-monitoring.sh` | Install kube-prometheus-stack + Grafana ingress |
| `helm/configure-dns.sh` | Add `grafana.devroom.local` to the CoreDNS rewrite |
| `docs/adr/0012-kube-prometheus-stack.md` | ADR |

---

## Task 1: Expose Prometheus metrics from the 5 Spring services

> Note: the spec mentioned the parent POM, but `spring-boot-starter-actuator` is declared per-service in this repo. For pattern-consistency we add `micrometer-registry-prometheus` the same way — once per service pom. Same outcome (all 5 services get it), matches the existing convention.

**Files:**
- Modify: `services/{auth,user,message,gateway,bot}-service/pom.xml`
- Modify: `services/{auth,user,message,gateway,bot}-service/src/main/resources/application.yml`

- [ ] **Step 1: Add the dependency to each of the 5 service poms**

In each `services/<svc>-service/pom.xml`, find the actuator dependency:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
```

Add directly after it:

```xml
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
            <scope>runtime</scope>
        </dependency>
```

(Version is managed by the spring-boot-dependencies BOM — no `<version>` needed.)

- [ ] **Step 2: Add `prometheus` to the actuator exposure in each of the 5 application.yml**

For auth/user/message/bot-service, find:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

Replace `include` value with `health,info,prometheus`.

For gateway (which also exposes `gateway`), change `include: health, info, gateway` to `include: health, info, gateway, prometheus`.

- [ ] **Step 3: Verify the dependency and config are present everywhere**

Run: `grep -rl "micrometer-registry-prometheus" services/*/pom.xml | wc -l | tr -d ' '`
Expected: `5`

Run: `grep -rl "prometheus" services/*/src/main/resources/application.yml | wc -l | tr -d ' '`
Expected: `5`

- [ ] **Step 4: Verify a service still boots with the new dependency**

Run: `mvn -q -pl services/message-service verify`
Expected: BUILD SUCCESS (the integration tests boot the app; the new registry must not break context startup).

- [ ] **Step 5: Commit**

```bash
git add services/*/pom.xml services/*/src/main/resources/application.yml
git commit -m "feat(plan-13): expose Prometheus metrics from the 5 Spring services"
```

---

## Task 2: `messages.published` counter in Message Service

**Files:**
- Modify: `services/message-service/src/main/java/com/devroom/message/application/MessageEventPublisher.java`
- Test: `services/message-service/src/test/java/com/devroom/message/application/MessageEventPublisherTest.java`

- [ ] **Step 1: Write the failing test**

Create `services/message-service/src/test/java/com/devroom/message/application/MessageEventPublisherTest.java`:

```java
package com.devroom.message.application;

import com.devroom.message.domain.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageEventPublisherTest {

    @Test
    void increments_messages_published_counter_on_publish() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessageEventPublisher publisher = new MessageEventPublisher(
                rabbit, JsonMapper.builder().build(),
                "11111111-1111-1111-1111-111111111111", registry);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(UUID.randomUUID());
        when(message.getChannelId()).thenReturn(UUID.randomUUID());
        when(message.getSenderId()).thenReturn(UUID.randomUUID());
        when(message.getBody()).thenReturn("hello");
        when(message.getParentMessageId()).thenReturn(null);
        when(message.getMentions()).thenReturn(List.of());

        publisher.publish(message);

        assertThat(registry.counter("messages.published").count()).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl services/message-service test -Dtest=MessageEventPublisherTest`
Expected: FAIL — constructor `MessageEventPublisher(RabbitTemplate, JsonMapper, String, SimpleMeterRegistry)` does not exist (compile error).

- [ ] **Step 3: Add the counter to `MessageEventPublisher`**

Add imports near the top:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
```

Add a field next to the existing fields:

```java
    private final Counter messagesPublished;
```

Replace the constructor with one that accepts a `MeterRegistry` and builds the counter:

```java
    public MessageEventPublisher(RabbitTemplate rabbit,
                                  JsonMapper mapper,
                                  @Value("${devroom.message.demo-team-id}") String demoTeamId,
                                  MeterRegistry meterRegistry) {
        this.rabbit = rabbit;
        this.mapper = mapper;
        this.demoTeamId = UUID.fromString(demoTeamId);
        this.messagesPublished = Counter.builder("messages.published")
                .description("Total messages published to RabbitMQ")
                .register(meterRegistry);
    }
```

At the end of `publish(...)`, after `rabbit.send(...)`, add:

```java
        messagesPublished.increment();
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl services/message-service test -Dtest=MessageEventPublisherTest`
Expected: PASS.

- [ ] **Step 5: Verify the whole module still builds**

Run: `mvn -q -pl services/message-service verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add services/message-service/src/main/java/com/devroom/message/application/MessageEventPublisher.java services/message-service/src/test/java/com/devroom/message/application/MessageEventPublisherTest.java
git commit -m "feat(plan-13): add messages.published counter to Message Service"
```

---

## Task 3: `bot.replies` counter in Bot Service

**Files:**
- Modify: `services/bot-service/src/main/java/com/devroom/bot/application/MessagePoster.java`
- Test: `services/bot-service/src/test/java/com/devroom/bot/application/MessagePosterTest.java`

- [ ] **Step 1: Write the failing test**

Create `services/bot-service/src/test/java/com/devroom/bot/application/MessagePosterTest.java`:

```java
package com.devroom.bot.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class MessagePosterTest {

    @Test
    void increments_bot_replies_counter_on_post() {
        RestClient client = mock(RestClient.class, RETURNS_DEEP_STUBS);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MessagePoster poster = new MessagePoster(client, registry);

        poster.post("channel-1", "user-1", "hi there", null, "msg-1");

        assertThat(registry.counter("bot.replies").count()).isEqualTo(1.0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -q -pl services/bot-service test -Dtest=MessagePosterTest`
Expected: FAIL — constructor `MessagePoster(RestClient, SimpleMeterRegistry)` does not exist (compile error).

- [ ] **Step 3: Add the counter to `MessagePoster`**

Add imports:

```java
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
```

Add a field next to `private final RestClient client;`:

```java
    private final Counter botReplies;
```

Replace the constructor:

```java
    public MessagePoster(RestClient messageServiceRestClient, MeterRegistry meterRegistry) {
        this.client = messageServiceRestClient;
        this.botReplies = Counter.builder("bot.replies")
                .description("Total replies posted by the bot")
                .register(meterRegistry);
    }
```

At the end of `post(...)`, after the `log.info(...)` line, add:

```java
        botReplies.increment();
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -q -pl services/bot-service test -Dtest=MessagePosterTest`
Expected: PASS.

- [ ] **Step 5: Verify the whole module still builds**

Run: `mvn -q -pl services/bot-service verify`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add services/bot-service/src/main/java/com/devroom/bot/application/MessagePoster.java services/bot-service/src/test/java/com/devroom/bot/application/MessagePosterTest.java
git commit -m "feat(plan-13): add bot.replies counter to Bot Service"
```

---

## Task 4: Chart wiring — metrics label + named ports

**Files:**
- Modify: `helm/devroom/values.yaml`
- Modify: `helm/devroom/templates/app-service.yaml`

- [ ] **Step 1: Add `metrics.enabled` to `values.yaml`** (append near the `ingress:` block)

```yaml
metrics:
  enabled: true
```

- [ ] **Step 2: Add `metrics: true` and name the `http` port on each of the 5 Spring services in `values.yaml`**

For **auth-service**, change its `ports` and add a `metrics` key:

```yaml
  auth-service:
    image: auth-service
    metrics: true
    ports:
      - { name: http, containerPort: 8081 }
```

Do the same shape for the others (add `metrics: true`, name the HTTP port `http`):
- `message-service`: `ports: [ { name: http, containerPort: 8083 } ]`
- `gateway`: `ports: [ { name: http, containerPort: 8080 } ]`
- `bot-service`: `ports: [ { name: http, containerPort: 8084 } ]`
- `user-service`: already has `{ name: http, containerPort: 8082 }` + `{ name: grpc, containerPort: 9082 }` — just add `metrics: true`.

Do NOT add `metrics`/rename ports for `frontend` or `dev-mentor` (no Actuator-Prometheus).

- [ ] **Step 3: Add the conditional metrics label in `helm/devroom/templates/app-service.yaml`**

Find the metadata labels block:

```
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- include "devroom.labels" $ | nindent 4 }}
```

Replace with:

```
metadata:
  name: {{ $name }}
  labels:
    app: {{ $name }}
    {{- if $svc.metrics }}
    devroom.io/metrics: "true"
    {{- end }}
    {{- include "devroom.labels" $ | nindent 4 }}
```

- [ ] **Step 4: Verify exactly the 5 Spring services get the label**

Run: `helm template devroom helm/devroom --show-only templates/app-service.yaml | grep -c 'devroom.io/metrics: "true"'`
Expected: `5`

Run: `helm template devroom helm/devroom --show-only templates/app-service.yaml | grep -B3 'devroom.io/metrics' | grep -E "name: (frontend|dev-mentor)" || echo "frontend/dev-mentor correctly excluded"`
Expected: `frontend/dev-mentor correctly excluded`

- [ ] **Step 5: Commit**

```bash
git add helm/devroom/values.yaml helm/devroom/templates/app-service.yaml
git commit -m "feat(plan-13): metrics label + named http ports on the 5 Spring services"
```

---

## Task 5: ServiceMonitor template

**Files:**
- Create: `helm/devroom/templates/servicemonitor.yaml`

- [ ] **Step 1: Create `helm/devroom/templates/servicemonitor.yaml`**

```
{{- if .Values.metrics.enabled }}
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: devroom
  labels:
    {{- include "devroom.labels" . | nindent 4 }}
spec:
  namespaceSelector:
    matchNames:
      - {{ .Release.Namespace }}
  selector:
    matchLabels:
      devroom.io/metrics: "true"
  endpoints:
  - port: http
    path: /actuator/prometheus
    interval: 15s
{{- end }}
```

- [ ] **Step 2: Verify it renders and the toggle works**

Run: `helm template devroom helm/devroom --show-only templates/servicemonitor.yaml | grep -E "kind: ServiceMonitor|path: /actuator/prometheus|port: http"`
Expected: all three lines appear.

Run: `helm template devroom helm/devroom --set metrics.enabled=false | grep -c "kind: ServiceMonitor" || echo 0`
Expected: `0`

Note: `helm template` renders the ServiceMonitor without needing the CRD. `helm install` requires the CRD, which kube-prometheus-stack provides (installed before the app — see Task 10).

- [ ] **Step 3: Commit**

```bash
git add helm/devroom/templates/servicemonitor.yaml
git commit -m "feat(plan-13): ServiceMonitor scraping the 5 Spring services"
```

---

## Task 6: install-monitoring.sh (kube-prometheus-stack)

**Files:**
- Create: `helm/install-monitoring.sh`

- [ ] **Step 1: Create `helm/install-monitoring.sh`**

```bash
#!/usr/bin/env bash
# Installs kube-prometheus-stack (Prometheus Operator + Prometheus + Grafana +
# Alertmanager) as its own Helm release. Grafana is exposed via Traefik ingress
# at grafana.devroom.local (requires Traefik + CoreDNS from Plan 12's
# setup-ingress.sh to already be installed).
set -euo pipefail

echo "==> Adding prometheus-community Helm repo"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update prometheus-community >/dev/null

echo "==> Installing/upgrading kube-prometheus-stack (chart 87.1.0)"
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  --version 87.1.0 \
  -n monitoring --create-namespace \
  --set grafana.ingress.enabled=true \
  --set grafana.ingress.ingressClassName=traefik \
  --set "grafana.ingress.hosts[0]=grafana.devroom.local" \
  --set grafana.adminPassword=admin \
  --set grafana.sidecar.dashboards.searchNamespace=ALL \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false \
  --wait --timeout 10m

echo "==> Monitoring stack ready. Grafana: http://grafana.devroom.local (admin/admin)"
kubectl get pods -n monitoring
```

- [ ] **Step 2: Make executable and syntax-check**

Run: `chmod +x helm/install-monitoring.sh && bash -n helm/install-monitoring.sh && echo OK`
Expected: `OK`.

- [ ] **Step 3: Commit**

```bash
git add helm/install-monitoring.sh
git commit -m "feat(plan-13): kube-prometheus-stack install script with Grafana ingress"
```

---

## Task 7: Grafana dashboard ConfigMap

A provisioned dashboard combining the two custom counters with auto JVM/HTTP metrics. The `grafana_dashboard: "1"` label makes the kube-prometheus-stack Grafana sidecar auto-import it (sidecar set to watch all namespaces in Task 6).

**Files:**
- Create: `helm/devroom/templates/grafana-dashboard.yaml`

- [ ] **Step 1: Create `helm/devroom/templates/grafana-dashboard.yaml`**

```
{{- if .Values.metrics.enabled }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: devroom-overview-dashboard
  labels:
    grafana_dashboard: "1"
    {{- include "devroom.labels" . | nindent 4 }}
data:
  devroom-overview.json: |
    {
      "title": "Devroom Overview",
      "uid": "devroom-overview",
      "schemaVersion": 39,
      "version": 1,
      "time": { "from": "now-30m", "to": "now" },
      "panels": [
        {
          "type": "timeseries", "title": "Messages published / sec",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 0 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [ { "expr": "sum(rate(messages_published_total[5m]))", "legendFormat": "messages" } ]
        },
        {
          "type": "timeseries", "title": "Bot replies / sec",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 0 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [ { "expr": "sum(rate(bot_replies_total[5m]))", "legendFormat": "bot replies" } ]
        },
        {
          "type": "timeseries", "title": "JVM memory used by service",
          "gridPos": { "h": 8, "w": 12, "x": 0, "y": 8 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [ { "expr": "sum(jvm_memory_used_bytes) by (job)", "legendFormat": "{{`{{job}}`}}" } ]
        },
        {
          "type": "timeseries", "title": "HTTP requests / sec by service",
          "gridPos": { "h": 8, "w": 12, "x": 12, "y": 8 },
          "datasource": { "type": "prometheus", "uid": "prometheus" },
          "targets": [ { "expr": "sum(rate(http_server_requests_seconds_count[5m])) by (job)", "legendFormat": "{{`{{job}}`}}" } ]
        }
      ]
    }
{{- end }}
```

Note: `{{`{{job}}`}}` escapes Grafana's own `{{job}}` legend syntax so Helm doesn't try to interpret it.

- [ ] **Step 2: Verify it renders as valid JSON inside the ConfigMap**

Run: `helm template devroom helm/devroom --show-only templates/grafana-dashboard.yaml | python3 -c "import sys,yaml,json; cm=yaml.safe_load(sys.stdin); json.loads(cm['data']['devroom-overview.json']); print('dashboard JSON valid')"`
Expected: `dashboard JSON valid`

- [ ] **Step 3: Commit**

```bash
git add helm/devroom/templates/grafana-dashboard.yaml
git commit -m "feat(plan-13): provision Grafana dashboard for custom + auto metrics"
```

---

## Task 8: Add grafana.devroom.local to CoreDNS

**Files:**
- Modify: `helm/configure-dns.sh`

- [ ] **Step 1: Add the grafana host to the rewrite in `helm/configure-dns.sh`**

Find:

```bash
HOST1=devroom.local
HOST2=auth.devroom.local
TARGET=traefik.traefik.svc.cluster.local
```

Add a third host and inject it. Replace the block above with:

```bash
HOST1=devroom.local
HOST2=auth.devroom.local
HOST3=grafana.devroom.local
TARGET=traefik.traefik.svc.cluster.local
```

Then find the Python `inject` line:

```python
inject = f'    rewrite name {host1} {target}\n    rewrite name {host2} {target}\n'
```

Replace with (and pass HOST3 as a 4th arg):

```python
inject = f'    rewrite name {host1} {target}\n    rewrite name {host2} {target}\n    rewrite name {host3} {target}\n'
```

Update the `python3 - ...` invocation line to pass the third host:

Find: `python3 - "$HOST1" "$HOST2" "$TARGET" <<'PY' > /tmp/coredns-patched.json`
Replace: `python3 - "$HOST1" "$HOST2" "$HOST3" "$TARGET" <<'PY' > /tmp/coredns-patched.json`

And inside the Python, update the argv unpacking:

Find: `host1, host2, target = sys.argv[1], sys.argv[2], sys.argv[3]`
Replace: `host1, host2, host3, target = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]`

- [ ] **Step 2: Syntax-check the script and its embedded Python**

Run: `bash -n helm/configure-dns.sh && echo "bash OK"`
Expected: `bash OK`

Run: `awk "/<<'PY'/{f=1;next} /^PY\$/{f=0} f" helm/configure-dns.sh | python3 -c "import ast,sys; ast.parse(sys.stdin.read()); print('python OK')"`
Expected: `python OK`

- [ ] **Step 3: Commit**

```bash
git add helm/configure-dns.sh
git commit -m "feat(plan-13): add grafana.devroom.local to CoreDNS split-horizon"
```

---

## Task 9: ADR-0012

**Files:**
- Create: `docs/adr/0012-kube-prometheus-stack.md`

- [ ] **Step 1: Create `docs/adr/0012-kube-prometheus-stack.md`**

```markdown
# ADR-0012: kube-prometheus-stack för metrics-observability

**Status:** Accepted
**Date:** 2026-06-24

## Context

Fas B börjar med metrics. Tjänsterna har Actuator men ingen metrics-pipeline.
Vi behöver välja hur Prometheus + Grafana installeras och hur Prometheus hittar
tjänsternas `/actuator/prometheus`-endpoints.

## Decision

Installera **kube-prometheus-stack** (prometheus-community) som egen Helm-release
i namespace `monitoring`. Tjänsterna exponerar metrics via
`micrometer-registry-prometheus`. Prometheus hittar dem via en **ServiceMonitor**
(CRD) i devroom-chartet som selekterar tjänsterna på label `devroom.io/metrics`.
Grafana nås via Traefik-ingress (`grafana.devroom.local`).

## Considered alternatives

### 1. Lättvikts standalone Prometheus + Grafana
En enkel Prometheus-Deployment + scrape-config med pod-annotationer. Lägre RAM,
färre rörliga delar, mer synlig scrape-config. Avvisat: ingen Operator/
ServiceMonitor — kube-prometheus-stack är branschstandarden och termerna
('Prometheus Operator', 'ServiceMonitor') är de man möter i produktionsmiljöer.

### 2. Grafana Cloud / hosted
Avvisat — projektet ska kunna köras helt lokalt.

### 3. Scrape-annotationer istället för ServiceMonitor
Fungerar men matchar inte Operator-mönstret; ServiceMonitor-CRD är det
idiomatiska sättet med kube-prometheus-stack.

## Consequences

- En ny kluster-release (`monitoring`) + ServiceMonitor-CRD; devroom-chartet får
  en ServiceMonitor + en dashboard-ConfigMap bakom `metrics.enabled`.
- Minikube höjs till 8 GB RAM (stacken drar ~1.5–2.5Gi).
- `install-monitoring.sh` körs efter Plan 12:s `setup-ingress.sh` (Grafana-ingress
  kräver Traefik) och före devroom-deployen (ServiceMonitor-CRD måste finnas).
- Egna domän-counters (`messages.published`, `bot.replies`) exponeras som
  `messages_published_total` / `bot_replies_total`.
- Loggar (Loki) och tracing (Tempo) byggs i Plan 14–15 på samma Grafana.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0012-kube-prometheus-stack.md
git commit -m "docs(plan-13): ADR-0012 kube-prometheus-stack"
```

---

## Task 10: End-to-end on Minikube

Live verification. Requires Minikube with more RAM. Traefik + CoreDNS from Plan 12 are assumed present; we re-run configure-dns (now with grafana) and install monitoring before redeploying the app.

**Files:** none (verification only)

- [ ] **Step 1: Ensure Minikube has enough memory**

If Minikube is running with less than 8 GB, recreate it:

Run: `minikube delete && minikube start --driver=docker --memory=8192 --cpus=4`
Expected: `host: Running`. (A fresh cluster — Traefik + app + monitoring are reinstalled below.)

- [ ] **Step 2: Set up ingress (Traefik + CoreDNS incl. grafana host)**

Run: `bash helm/setup-ingress.sh`
Expected: Traefik ready, CoreDNS patched with all three hostnames.

- [ ] **Step 3: Install the monitoring stack**

Run: `bash helm/install-monitoring.sh`
Expected: stack rolls out (`--wait` returns), `kubectl get pods -n monitoring` shows Prometheus, Grafana, Operator, etc. Running. (CRDs incl. ServiceMonitor now exist.)

- [ ] **Step 4: Deploy the app (rebuilds images with metrics + counters)**

Run: `OPENROUTER_API_KEY="${OPENROUTER_API_KEY:-}" bash helm/deploy.sh`
Expected: all 8 deployments available; the ServiceMonitor + dashboard ConfigMap apply cleanly (CRD present).

- [ ] **Step 5: Verify a service exposes Prometheus metrics**

Run: `kubectl run mtest --rm --attach --restart=Never --image=curlimages/curl -n devroom -- curl -s http://message-service:8083/actuator/prometheus | grep -E "jvm_memory_used_bytes|messages_published_total" | head -3`
Expected: lines including `jvm_memory_used_bytes` (and `messages_published_total` appears after at least one message is posted).

- [ ] **Step 6: Verify Prometheus scrapes the services (targets UP)**

Run: `kubectl exec -n monitoring sts/prometheus-monitoring-kube-prometheus-prometheus -c prometheus -- wget -qO- http://localhost:9090/api/v1/targets | grep -o '"health":"up"' | wc -l | tr -d ' '`
Expected: a number ≥ `5` (the 5 Spring services are UP; other stack targets also count).

- [ ] **Step 7: Add hosts + tunnel, then open Grafana** (manual, sudo)

Run: `echo "127.0.0.1 grafana.devroom.local" | sudo tee -a /etc/hosts` (devroom/auth hosts added in Plan 12).
Then `minikube tunnel` in a separate terminal. Open `http://grafana.devroom.local` (admin/admin) → Dashboards → "Devroom Overview". Post a message via `http://devroom.local` and watch `messages_published_total` / `bot_replies_total` rise.

- [ ] **Step 8: Record the result**

No commit. Note in the PR: `/actuator/prometheus` exposes metrics, Prometheus targets UP ≥5, Grafana dashboard renders, custom counters increment.

---

## Task 11: Documentation

**Files:**
- Modify: `CLAUDE.md`
- Modify: `README.md`

- [ ] **Step 1: Append a Plan 13 status block to `CLAUDE.md`** (after the Plan 12 block, same Swedish bullet style)

Cover: micrometer-registry-prometheus on the 5 services + `prometheus` exposure; two custom counters (`messages.published`→`messages_published_total`, `bot.replies`→`bot_replies_total`); kube-prometheus-stack own release in `monitoring` (chart 87.1.0); ServiceMonitor in chart selecting `devroom.io/metrics`; Grafana via ingress (`grafana.devroom.local`) + CoreDNS third host; provisioned dashboard ConfigMap; Minikube bumped to 8 GB; install order (setup-ingress → install-monitoring → deploy); ADR-0012; verification results. End with **Nästa steg:** merge + Plan 14 (Loki/loggar).

- [ ] **Step 2: Update `README.md`** — add a "Metrics (Grafana)" subsection after the ingress section:

```markdown
### Metrics med Prometheus + Grafana

Se [ADR-0012](docs/adr/0012-kube-prometheus-stack.md). kube-prometheus-stack
skrapar tjänsternas `/actuator/prometheus` via en ServiceMonitor; Grafana visar
en "Devroom Overview"-dashboard (inkl. egna mått `messages_published_total` och
`bot_replies_total`).

```bash
bash helm/setup-ingress.sh     # Traefik + CoreDNS (incl. grafana-host)
bash helm/install-monitoring.sh # kube-prometheus-stack + Grafana-ingress
bash helm/deploy.sh             # appen (ServiceMonitor + dashboard)

echo "127.0.0.1 grafana.devroom.local" | sudo tee -a /etc/hosts
minikube tunnel                 # eget fönster
# Grafana: http://grafana.devroom.local  (admin/admin)
```

Kräver Minikube med minst 8 GB: `minikube start --driver=docker --memory=8192 --cpus=4`.
```

Add a status table row: `| 13 | Metrics: Prometheus + Grafana (ServiceMonitor + egna counters + ADR-0012) | 13 | 2026-06-24 |`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs(plan-13): document Prometheus + Grafana metrics and Plan 13 status"
```

---

## Self-Review Notes

- **Spec coverage:** micrometer dep + exposure (Task 1), custom counters (Tasks 2–3), kube-prometheus-stack own release + Grafana ingress + serviceMonitorSelector (Task 6), ServiceMonitor + label/named ports (Tasks 4–5), dashboards (Task 7), grafana CoreDNS host (Task 8), Minikube 8 GB + install order (Task 10), ADR-0012 (Task 9), verification + docs (Tasks 10–11). All spec sections map to a task.
- **Naming:** Micrometer `messages.published`/`bot.replies` → Prometheus `messages_published_total`/`bot_replies_total` (used consistently in tests, dashboard PromQL, and docs).
- **Ordering (Task 10):** setup-ingress (Traefik+CoreDNS) → install-monitoring (CRDs + Grafana ingress) → deploy app (ServiceMonitor needs the CRD). Documented in ADR-0012 and Task 10.
- **Out of scope (spec §6):** Loki/logs (14), Tempo/tracing (15), Alertmanager rules, long-term storage, frontend metrics.
```
