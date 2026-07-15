#!/usr/bin/env bash
# Installs Loki (single-binary) + Grafana Alloy (DaemonSet) in the monitoring
# namespace, and provisions a Loki datasource into the existing Grafana.
# Run after install-monitoring.sh (Plan 13).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Adding grafana Helm repo"
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update grafana >/dev/null

echo "==> Installing Loki (chart 7.0.0, single-binary)"
helm upgrade --install loki grafana/loki \
  --version 7.0.0 -n monitoring --create-namespace \
  -f helm/loki-values.yaml --wait --timeout 10m

echo "==> Installing Grafana Alloy (chart 1.10.0, DaemonSet)"
helm upgrade --install alloy grafana/alloy \
  --version 1.10.0 -n monitoring \
  -f helm/alloy-values.yaml --wait --timeout 5m

echo "==> Provisioning Loki datasource into Grafana"
kubectl apply -f helm/grafana-loki-datasource.yaml

echo "==> Logging stack ready. Query logs in Grafana → Explore → Loki."
kubectl get pods -n monitoring -l 'app.kubernetes.io/name in (loki, alloy)'
