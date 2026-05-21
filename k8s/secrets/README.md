# Secrets

Dev-secrets för lokal Minikube-demo. **Inga production-värden ska någonsin commit:as här.**

## Filerna

| Fil | Status | Innehåll |
|---|---|---|
| `secrets.yaml.template` | commit:ad | Mall med `__PLACEHOLDER__`-strängar |
| `render-secrets.sh` | commit:ad | Genererar `secrets.yaml` från mallen |
| `secrets.yaml` | gitignored | Det renderade resultatet med faktiska värden |

## Användning

```bash
bash render-secrets.sh
kubectl apply -f secrets.yaml
```

Default-värden matchar `docker-compose.yml` så att Minikube-demon "bara fungerar".

För egna värden, sätt env-vars först:

```bash
DB_PASSWORD=hemligt \
GATEWAY_CLIENT_SECRET=från-vault \
BOT_SERVICE_CLIENT_SECRET=från-vault \
  bash render-secrets.sh
```

## Vad finns i secrets.yaml

3 K8s `Secret`-resurser i namespace `devroom`:

1. **db-credentials** — Postgres user/pass (delas mellan alla 3 db-instanser)
2. **rabbitmq-credentials** — RabbitMQ user/pass
3. **oauth-client-secrets** — Authorization Server client-secrets för Gateway (Authorization Code-flödet) och Bot Service (Client Credentials-flödet)

## Vad finns INTE i secrets.yaml (medvetet)

- **JWT-nycklar** — Auth Service genererar sitt RSA-keypair in-memory vid uppstart. Resource Servers (User, Message, Gateway) verifierar JWTs via JWKS från `http://auth-service:8081/.well-known/jwks.json` dynamiskt. Inga PEM-filer behövs i clustret.
- **Service-JWTs för Bot Service** — Bot Service använder OAuth2 Client Credentials grant istället för pre-issued JWTs. Den `bot-service-client-secret` ovan är det enda Bot Service behöver för att begära sina egna tokens.

## Prod

I prod ska Secrets skapas via en av:

- `kubectl create secret generic <name> --from-literal=key=value` (kort-livs-bruk)
- Sealed Secrets / SOPS / External Secrets Operator (gitops-vänligt)
- Cloud KMS-integration (AWS Secrets Manager, GCP Secret Manager, Azure Key Vault)

Aldrig `kubectl apply -f secrets.yaml` med plaintext-värden mot ett prod-kluster.
