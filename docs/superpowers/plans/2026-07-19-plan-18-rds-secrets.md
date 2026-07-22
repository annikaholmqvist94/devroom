# Plan 18 — RDS + Secrets Manager + External Secrets Operator: Implementationsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beskriva Devrooms tre Postgres som Amazon RDS-instanser i Terraform, och en komplett kedja från AWS Secrets Manager till poddarnas miljövariabler via External Secrets Operator — verifierat statiskt, utan att någonsin köra `apply`.

**Architecture:** `terraform/` växer med `rds.tf` (tre `aws_db_instance` via `for_each`, delad subnet group, security group som bara släpper in EKS-noderna, parameter group) och `irsa.tf` (IAM-roll som ESO:s service account får anta via EKS OIDC-provider). Helm-chartet växer med `SecretStore`, `ExternalSecret` och en annoterad `ServiceAccount` bakom `externalSecrets.enabled`, och den delade `db-credentials`-Secreten delas upp i tre per-databas-secrets i **alla** miljöer. `app-deployment.yaml` rörs inte.

**Tech Stack:** Terraform 1.15.8 (lokalt) / 1.9.5 (CI), AWS provider `~> 5.0`, `terraform-aws-modules/vpc` ~> 5, `terraform-aws-modules/eks` ~> 20, Helm 4.2.1, External Secrets Operator chart 2.8.0 (app v2.8.0).

## Global Constraints

- **`terraform apply` körs ALDRIG.** Endast `fmt`, `init -backend=false`, `validate` och `plan`. Ingen AWS-resurs skapas. Utan kostnad.
- **Strikt wiring:** varje AWS-resurs måste ha en identifierbar konsument i Devroom — en tjänst, en miljövariabel, en nyckel i `values-eks.yaml` eller en kodrad. Saknas konsumenten åker resursen ut.
- **ESO API-version är `external-secrets.io/v1`.** Verifierat mot chart 2.8.0:s CRD:er: `v1` är `served: true, storage: true`; `v1beta1` är `served: false` och skulle avvisas av API-servern. Använd aldrig `v1beta1`.
- **Minikube-vägen får inte gå sönder.** Enda tillåtna skillnaden i `helm template` utan flaggor är secret-namnen (`db-credentials` → tre per-databas-secrets).
- **`app-deployment.yaml` ändras inte** i någon task.
- **Inga hemligheter i Terraform-state:** `manage_master_user_password = true`; `random_password` är förbjudet.
- Commit-konvention: subject på engelska (Conventional Commits), body på svenska, **ingen `Co-Authored-By`-rad**.
- Spec: `docs/superpowers/specs/2026-07-19-plan-18-rds-secrets-design.md`.

## Filstruktur

| Fil | Ansvar |
|---|---|
| `terraform/rds.tf` (ny) | Tre RDS-instanser + subnet group + security group + parameter group |
| `terraform/irsa.tf` (ny) | IAM-roll + scoped policy för ESO:s service account |
| `terraform/variables.tf` (ändras) | Sex nya db-variabler + två ESA-identitetsvariabler |
| `terraform/outputs.tf` (ändras) | `rds_endpoints`, `rds_secret_arns`, `eso_role_arn` |
| `terraform/terraform.tfvars.dev.example`, `.prod.example` (nya) | Kostnadstrappan |
| `helm/devroom/values.yaml` (ändras) | Tre db-secrets, sex ompekade `secretEnv`-rader, nytt `externalSecrets`-block |
| `helm/devroom/templates/postgres-statefulset.yaml` (ändras) | Härleder secret-namn ur loop-nyckeln |
| `helm/devroom/templates/secret.yaml` (ändras) | Hoppar över db-secrets när ESO äger dem |
| `helm/devroom/templates/externalsecret.yaml` (ny) | Tre `ExternalSecret` |
| `helm/devroom/templates/secretstore.yaml` (ny) | `SecretStore` + annoterad `ServiceAccount` |
| `helm/devroom/values-eks.yaml` (ändras) | ESO på, RDS-endpoints, ECR-images |
| `helm/install-external-secrets.sh` (ny) | Installerar ESO som egen release |
| `docs/adr/0017-rds-secrets-manager.md` (ny) | Beslutet |

---

### Task 1: Terraform — RDS-instanserna

**Files:**
- Create: `terraform/rds.tf`
- Create: `terraform/terraform.tfvars.dev.example`
- Create: `terraform/terraform.tfvars.prod.example`
- Modify: `terraform/variables.tf` (lägg till i slutet)
- Modify: `terraform/outputs.tf` (lägg till i slutet)

**Interfaces:**
- Consumes: `module.vpc.private_subnets`, `module.vpc.vpc_id`, `module.eks.node_security_group_id`, `var.cluster_name` (alla finns sedan Plan 17).
- Produces: `aws_db_instance.devroom` (map nycklad `auth-db`/`user-db`/`message-db`), `local.databases`, outputs `rds_endpoints` och `rds_secret_arns`. Task 2 konsumerar `aws_db_instance.devroom[*].master_user_secret[0].secret_arn`.

- [ ] **Step 1: Fånga nuvarande plan-baseline**

Vi behöver veta hur många resurser `plan` rapporterar innan ändringen, för att kunna visa exakt vad Task 1 tillför.

Run:
```bash
cd terraform && terraform init -backend=false && terraform validate
```
Expected: `Success! The configuration is valid.`

