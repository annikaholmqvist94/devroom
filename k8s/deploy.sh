#!/usr/bin/env bash
# One-command deploy of Devroom on Minikube.
#
# Prerequisites:
#   - Docker Desktop or compatible daemon running
#   - minikube + kubectl installed
#   - Minikube already started: minikube start --driver=docker --memory=6144 --cpus=4
#   - Nordic Dev Mentor cloned at ${DEV_MENTOR_PATH:-../dev-mentor}
#
# Usage:
#   bash k8s/deploy.sh

set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"
DEV_MENTOR_PATH="${DEV_MENTOR_PATH:-${HOME}/IdeaProjects/dev-mentor}"

echo "==> Verifying prerequisites"
command -v minikube >/dev/null || { echo "minikube not installed"; exit 1; }
command -v kubectl  >/dev/null || { echo "kubectl not installed"; exit 1; }
command -v docker   >/dev/null || { echo "docker not installed"; exit 1; }
minikube status >/dev/null || { echo "minikube is not running. Run: minikube start --driver=docker --memory=6144 --cpus=4"; exit 1; }
[ -d "$DEV_MENTOR_PATH" ] || { echo "Nordic Dev Mentor not found at $DEV_MENTOR_PATH (override with DEV_MENTOR_PATH)"; exit 1; }

echo "==> Pointing Docker CLI at Minikube's Docker daemon"
eval "$(minikube docker-env)"

echo "==> Building backend service images"
for svc in auth-service user-service message-service gateway bot-service; do
  echo "  → devroom/${svc}:latest"
  DOCKER_BUILDKIT=1 docker build -f "services/${svc}/Dockerfile" -t "devroom/${svc}:latest" "$REPO_ROOT" >/dev/null
done

echo "==> Building frontend image"
DOCKER_BUILDKIT=1 docker build -f frontend/Dockerfile -t devroom/frontend:latest "$REPO_ROOT" >/dev/null

echo "==> Building dev-mentor image from $DEV_MENTOR_PATH"
DOCKER_BUILDKIT=1 docker build -t devroom/dev-mentor:latest "$DEV_MENTOR_PATH" >/dev/null

echo "==> Creating namespace"
kubectl apply -f k8s/namespace.yaml

echo "==> Rendering and applying secrets"
bash k8s/secrets/render-secrets.sh
kubectl apply -f k8s/secrets/secrets.yaml

echo "==> Applying infra (Postgres × 3, RabbitMQ)"
kubectl apply -f k8s/postgres/
kubectl apply -f k8s/rabbitmq/

echo "==> Waiting for infra readiness (timeout 180s)"
kubectl wait -n devroom --for=condition=ready pod -l app=auth-db    --timeout=180s
kubectl wait -n devroom --for=condition=ready pod -l app=user-db    --timeout=180s
kubectl wait -n devroom --for=condition=ready pod -l app=message-db --timeout=180s
kubectl wait -n devroom --for=condition=ready pod -l app=rabbitmq   --timeout=180s

echo "==> Applying application services"
kubectl apply -f k8s/dev-mentor.yaml
kubectl apply -f k8s/auth-service.yaml
kubectl wait -n devroom --for=condition=available deployment/auth-service --timeout=240s
kubectl apply -f k8s/user-service.yaml
kubectl apply -f k8s/message-service.yaml
kubectl apply -f k8s/gateway.yaml
kubectl apply -f k8s/bot-service.yaml
kubectl apply -f k8s/frontend.yaml

echo "==> Waiting for all deployments to become available (timeout 300s)"
kubectl wait -n devroom --for=condition=available deployment --all --timeout=300s

cat <<EOF

============================================================
Deploy complete.

Pods:
$(kubectl get pods -n devroom)

Open the demo:
  kubectl port-forward -n devroom svc/frontend 3000:3000 &
  kubectl port-forward -n devroom svc/gateway  8080:8080 &
  kubectl port-forward -n devroom svc/auth-service 8081:8081 &
  kubectl port-forward -n devroom svc/rabbitmq 15672:15672 &

Then visit:
  - http://localhost:3000           — frontend (signup + chat)
  - http://localhost:15672          — RabbitMQ management UI (devroom/devroom)

Tear down with:
  kubectl delete namespace devroom
============================================================
EOF
