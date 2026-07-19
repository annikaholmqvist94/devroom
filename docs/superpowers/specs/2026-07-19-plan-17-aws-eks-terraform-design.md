# Plan 17 — AWS/EKS via Terraform (plan-only, utan kostnad): Designspecifikation

**Datum:** 2026-07-19
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** D — AWS (del 1: moln-fundament)

---

## 1. Kontext och mål

Devroom kör lokalt (Minikube, Plan 11–15) och publicerar images till GHCR (Plan 16). Fas D
lyfter samma Helm-chart till AWS. **Kravet är strikt:** beskriva Devrooms moln-fundament med
riktig Terraform **utan att dra på sig någon kostnad** och utan att köra ett live-kluster. Annika
har ett AWS-konto. Terraform är inte installerat lokalt (AWS CLI 2.35 finns).

**Mål:** Skriva *riktig*, produktions-formad Terraform för Devrooms moln-fundament — **VPC,
EKS (kluster + managed node group), ECR, IAM** — och bevisa att den är äkta via `terraform
validate` + `terraform plan` mot AWS-kontot, **utan att någonsin köra `apply`** (alltså utan
kostnad, inget skapas). Plus en `values-eks.yaml` som visar chart-portabiliteten.

**Kostnadsgaranti:** `apply` körs ALDRIG. Endast `fmt`/`init`/`validate` (lokalt, inga
AWS-anrop) + `plan` (läs-anrop, ingen kostnad). EKS/NAT/node group skulle kosta *om* de applicerades —
det gör vi inte.

**Icke-mål (denna plan):** `apply`/live-kluster, Kubernetes/Helm-resurser i Terraform.

**Uttalat framtida arbete (svar på roadmap-frågor):**
- **RDS + ALB/Route53** → **Plan 18** (Fas D, nästa plan).
- **S3 + DynamoDB remote state-backend** → framtida uppgradering (fjärr-/team-state; i praktiken gratis men
  kräver bootstrap). Lokal state räcker för plan-only nu.
- **GitOps-deploy till EKS (ArgoCD)** → framtida plan, meningsfull först när ett live-kluster
  körs (kostar pengar). ArgoCD-idén som sköts upp i Plan 16.

---

## 2. Arkitektur-översikt

### 2.1 Terraform-struktur (`terraform/`)

| Fil | Ansvar |
|---|---|
| `versions.tf` | `required_providers` (aws ~> 5), `required_version >= 1.5`, **lokal** state-backend |
| `variables.tf` | `region` (default `eu-north-1`), `cluster_name`, `node_instance_type`, `node_desired_size` |
| `terraform.tfvars.example` | Exempelvärden (committas; riktig `*.tfvars` gitignoreras) |
| `main.tf` | `provider "aws"` (region från var), `data "aws_availability_zones"` |
| `vpc.tf` | `terraform-aws-modules/vpc/aws` — VPC, 3 publika + 3 privata subnät, **single NAT** (kostnadsminimering om applicerat) |
| `eks.tf` | `terraform-aws-modules/eks/aws` — kluster + **managed node group** (liten instans, t.ex. `t3.small`, desired 2). **Access entries** (EKS API) för behörighet — INTE aws-auth-configmap, så ingen kubernetes-provider behövs → `plan` blir ren mot ett icke-existerande kluster |
| `ecr.tf` | `aws_ecr_repository` via `for_each` över de 6 tjänsterna |
| `outputs.tf` | `cluster_name`, `cluster_endpoint`, `ecr_repository_urls` |
| `.gitignore` (terraform/) | `.terraform/`, `*.tfstate*`, `*.tfvars` (aldrig committa state/hemligheter) |
| `README.md` (terraform/) | **PLAN-ONLY-varning** + validate/plan-instruktioner |

IAM-roller (kluster-roll, node-roll, OIDC-provider) skapas av `eks`-modulen — ingen separat
`iam.tf` behövs; outputs exponerar dem.

### 2.2 Varför access entries (inte aws-auth)

Moderna `terraform-aws-modules/eks` stöder EKS **access entries** (`aws_eks_access_entry`,
en AWS-API-resurs) för att ge principals kube-behörighet. Det undviker att konfigurera en
`kubernetes`-provider mot klustrets endpoint — vilket vid `plan` mot ett ännu icke-skapat
kluster annars ger "provider configuration depends on unknown values"-fel. Med access entries
blir `plan` en ren "N to add" utan k8s-provider.