- [ ] **Step 2: Lägg till variablerna**

Lägg till sist i `terraform/variables.tf`:

```hcl
variable "db_engine_version" {
  description = "PostgreSQL major version for RDS (AWS selects the latest minor)"
  type        = string
  default     = "16"
}

variable "db_instance_class" {
  description = "RDS instance class. Graviton burstable classes are the cheapest option."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Initial storage per RDS instance, in GiB"
  type        = number
  default     = 20
}

variable "db_max_allocated_storage" {
  description = "Upper bound for RDS storage autoscaling, in GiB"
  type        = number
  default     = 100
}

variable "db_multi_az" {
  description = "Run a standby in a second AZ. Doubles instance cost — the single largest cost lever."
  type        = bool
  default     = false
}

variable "db_backup_retention_period" {
  description = "Automated backup retention, in days"
  type        = number
  default     = 1
}
```

- [ ] **Step 3: Skriv `terraform/rds.tf`**

```hcl
# Devroom's three bounded contexts get one RDS instance each (ADR-0001, ADR-0005).
# Instance count is a linear multiplier; multi_az and instance_class are what
# actually drive cost — both are variables (see terraform.tfvars.*.example).
locals {
  databases = {
    auth-db    = { db_name = "authdb" }
    user-db    = { db_name = "userdb" }
    message-db = { db_name = "messagedb" }
  }
}

resource "aws_db_subnet_group" "devroom" {
  name       = "${var.cluster_name}-db"
  subnet_ids = module.vpc.private_subnets

  tags = { Project = "devroom" }
}

# Postgres reachable only from the EKS worker nodes — never from the internet.
resource "aws_security_group" "rds" {
  name        = "${var.cluster_name}-rds"
  description = "PostgreSQL access from EKS worker nodes only"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description     = "PostgreSQL from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [module.eks.node_security_group_id]
  }

  tags = { Project = "devroom" }
}

# Slow-query logging feeds the Loki/Grafana stack from Phase B.
resource "aws_db_parameter_group" "devroom" {
  name        = "${var.cluster_name}-postgres16"
  family      = "postgres16"
  description = "Devroom PostgreSQL 16 parameters"

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  tags = { Project = "devroom" }
}

resource "aws_db_instance" "devroom" {
  for_each = local.databases

  identifier     = "${var.cluster_name}-${each.key}"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = each.value.db_name
  username = "devroom"

  # RDS generates and rotates the master password into Secrets Manager itself,
  # so the password never touches Terraform state.
  manage_master_user_password = true

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true

  multi_az               = var.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.devroom.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.devroom.name

  backup_retention_period = var.db_backup_retention_period
  skip_final_snapshot     = true
  deletion_protection     = false

  tags = {
    Project = "devroom"
    Service = each.key
  }
}
```

- [ ] **Step 4: Lägg till outputs**

Lägg till sist i `terraform/outputs.tf`:

```hcl
output "rds_endpoints" {
  description = "RDS endpoint (host:port) per database — goes into values-eks.yaml"
  value       = { for k, db in aws_db_instance.devroom : k => db.endpoint }
}

output "rds_secret_arns" {
  description = "Secrets Manager ARN of the RDS-managed master credential, per database"
  value       = { for k, db in aws_db_instance.devroom : k => db.master_user_secret[0].secret_arn }
}
```

- [ ] **Step 5: Skriv de två tfvars-exemplen**

`terraform/terraform.tfvars.dev.example`:

```hcl
# Development profile — cheapest configuration that still runs.
# Copy to terraform.tfvars (gitignored) before running plan.
region       = "eu-north-1"
cluster_name = "devroom"

db_instance_class          = "db.t4g.micro"
db_multi_az                = false
db_allocated_storage       = 20
db_backup_retention_period = 1
```

`terraform/terraform.tfvars.prod.example`:

```hcl
# Production profile — multi-AZ standby and a week of backups.
# multi_az doubles instance cost; that is the deliberate trade-off.
region       = "eu-north-1"
cluster_name = "devroom"

db_instance_class          = "db.t4g.small"
db_multi_az                = true
db_allocated_storage       = 50
db_backup_retention_period = 7
```

- [ ] **Step 6: Formatera och validera**

Run:
```bash
cd terraform && terraform fmt -recursive && terraform validate
```
Expected: `Success! The configuration is valid.`

Om `validate` klagar på `master_user_secret`: attributet heter `secret_arn` (inte `arn`) och nås som `master_user_secret[0].secret_arn`.

- [ ] **Step 7: Kör plan mot kontot**

Run:
```bash
cd terraform && terraform plan
```
Expected: planen går igenom utan fel och rapporterar fler resurser att skapa än Plan 17:s 65 — de tillkommande är 3 × `aws_db_instance`, 1 × `aws_db_subnet_group`, 1 × `aws_security_group`, 1 × `aws_db_parameter_group`.

**Kör INTE `apply`.** Om kommandot frågar efter bekräftelse har fel kommando körts — avbryt med Ctrl-C.

- [ ] **Step 8: Commit**

