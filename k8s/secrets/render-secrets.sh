#!/usr/bin/env bash
# Renderar k8s/secrets/secrets.yaml från secrets.yaml.template.
# Default-värden matchar docker-compose så lokal Minikube-demo "bara fungerar".
# Override genom att sätta env-vars innan du kör scriptet.
#
#   DB_PASSWORD=hemligt BOT_SERVICE_CLIENT_SECRET=annan bash render-secrets.sh

set -euo pipefail
cd "$(dirname "$0")"

: "${DB_USERNAME:=dbuser}"
: "${DB_PASSWORD:=dbpass}"
: "${RABBITMQ_USERNAME:=devroom}"
: "${RABBITMQ_PASSWORD:=devroom}"
: "${GATEWAY_CLIENT_SECRET:=dev-gateway-secret}"
: "${BOT_SERVICE_CLIENT_SECRET:=dev-bot-secret}"

# OpenRouter API key för Nordic Dev Mentor (LLM-provider). Försök i ordning:
# 1) env-var OPENROUTER_API_KEY, 2) ~/IdeaProjects/dev-mentor/.env, 3) tom string.
if [ -z "${OPENROUTER_API_KEY:-}" ] && [ -f "${HOME}/IdeaProjects/dev-mentor/.env" ]; then
    OPENROUTER_API_KEY=$(grep -E "^OPENROUTER_API_KEY=" "${HOME}/IdeaProjects/dev-mentor/.env" | head -1 | cut -d= -f2- || true)
fi
: "${OPENROUTER_API_KEY:=}"
if [ -z "$OPENROUTER_API_KEY" ]; then
    echo "WARNING: OPENROUTER_API_KEY empty — dev-mentor will return 503 LLM_UNAVAILABLE." >&2
fi

sed \
  -e "s|__DB_USERNAME__|${DB_USERNAME}|g" \
  -e "s|__DB_PASSWORD__|${DB_PASSWORD}|g" \
  -e "s|__RABBITMQ_USERNAME__|${RABBITMQ_USERNAME}|g" \
  -e "s|__RABBITMQ_PASSWORD__|${RABBITMQ_PASSWORD}|g" \
  -e "s|__GATEWAY_CLIENT_SECRET__|${GATEWAY_CLIENT_SECRET}|g" \
  -e "s|__BOT_SERVICE_CLIENT_SECRET__|${BOT_SERVICE_CLIENT_SECRET}|g" \
  -e "s|__OPENROUTER_API_KEY__|${OPENROUTER_API_KEY}|g" \
  secrets.yaml.template > secrets.yaml

echo "Wrote $(pwd)/secrets.yaml (gitignored)"
