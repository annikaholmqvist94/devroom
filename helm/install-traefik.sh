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