```bash
git add terraform/rds.tf terraform/variables.tf terraform/outputs.tf \
        terraform/terraform.tfvars.dev.example terraform/terraform.tfvars.prod.example
git commit -F- <<'EOF'
feat(terraform): three RDS PostgreSQL instances with cost parameterised

En aws_db_instance per bounded context (auth/user/message) via for_each,
med delad subnet group i VPC:ns privata subnät, en security group som bara
släpper in trafik från EKS-noderna på 5432, och en parameter group som
sätter log_min_duration_statement för slow query-loggning.

manage_master_user_password = true låter RDS skapa och rotera master-
lösenordet i Secrets Manager, så det aldrig hamnar i Terraform-state.

Kostnaden parametriseras istället för att arkitekturen kompromissas:
multi_az, instance_class, storage och backup-retention är variabler, och
tfvars-exemplen visar dev- respektive prod-profilen.

Fortsatt plan-only — verifierat med fmt, validate och plan. Ingen apply.
EOF
```

---

### Task 2: Terraform — IRSA-roll för External Secrets Operator

**Files:**
- Create: `terraform/irsa.tf`
- Modify: `terraform/variables.tf` (lägg till i slutet)
- Modify: `terraform/outputs.tf` (lägg till i slutet)

**Interfaces:**
- Consumes: `aws_db_instance.devroom` från Task 1, `module.eks.oidc_provider_arn` och `module.eks.oidc_provider` från Plan 17.
- Produces: output `eso_role_arn` (sträng). Task 5 skriver in det värdet som platshållare `<ESO_ROLE_ARN>` i `values-eks.yaml`, som Task 4:s `ServiceAccount`-mall annoterar sig med.

- [ ] **Step 1: Lägg till identitetsvariablerna**

Service-accounten som får anta rollen ligger i **Devrooms** namespace, inte i ESO:s — ESO byter till den identiteten när den läser secreten. Lägg till sist i `terraform/variables.tf`:

```hcl
variable "app_namespace" {
  description = "Kubernetes namespace the Devroom chart is released into"
  type        = string
  default     = "devroom"
}

variable "eso_service_account" {
  description = "Name of the service account the chart creates for External Secrets Operator to assume"
  type        = string
  default     = "devroom-external-secrets"
}
```

- [ ] **Step 2: Skriv `terraform/irsa.tf`**

```hcl
# IRSA: the service account the Devroom chart creates may assume this role via
# the cluster's OIDC provider. The role may read exactly the three RDS master
# secrets — nothing else in Secrets Manager.
data "aws_iam_policy_document" "eso_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:${var.app_namespace}:${var.eso_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eso" {
  name               = "${var.cluster_name}-external-secrets"
  description        = "Read RDS master credentials from Secrets Manager"
  assume_role_policy = data.aws_iam_policy_document.eso_assume_role.json

  tags = { Project = "devroom" }
}

data "aws_iam_policy_document" "eso_read_rds_secrets" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]

    resources = [
      for db in aws_db_instance.devroom : db.master_user_secret[0].secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "eso_read_rds_secrets" {
  name   = "read-rds-master-secrets"
  role   = aws_iam_role.eso.id
  policy = data.aws_iam_policy_document.eso_read_rds_secrets.json
}
```

Rollen får ingen `kms:Decrypt`-sats: RDS-hanterade secrets krypteras med den AWS-hanterade nyckeln `aws/secretsmanager`, och för principals i samma konto räcker `GetSecretValue`. Byts nyckeln till en kundhanterad CMK måste `kms:Decrypt` läggas till — det noteras i ADR-0017.

- [ ] **Step 3: Lägg till output**

Lägg till sist i `terraform/outputs.tf`:

```hcl
output "eso_role_arn" {
  description = "IAM role ARN for the chart's external-secrets service account annotation"
  value       = aws_iam_role.eso.arn
}
```

- [ ] **Step 4: Formatera, validera, planera**

Run:
```bash
cd terraform && terraform fmt -recursive && terraform validate && terraform plan
```
Expected: `Success! The configuration is valid.` följt av en plan som nu även innehåller `aws_iam_role.eso` och `aws_iam_role_policy.eso_read_rds_secrets`. **Ingen `apply`.**

- [ ] **Step 5: Commit**

```bash
git add terraform/irsa.tf terraform/variables.tf terraform/outputs.tf
git commit -F- <<'EOF'
feat(terraform): IRSA role letting External Secrets Operator read RDS secrets

En IAM-roll som chartets service account (devroom-external-secrets i
devroom-namespacet) får anta via EKS OIDC-provider. Trust policy låser både
sub och aud, så bara den service accounten kan anta rollen.

Behörigheten är scopad till exakt de tre RDS-master-secreternas ARN:er —
inte secretsmanager:* och inte resource "*".

Ingen kms:Decrypt behövs: RDS-hanterade secrets använder den AWS-hanterade
nyckeln aws/secretsmanager, där GetSecretValue räcker inom samma konto.
EOF
```

---

### Task 3: Helm — dela upp `db-credentials` i alla miljöer

Detta är planens enda ändring som rör den fungerande Minikube-vägen. Den verifieras med en diff mot nuvarande render.

**Files:**
- Modify: `helm/devroom/values.yaml` (`secrets:`-blocket + sex `secretEnv`-rader)
- Modify: `helm/devroom/templates/postgres-statefulset.yaml`
- Modify: `helm/devroom/templates/secret.yaml`

**Interfaces:**
- Consumes: `.Values.databases` (finns redan, nycklad `auth-db`/`user-db`/`message-db`).
- Produces: namnkonventionen `<db-nyckel>-credentials` — alltså `auth-db-credentials`, `user-db-credentials`, `message-db-credentials`. Task 4:s `ExternalSecret` skapar Secrets med **exakt** dessa namn.

