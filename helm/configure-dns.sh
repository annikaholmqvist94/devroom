#!/usr/bin/env bash
# Split-horizon DNS: make in-cluster pods resolve the ingress hostnames to
# Traefik, so the OAuth issuer (http://auth.devroom.local) and Grafana
# (http://grafana.devroom.local) are the same URL inside and outside the
# cluster. Adds/refreshes CoreDNS rewrite rules (idempotent — re-runnable).
set -euo pipefail

HOST1=devroom.local
HOST2=auth.devroom.local
HOST3=grafana.devroom.local
TARGET=traefik.traefik.svc.cluster.local

if kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}' | grep -q "rewrite name ${HOST3}"; then
  echo "==> CoreDNS already patched (grafana host present)"
else
  echo "==> Patching CoreDNS: rewrite ${HOST1}/${HOST2}/${HOST3} -> ${TARGET}"
  kubectl get configmap coredns -n kube-system -o json > /tmp/coredns.json
  python3 - "$HOST1" "$HOST2" "$HOST3" "$TARGET" <<'PY' > /tmp/coredns-patched.json
import json, sys
host1, host2, host3, target = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
d = json.load(open('/tmp/coredns.json'))
cf = d['data']['Corefile']
# Drop any existing devroom rewrites so re-runs don't duplicate lines.
lines = [ln for ln in cf.splitlines(keepends=True)
         if not ('rewrite name' in ln and 'devroom.local' in ln)]
inject = (f'    rewrite name {host1} {target}\n'
          f'    rewrite name {host2} {target}\n'
          f'    rewrite name {host3} {target}\n')
out, done = [], False
for ln in lines:
    out.append(ln)
    if not done and ln.strip() == 'ready':
        out.append(inject); done = True
if not done:                       # fallback: insert before first 'forward'
    out, done = [], False
    for ln in lines:
        if not done and ln.strip().startswith('forward'):
            out.append(inject); done = True
        out.append(ln)
d['data']['Corefile'] = ''.join(out)
json.dump(d, open('/tmp/coredns-patched.json', 'w'))
PY
  kubectl apply -f /tmp/coredns-patched.json
  kubectl -n kube-system rollout restart deployment coredns
  kubectl -n kube-system rollout status deployment coredns --timeout=60s
fi