### 2.3 Chart-portabilitet — `values-eks.yaml`

`helm/devroom/values-eks.yaml`:
```yaml
global:
  imageRegistry: <account-id>.dkr.ecr.eu-north-1.amazonaws.com/devroom
  imageTag: latest
  imagePullPolicy: Always
```
`infra.enabled` förblir `true` (in-kluster Postgres/RabbitMQ; RDS byts in i Plan 18). Visar
"samma chart → tre miljöer" (Minikube `values.yaml` / GHCR `values-ghcr.yaml` / EKS
`values-eks.yaml`) utan mall-ändringar. **Deployas aldrig** i denna plan — illustrerar vägen.
(Account-id är en platshållare i den committade filen; ECR-URL:en kommer från
Terraform-outputen.)

### 2.4 CI — kostnadsfri IaC-grind

Nytt `terraform`-jobb i `.github/workflows/ci.yml`:
`hashicorp/setup-terraform` → `terraform fmt -check -recursive` → `terraform init -backend=false`
→ `terraform validate`. **Inga AWS-credentials** (validate rör inte AWS) → körs gratis på varje
push/PR. Signal: "IaC formateras + valideras i pipelinen."

### 2.5 Låsta designval (från brainstorming 2026-07-19)

| Val | Beslut | Motivering |
|---|---|---|
| Verifieringsdjup | `validate` + `plan` mot kontot; **aldrig `apply`** | Genuint "använder AWS", utan kostnad |
| Omfattning | VPC + EKS + node group + ECR + IAM | Moln-fundament; RDS/ALB = Plan 18 |
| Moduler | `terraform-aws-modules/vpc` + `/eks` | Branschstandard, välkänt |
| Region | `eu-north-1` (Stockholm) | Nära; variabel-styrd |
| State | Lokal (ingen S3) | Noll bootstrap-kostnad; plan-only behöver ej remote state |
| Behörighet | EKS access entries (ej aws-auth) | Ren `plan` utan kubernetes-provider |
| CI | `terraform fmt+validate`-jobb | Kostnadsfri IaC-grind i pipelinen |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `terraform/*.tf` + `terraform.tfvars.example` + `.gitignore` + `README.md` | VPC/EKS/ECR/IAM-infra, plan-only |
| `helm/devroom/values-eks.yaml` | Chart pekar på ECR (illustrativ, ej deployad) |
| `.github/workflows/ci.yml` | Nytt `terraform`-jobb (fmt + validate) |
| `docs/adr/0016-aws-eks-terraform.md` | ADR |
| `CLAUDE.md` / `README.md` | Dokumentera Terraform-arbetsflödet + kostnadsgaranti |

---

## 4. ADR-0016

**Terraform för AWS EKS-fundament, plan-only (ingen apply) för noll kostnad.** Övervägda
alternativ: CDK/Pulumi (Terraform vanligast i jobbannonser), LocalStack (Docker nere lokalt +
EKS ej i gratis-tier), faktisk `apply` (kostar EKS/NAT/node — avvisat mot kravet). `validate`
(gratis, inga credentials) + `plan` (gratis, läs-anrop) räcker för att bevisa äktheten.

---

## 5. Verifiering

- **Statiskt/CI (gratis, inga credentials):** `terraform fmt -check -recursive`,
  `terraform init -backend=false`, `terraform validate`; CI-jobbet grönt.
- **Mot AWS-kontot (utan kostnad, inget skapas):** `terraform init` + `terraform plan` → utskrift
  "Plan: N to add, 0 to change, 0 to destroy" som listar VPC/EKS/ECR-resurser. Kräver
  konfigurerade AWS-credentials. **`apply` körs aldrig.**
- Om Terraform inte är installerat: `brew install terraform` (förutsättning, som `helm` i
  Plan 11).

---

## 6. Out of scope (explicit)

- `terraform apply` / live EKS-kluster / faktiska node-instanser.
- RDS + ALB/Route53 (Plan 18).
- S3 + DynamoDB remote state-backend (framtida).
- GitOps/ArgoCD-deploy till EKS (framtida, kräver live-kluster).
- Kubernetes-/Helm-resurser hanterade i Terraform (håller `plan` ren).
- Faktisk image-push till ECR (images finns i GHCR; ECR-repos skapas men fylls ej).