- [ ] **Step 1: Fånga nuvarande render som facit**

Run:
```bash
helm template devroom helm/devroom > /tmp/devroom-before.yaml && wc -l /tmp/devroom-before.yaml
```
Expected: en YAML-fil på några hundra rader. Detta är facit — efter ändringen får bara secret-namnen skilja.

- [ ] **Step 2: Dela upp `secrets:`-blocket**

I `helm/devroom/values.yaml`, ersätt:

```yaml
secrets:
  db-credentials:
    username: dbuser
    password: dbpass
```

med:

```yaml
secrets:
  # One credential per database — three RDS instances have three independent
  # master passwords, so a shared secret cannot be synced from Secrets Manager.
  auth-db-credentials:
    username: dbuser
    password: dbpass
  user-db-credentials:
    username: dbuser
    password: dbpass
  message-db-credentials:
    username: dbuser
    password: dbpass
```

- [ ] **Step 3: Peka om de sex `secretEnv`-raderna**

I `helm/devroom/values.yaml`, under `services.auth-service.secretEnv`, ändra de två första raderna till:

```yaml
      - { name: SPRING_DATASOURCE_USERNAME, secret: auth-db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: auth-db-credentials, key: password }
```

Under `services.user-service.secretEnv`:

```yaml
      - { name: SPRING_DATASOURCE_USERNAME, secret: user-db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: user-db-credentials, key: password }
```

Under `services.message-service.secretEnv`:

```yaml
      - { name: SPRING_DATASOURCE_USERNAME, secret: message-db-credentials, key: username }
      - { name: SPRING_DATASOURCE_PASSWORD, secret: message-db-credentials, key: password }
```

Rör inte raderna för `SPRING_RABBITMQ_*`, `GATEWAY_CLIENT_SECRET` eller `BOT_CLIENT_SECRET`.

- [ ] **Step 4: Härled secret-namnet i StatefulSet-mallen**

I `helm/devroom/templates/postgres-statefulset.yaml`, ersätt båda förekomsterna av det hårdkodade namnet. Före:

```yaml
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
```

Efter:

```yaml
        - name: POSTGRES_USER
          valueFrom:
            secretKeyRef:
              name: {{ $name }}-credentials
              key: username
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: {{ $name }}-credentials
              key: password
```

`$name` är loop-variabeln från `range $name, $db := .Values.databases` högst upp i filen, alltså `auth-db`/`user-db`/`message-db`.

- [ ] **Step 5: Låt `secret.yaml` hoppa över db-secrets när ESO äger dem**

`secret.yaml` renderar **alla** secrets, även `rabbitmq-credentials` och `oauth-client-secrets` som ESO inte rör. Bara de tre db-secreterna får hoppas över. Ersätt hela filens innehåll med:

```yaml
{{- range $name, $data := .Values.secrets }}
{{- /* Skip database credentials when External Secrets Operator owns them —
       rabbitmq and oauth secrets are still rendered from values. */}}
{{- if not (and $.Values.externalSecrets.enabled (hasKey $.Values.databases (trimSuffix "-credentials" $name))) }}
---
apiVersion: v1
kind: Secret
metadata:
  name: {{ $name }}
  labels:
    {{- include "devroom.labels" $ | nindent 4 }}
type: Opaque
stringData:
{{- range $key, $value := $data }}
  {{ $key }}: {{ $value | quote }}
{{- end }}
{{- end }}
{{- end }}
```

- [ ] **Step 6: Lägg till `externalSecrets`-blocket med togglen av**

Mallen i steg 5 läser `.Values.externalSecrets.enabled`, så nyckeln måste finnas. Lägg till i `helm/devroom/values.yaml`, efter `secrets:`-blocket:

```yaml
externalSecrets:
  # Off by default: Minikube has no AWS to sync from.
  enabled: false
  region: eu-north-1
  refreshInterval: 1h
  secretStoreName: devroom-aws
  serviceAccountName: devroom-external-secrets
  roleArn: ""
```

- [ ] **Step 7: Rendera och diffa mot facit**

Run:
```bash
helm lint helm/devroom && helm template devroom helm/devroom > /tmp/devroom-after.yaml \
  && diff /tmp/devroom-before.yaml /tmp/devroom-after.yaml
```
Expected: diffen innehåller **enbart** rader där `db-credentials` blivit `auth-db-credentials`, `user-db-credentials` eller `message-db-credentials`, plus att en Secret blivit tre. Inga ändrade env-var-namn, inga borttagna nycklar, inga ändrade tjänster, inga ändrade StatefulSet-namn.

Om diffen visar något annat — stoppa och rätta innan commit.

- [ ] **Step 8: Bekräfta att inga hårdkodade referenser är kvar**

Run:
```bash
grep -rn 'db-credentials' helm/ | grep -v -- '-db-credentials'
```
Expected: ingen träff. Varje kvarvarande förekomst ska ha ett databasprefix.

- [ ] **Step 9: Commit**

