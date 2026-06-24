#!/usr/bin/env bash
# Split-horizon DNS: make in-cluster pods resolve the ingress hostnames to
# Traefik, so the OAuth issuer (http://auth.devroom.local) is the same URL
# inside and outside the cluster. Adds CoreDNS rewrite rules.
set -euo pipefail

HOST1=devroom.local
HOST2=auth.devroom.local
TARGET=traefik.traefik.svc.cluster.local

if kubectl get configmap coredns -n kube-system -o jsonpath='{.data.Corefile}' | grep -q "rewrite name ${HOST1}"; then
  echo "==> CoreDNS already patched"
else
  echo "==> Patching CoreDNS: rewrite ${HOST1}/${HOST2} -> ${TARGET}"
  kubectl get configmap coredns -n kube-system -o json > /tmp/coredns.json
  python3 - "$HOST1" "$HOST2" "$TARGET" <<'PY' > /tmp/coredns-patched.json
import json, sys
host1, host2, target = sys.argv[1], sys.argv[2], sys.argv[3]
d = json.load(open('/tmp/coredns.json'))
cf = d['data']['Corefile']
inject = f'    rewrite name {host1} {target}\n    rewrite name {host2} {target}\n'
lines = cf.splitlines(keepends=True)
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
