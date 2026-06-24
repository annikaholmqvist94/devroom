#!/usr/bin/env bash
# One-time ingress setup: Traefik + CoreDNS. Run once per cluster, BEFORE
# deploying the app — so app pods resolve the issuer hostname from first boot.
set -euo pipefail
cd "$(dirname "$0")"

bash install-traefik.sh
bash configure-dns.sh

cat <<'EOF'

============================================================
Ingress installed. Two manual steps remain (need sudo):

1. Map the hostnames to localhost in /etc/hosts:
     echo "127.0.0.1 devroom.local auth.devroom.local" | sudo tee -a /etc/hosts

2. Start the Minikube tunnel (keep it running in its own terminal):
     minikube tunnel

Then open http://devroom.local in a browser.
============================================================
EOF