```bash
git add helm/devroom/values.yaml helm/devroom/templates/postgres-statefulset.yaml helm/devroom/templates/secret.yaml
git commit -F- <<'EOF'
refactor(helm): split shared db-credentials into one secret per database

Tre RDS-instanser med RDS-hanterade master-lösenord får tre oberoende
lösenord, så den delade db-credentials-Secreten går inte att synka från
Secrets Manager. Den delas upp i auth-db-credentials, user-db-credentials
och message-db-credentials.

Uppdelningen görs i alla miljöer, inte bara på EKS. Skälet är Helms
merge-semantik: maps djup-mergeas men listor ersätts rakt av, och secretEnv
är en lista. Hade values.yaml behållit den delade Secreten skulle
values-eks.yaml tvingats skriva om hela listan för alla tre tjänsterna —
inklusive rabbitmq- och oauth-raderna — vilket garanterat drivit isär.

postgres-statefulset.yaml härleder nu namnet ur loop-nyckeln istället för
att hårdkoda det, och secret.yaml hoppar över just db-secreterna när ESO
äger dem (rabbitmq och oauth renderas fortfarande ur values).

Verifierat med helm template diffad mot föregående render: enda skillnaden
är secret-namnen.
EOF
```

---

### Task 4: Helm — SecretStore, ServiceAccount och ExternalSecret

**Files:**
- Create: `helm/devroom/templates/secretstore.yaml`
- Create: `helm/devroom/templates/externalsecret.yaml`
- Modify: `helm/devroom/values.yaml` (`databases`-blocket får `secretName`-nyckel)

**Interfaces:**
- Consumes: `.Values.externalSecrets` (Task 3, steg 6), `.Values.databases` (befintlig), namnkonventionen `<db-nyckel>-credentials` (Task 3).
- Produces: Kubernetes-Secrets med namnen `auth-db-credentials`, `user-db-credentials`, `message-db-credentials` och nycklarna `username`/`password` — exakt vad `secretEnv` i Task 3 refererar.

- [ ] **Step 1: Lägg till `secretName` i `databases`-blocket**

I `helm/devroom/values.yaml`, ersätt:

```yaml
  auth-db:    { database: authdb,    storage: 1Gi }
  user-db:    { database: userdb,    storage: 1Gi }
  message-db: { database: messagedb, storage: 1Gi }
```

med:

```yaml
  # secretName is the Secrets Manager secret holding the RDS master credential.
  # Empty locally (Minikube has no AWS); values-eks.yaml fills it in.
  auth-db:    { database: authdb,    storage: 1Gi, secretName: "" }
  user-db:    { database: userdb,    storage: 1Gi, secretName: "" }
  message-db: { database: messagedb, storage: 1Gi, secretName: "" }
```

- [ ] **Step 2: Skriv `helm/devroom/templates/secretstore.yaml`**

`SecretStore` behöver en identitet att läsa Secrets Manager med. Chartet skapar därför en `ServiceAccount` som annoteras med IRSA-rollens ARN från Task 2.

```yaml
{{- if .Values.externalSecrets.enabled }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Values.externalSecrets.serviceAccountName }}
  labels:
    {{- include "devroom.labels" . | nindent 4 }}
  annotations:
    eks.amazonaws.com/role-arn: {{ .Values.externalSecrets.roleArn | quote }}
---
apiVersion: external-secrets.io/v1
kind: SecretStore
metadata:
  name: {{ .Values.externalSecrets.secretStoreName }}
  labels:
    {{- include "devroom.labels" . | nindent 4 }}
spec:
  provider:
    aws:
      service: SecretsManager
      region: {{ .Values.externalSecrets.region }}
      auth:
        jwt:
          serviceAccountRef:
            name: {{ .Values.externalSecrets.serviceAccountName }}
{{- end }}
```

`apiVersion` är `external-secrets.io/v1` — `v1beta1` är inte längre `served` i ESO 2.8.0.

- [ ] **Step 3: Skriv `helm/devroom/templates/externalsecret.yaml`**

```yaml
{{- if .Values.externalSecrets.enabled }}
{{- range $name, $db := .Values.databases }}
---
apiVersion: external-secrets.io/v1
kind: ExternalSecret
metadata:
  name: {{ $name }}-credentials
  labels:
    {{- include "devroom.labels" $ | nindent 4 }}
spec:
  refreshInterval: {{ $.Values.externalSecrets.refreshInterval }}
  secretStoreRef:
    name: {{ $.Values.externalSecrets.secretStoreName }}
    kind: SecretStore
  target:
    # Must match the secret names referenced by services' secretEnv.
    name: {{ $name }}-credentials
    creationPolicy: Owner
  data:
    - secretKey: username
      remoteRef:
        key: {{ $db.secretName | quote }}
        property: username
    - secretKey: password
      remoteRef:
        key: {{ $db.secretName | quote }}
        property: password
{{- end }}
{{- end }}
```

RDS lägger credentialen som ett JSON-objekt med fälten `username` och `password`; `property` plockar ut ett fält ur det.

- [ ] **Step 4: Verifiera att Minikube-vägen är oförändrad**

Run:
```bash
helm lint helm/devroom && helm template devroom helm/devroom > /tmp/devroom-task4.yaml \
  && diff /tmp/devroom-after.yaml /tmp/devroom-task4.yaml
```
Expected: **ingen diff alls.** Togglen är av, så varken `SecretStore`, `ServiceAccount` eller `ExternalSecret` får renderas.

- [ ] **Step 5: Verifiera att togglen på ger rätt objekt**

