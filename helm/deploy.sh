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
