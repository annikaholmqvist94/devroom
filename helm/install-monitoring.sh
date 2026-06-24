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