Run:
```bash
helm template devroom helm/devroom \
  --set externalSecrets.enabled=true \
  --set externalSecrets.roleArn=arn:aws:iam::123456789012:role/devroom-external-secrets \
  --set databases.auth-db.secretName=rds-auth \
  --set databases.user-db.secretName=rds-user \
  --set databases.message-db.secretName=rds-message \
  | grep -E '^kind:|^  name:' | grep -B1 -E 'ExternalSecret|SecretStore|ServiceAccount'
```
Expected: en `ServiceAccount` (`devroom-external-secrets`), en `SecretStore` (`devroom-aws`) och tre `ExternalSecret` (`auth-db-credentials`, `user-db-credentials`, `message-db-credentials`).

- [ ] **Step 6: Verifiera att db-secreterna INTE dubbelrenderas**

Run:
```bash
helm template devroom helm/devroom --set externalSecrets.enabled=true \
  | grep -A2 '^kind: Secret' | grep 'name:'
```
Expected: enbart `rabbitmq-credentials`, `oauth-client-secrets` och `dev-mentor-secrets`. Ingen `*-db-credentials` — de ägs av ESO nu.

- [ ] **Step 7: Commit**

```bash
git add helm/devroom/templates/secretstore.yaml helm/devroom/templates/externalsecret.yaml helm/devroom/values.yaml
git commit -F- <<'EOF'
feat(helm): sync RDS credentials from Secrets Manager via External Secrets

En SecretStore mot AWS Secrets Manager plus tre ExternalSecret som skriver
Kubernetes-Secrets med exakt de namn och nycklar som tjänsternas secretEnv
redan refererar. app-deployment.yaml är oförändrad — tjänsterna läser samma
miljövariabler i Minikube som på EKS.

Chartet skapar också den service account ESO byter identitet till,
annoterad med IRSA-rollens ARN från terraform/irsa.tf.

apiVersion är external-secrets.io/v1. Verifierat mot ESO chart 2.8.0:s
CRD:er att v1beta1 inte längre är served och skulle avvisas.

Allt bakom externalSecrets.enabled, default false — render utan flaggan är
bit-identisk med föregående commit.
EOF
```

---

### Task 5: ESO-installation, Postgres-toggle och `values-eks.yaml`

**Files:**
- Create: `helm/install-external-secrets.sh`
- Modify: `helm/devroom/values.yaml` (`infra:`-blocket)
- Modify: `helm/devroom/templates/postgres-statefulset.yaml` (gate-raden)
- Modify: `helm/devroom/templates/postgres-service.yaml` (gate-raden)
- Modify: `helm/devroom/values-eks.yaml`

**Interfaces:**
- Consumes: outputs `rds_endpoints`, `rds_secret_arns`, `eso_role_arn` från Task 1–2; togglarna från Task 3–4.
- Produces: `.Values.infra.postgres.enabled` (bool, default `true`) och en fullständig EKS-values-fil. Ingen senare task konsumerar dem.

- [ ] **Step 1: Skriv `helm/install-external-secrets.sh`**

Följer mönstret från `install-traefik.sh` / `install-monitoring.sh` / `install-logging.sh`.

```bash
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
```

Gör den körbar:
```bash
chmod +x helm/install-external-secrets.sh
```

- [ ] **Step 2: Inför `infra.postgres.enabled`**

`infra.enabled` gatar idag **tre** saker: Postgres-StatefulSets, deras Services, och RabbitMQ-deploymenten. På EKS ska databaserna flytta till RDS men brokern stanna in-cluster — en enda toggle klarar inte det. Utan en finkornig toggle skulle EKS-rendern antingen köra in-cluster-Postgres parallellt med RDS (inkoherent, och StatefulSetsen skulle dessutom starta med RDS master-lösenordet som ESO synkat) eller tappa RabbitMQ (appen går sönder).

I `helm/devroom/values.yaml`, ersätt:

```yaml
infra:
  enabled: true
  postgres:
    image: postgres:16-alpine
```

med:

```yaml
infra:
  # Gates the whole in-cluster infra block (Postgres + RabbitMQ).
  enabled: true
  postgres:
    # Turned off on EKS, where the databases live on RDS. RabbitMQ stays
    # in-cluster either way — Amazon MQ is out of scope.
    enabled: true
    image: postgres:16-alpine
```

Rör inte `infra.postgres.resources` eller `infra.rabbitmq`.

- [ ] **Step 3: Gata de två Postgres-mallarna**

I `helm/devroom/templates/postgres-statefulset.yaml`, ersätt första raden:

```yaml
{{- if .Values.infra.enabled }}
```

med:

```yaml
{{- if and .Values.infra.enabled .Values.infra.postgres.enabled }}
```

Gör exakt samma ändring på första raden i `helm/devroom/templates/postgres-service.yaml`.

**Rör inte `helm/devroom/templates/rabbitmq.yaml`** — den ska fortsätta styras enbart av `infra.enabled`.

- [ ] **Step 4: Verifiera att Minikube-vägen fortfarande är oförändrad**

Run:
```bash
helm lint helm/devroom && helm template devroom helm/devroom > /tmp/devroom-task5.yaml \
  && diff /tmp/devroom-after.yaml /tmp/devroom-task5.yaml
```
Expected: **ingen diff.** Båda togglarna är på som default, så rendern ska vara bit-identisk med Task 3:s resultat.

- [ ] **Step 5: Skriv om `helm/devroom/values-eks.yaml`**

Ersätt hela filen med:

