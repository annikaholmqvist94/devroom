#!/usr/bin/env bash
# Installs External Secrets Operator, which syncs AWS Secrets Manager entries
# into Kubernetes Secrets. Only meaningful on EKS — Minikube has no AWS to read.
# Run before deploying the chart with -f values-eks.yaml.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> Adding external-secrets Helm repo"
helm repo add external-secrets https://charts.external-secrets.io >/dev/null 2>&1 || true
helm repo update external-secrets >/dev/null

echo "==> Installing External Secrets Operator (chart 2.8.0)"
helm upgrade --install external-secrets external-secrets/external-secrets \
  --version 2.8.0 -n external-secrets --create-namespace \
  --set installCRDs=true --wait --timeout 5m

echo "==> External Secrets Operator ready."
kubectl get pods -n external-secrets
echo
echo "Next: helm upgrade --install devroom helm/devroom -n devroom \\"
echo "        --create-namespace -f helm/devroom/values-eks.yaml"