```yaml
# Overrides for deploying the chart on EKS with images from ECR and databases
# on RDS.
#
# Illustrative only — Plan 17 and 18 are plan-only and never run `terraform
# apply`, so the placeholders below have no real values yet. Fill them from
# `terraform output` if this is ever deployed:
#   <ACCOUNT_ID>          aws sts get-caller-identity
#   <*_DB_ENDPOINT>       terraform output rds_endpoints
#   <*_DB_SECRET>         terraform output rds_secret_arns
#   <ESO_ROLE_ARN>        terraform output eso_role_arn
#
# Prerequisite: helm/install-external-secrets.sh
global:
  imageRegistry: "<ACCOUNT_ID>.dkr.ecr.eu-north-1.amazonaws.com/devroom"
  imageTag: latest
  imagePullPolicy: Always

# Databases move to RDS; RabbitMQ stays in-cluster (Amazon MQ is out of scope).
infra:
  enabled: true
  postgres:
    enabled: false

externalSecrets:
  enabled: true
  region: eu-north-1
  refreshInterval: 1h
  secretStoreName: devroom-aws
  serviceAccountName: devroom-external-secrets
  roleArn: "<ESO_ROLE_ARN>"

databases:
  auth-db:
    secretName: "<AUTH_DB_SECRET>"
  user-db:
    secretName: "<USER_DB_SECRET>"
  message-db:
    secretName: "<MESSAGE_DB_SECRET>"

services:
  auth-service:
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://<AUTH_DB_ENDPOINT>/authdb
  user-service:
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://<USER_DB_ENDPOINT>/userdb
  message-service:
    env:
      SPRING_DATASOURCE_URL: jdbc:postgresql://<MESSAGE_DB_ENDPOINT>/messagedb
```

`databases`-blocket behåller `database` och `storage` från `values.yaml` — Helm djup-mergear maps, så bara `secretName` tillkommer.

- [ ] **Step 6: Verifiera EKS-rendern i fyra avseenden**

Run:
```bash
helm template devroom helm/devroom -f helm/devroom/values-eks.yaml > /tmp/devroom-eks.yaml \
  && grep -c '^kind: StatefulSet' /tmp/devroom-eks.yaml || true
```
Expected: `0` — inga in-cluster-Postgres.

Run:
```bash
grep -A3 '^kind: Deployment' /tmp/devroom-eks.yaml | grep 'name: rabbitmq'
```
Expected: en träff — brokern finns kvar.

Run:
```bash
grep 'SPRING_DATASOURCE_URL' -A1 /tmp/devroom-eks.yaml | grep jdbc
```
Expected: tre rader som pekar på `<*_DB_ENDPOINT>`-platshållare, ingen på `auth-db:5432`.

Run:
```bash
grep -E '^kind: (SecretStore|ExternalSecret|ServiceAccount)' /tmp/devroom-eks.yaml | sort | uniq -c
```
Expected: 3 `ExternalSecret`, 1 `SecretStore`, 1 `ServiceAccount`.

- [ ] **Step 7: Verifiera att skriptet är syntaktiskt giltigt**

Run:
```bash
bash -n helm/install-external-secrets.sh && echo OK
```
Expected: `OK`

- [ ] **Step 8: Commit**

```bash
git add helm/install-external-secrets.sh helm/devroom/values-eks.yaml helm/devroom/values.yaml \
        helm/devroom/templates/postgres-statefulset.yaml helm/devroom/templates/postgres-service.yaml
git commit -F- <<'EOF'
feat(helm): External Secrets install script, Postgres toggle and EKS values

install-external-secrets.sh installerar ESO chart 2.8.0 i eget namespace,
samma mönster som install-traefik.sh och install-logging.sh.

Ny infra.postgres.enabled skiljer databaserna från brokern. Specen angav
infra.enabled: false för EKS, men den togglen gatar även RabbitMQ — som ska
stanna in-cluster. Utan uppdelningen skulle EKS-rendern antingen köra
in-cluster-Postgres parallellt med RDS eller tappa kön. Två gate-rader löser
det; rabbitmq.yaml styrs fortfarande enbart av infra.enabled.

values-eks.yaml pekar tjänsterna mot RDS-endpoints och slår på
ExternalSecrets-kedjan. Platshållarna är dokumenterade med vilket terraform
output-kommando som fyller i respektive värde.

Minikube-rendern är bit-identisk med föregående commit — båda togglarna är
på som default.
EOF
```

---

### Task 6: ADR-0017 och dokumentation

**Files:**
- Create: `docs/adr/0017-rds-secrets-manager.md`
- Modify: `terraform/README.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: alla tidigare tasks.
- Produces: inget kod-gränssnitt.

- [ ] **Step 1: Verifiera prisantagandena innan de skrivs ned**

Specen kräver att priser verifieras, inte citeras ur minnet.

Run:
```bash
aws pricing get-products --service-code AmazonRDS --region us-east-1 \
  --filters "Type=TERM_MATCH,Field=instanceType,Value=db.t4g.micro" \
            "Type=TERM_MATCH,Field=location,Value=EU (Stockholm)" \
            "Type=TERM_MATCH,Field=databaseEngine,Value=PostgreSQL" \
  --max-items 1 2>/dev/null | head -40
```

Om kommandot misslyckas (pricing-API:et kräver `us-east-1` och rätt behörighet): skriv inga absoluta siffror i ADR:n. Beskriv istället kostnadsordningen relativt — multi-AZ fördubblar, instansklass skiljer storleksordningar — vilket är det påstående planen faktiskt behöver.

- [ ] **Step 2: Skriv `docs/adr/0017-rds-secrets-manager.md`**

Följ formen i `docs/adr/0016-aws-eks-terraform.md`. Innehållet ska täcka:

- **Kontext:** Plan 17 lade VPC/EKS/ECR. Chartets `infra.enabled` byggdes i Plan 11 för att kunna flytta ut datalagret. Devroom har tre databaser bakom tre bounded contexts.
- **Beslut 1 — tre RDS-instanser, inte en delad.** Motivering ur ADR-0001/0005. Kostnadsargumentet: antal instanser är en linjär multiplikator, multi-AZ och instansklass är de verkliga kostnadsdrivarna, därför parametriseras de. Förkastat: en instans med tre logiska databaser (delad failure domain gör isoleringen till en konvention), Aurora Serverless v2 (annan produkt; döljer de primitiv som är poängen).
- **Beslut 2 — `manage_master_user_password = true`.** Förkastat: `random_password`, eftersom lösenordet då hamnar i klartext i Terraform-state.
- **Beslut 3 — External Secrets Operator.** Förkastat: Spring Cloud AWS i tjänsterna (kopplar tre tjänster till AWS, kräver lokal fallback, bryter ett-chart-tre-miljöer), Secrets Store CSI Driver (kräver volume-mounts i den generiska deployment-mallen), och enbart Terraform (secreten får ingen konsument — bryter strikt wiring).
- **Konsekvenser:** `db-credentials` delas upp i tre i alla miljöer, med Helms list-ersättningssemantik som skäl (maps djup-mergeas, listor ersätts — och `secretEnv` är en lista). `infra.enabled` visade sig för grov: den gatar även RabbitMQ, som ska stanna in-cluster, så chartet fick en `infra.postgres.enabled` som skiljer databaserna från brokern. En kundhanterad KMS-nyckel istället för `aws/secretsmanager` skulle kräva en `kms:Decrypt`-sats i IRSA-policyn.
- **Kostnadsgaranti:** plan-only, `apply` körs aldrig, utan kostnad.
- **Status:** Accepterad.

- [ ] **Step 3: Uppdatera `terraform/README.md`**

Utöka PLAN-ONLY-varningen med RDS, och dokumentera de två tfvars-profilerna:

```markdown
## Kostnadsprofiler

`terraform.tfvars.dev.example` och `terraform.tfvars.prod.example` visar de två
konfigurationerna. Kopiera en av dem till `terraform.tfvars` (gitignorerad).

| | dev | prod |
|---|---|---|
| `db_instance_class` | `db.t4g.micro` | `db.t4g.small` |
| `db_multi_az` | `false` | `true` |
| `db_backup_retention_period` | 1 | 7 |

Multi-AZ är den enskilt största kostnadsdrivaren — den kör en varm standby
dygnet runt. Antal instanser är bara en multiplikator på de val som redan gjorts.

**Ingenting av detta appliceras.** Endast `fmt`, `init -backend=false`,
`validate` och `plan` körs.
```

- [ ] **Step 4: Uppdatera `README.md` och `CLAUDE.md`**

Lägg till Plan 18 i status-avsnittet och ADR-0017 i ADR-listan i båda filerna, i samma stil som befintliga rader. Håll `CLAUDE.md`:s "Nästa steg" uppdaterad: nästa plan är **Plan 19 — edge (ALB, ACM, Route53)**.

- [ ] **Step 5: Kör hela verifieringskedjan**

Run:
```bash
cd terraform && terraform fmt -check -recursive && terraform init -backend=false \
  && terraform validate && terraform plan
```
Expected: allt grönt, plan går igenom. **Ingen `apply`.**

Run:
```bash
cd /Users/annikaholmqvist/IdeaProjects/devroom \
  && helm lint helm/devroom \
  && helm template devroom helm/devroom > /tmp/devroom-final.yaml \
  && diff /tmp/devroom-after.yaml /tmp/devroom-final.yaml \
  && helm template devroom helm/devroom -f helm/devroom/values-eks.yaml > /dev/null \
  && echo "ALLA HELM-KONTROLLER GRÖNA"
```
Expected: `ALLA HELM-KONTROLLER GRÖNA`, och diffen tom.

- [ ] **Step 6: Commit**

```bash
git add docs/adr/0017-rds-secrets-manager.md terraform/README.md README.md CLAUDE.md
git commit -F- <<'EOF'
docs(adr): ADR-0017 — RDS, Secrets Manager och External Secrets Operator

Dokumenterar tre beslut: tre RDS-instanser istället för en delad, RDS-hanterat
master-lösenord istället för random_password, och External Secrets Operator
istället för Spring Cloud AWS eller CSI-drivrutinen. Förkastade alternativ och
motiveringar finns i ADR:n.

Noterar två konsekvenser för framtida arbete: infra.enabled är för grov
eftersom den även styr RabbitMQ, och en kundhanterad KMS-nyckel skulle kräva
kms:Decrypt i IRSA-policyn.

README, CLAUDE.md och terraform/README uppdaterade. Nästa plan är 19 — edge.
EOF
```

---

## Efter sista task

Kör `superpowers:finishing-a-development-branch` för att välja mellan PR och merge. Branchen `plan-18-rds-secrets` ska **inte** raderas vid merge (`gh pr merge --merge` utan `--delete-branch`).
