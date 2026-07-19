# Plan 17 — AWS/EKS via Terraform (plan-only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Write real Terraform for Devroom's AWS cloud foundation (VPC, EKS + node group, ECR, IAM) and prove it with `terraform validate`/`plan` against the account — never `apply`, so $0.

**Architecture:** A `terraform/` root module using `terraform-aws-modules/vpc` + `/eks` (v20, EKS access entries so no kubernetes provider is needed and `plan` stays clean), plus `aws_ecr_repository` resources. A `values-eks.yaml` points the chart at ECR (illustrative). A cost-free `terraform` CI job runs fmt + validate.

**Tech Stack:** Terraform (>= 1.5), AWS provider ~> 5, terraform-aws-modules (vpc ~> 5, eks ~> 20), Helm.

**Spec:** `docs/superpowers/specs/2026-07-19-plan-17-aws-eks-terraform-design.md`

**Branch:** `plan-17-aws-eks-terraform` (already created; spec already committed).

**Prerequisites:** Plans 11–16 merged. **Terraform is NOT installed** — Task 1 installs it (`brew install terraform`). `terraform init`/`validate` need network (to fetch providers/modules) but NO AWS credentials. `terraform plan` (Task 8) needs AWS credentials configured and costs $0 (read-only, creates nothing). **NEVER run `terraform apply`.**

**Cost guarantee (repeat in every relevant task):** only `fmt` / `init` / `validate` / `plan` are ever run. `apply` is out of scope and must not be run.

---

## File Structure

| File | Responsibility |
|---|---|
| `terraform/versions.tf` | terraform + aws provider version constraints, local backend |
| `terraform/variables.tf` | region, cluster_name, node instance type/size |
| `terraform/terraform.tfvars.example` | example values (committed) |
| `terraform/main.tf` | aws provider, availability-zones data source |
| `terraform/vpc.tf` | VPC module |
| `terraform/eks.tf` | EKS module (access entries, managed node group) |
| `terraform/ecr.tf` | ECR repositories (for_each over 6 services) |
| `terraform/outputs.tf` | cluster + ECR outputs |
| `terraform/.gitignore` | ignore state / .terraform / real tfvars |
| `terraform/README.md` | plan-only warning + workflow |
| `helm/devroom/values-eks.yaml` | chart pulls from ECR (illustrative) |
| `.github/workflows/ci.yml` | new `terraform` job (fmt + validate) |
| `docs/adr/0016-aws-eks-terraform.md` | ADR |
| `CLAUDE.md` / `README.md` | document the Terraform workflow + cost guarantee |

---

## Task 1: Terraform scaffolding

**Files:**
- Create: `terraform/versions.tf`, `terraform/variables.tf`, `terraform/main.tf`, `terraform/terraform.tfvars.example`, `terraform/.gitignore`

- [ ] **Step 1: Install Terraform (prerequisite)**

HashiCorp ships Terraform via its own Homebrew tap (it was removed from homebrew-core under the BSL license), so use the tap:

Run: `terraform version >/dev/null 2>&1 || { brew tap hashicorp/tap && brew install hashicorp/tap/terraform; }` then `terraform version`
Expected: a Terraform version >= 1.5 prints.

- [ ] **Step 2: Create `terraform/versions.tf`**

```hcl
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Local state for a plan-only demo (no S3 backend → no bootstrap cost).
  # A production setup would use an S3 + DynamoDB backend (future work).
  backend "local" {}
}
```

- [ ] **Step 3: Create `terraform/variables.tf`**

```hcl
variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-north-1"
}

variable "cluster_name" {
  description = "EKS cluster name"
  type        = string
  default     = "devroom"
}

variable "node_instance_type" {
  description = "EC2 instance type for the managed node group"
  type        = string
  default     = "t3.small"
}

variable "node_desired_size" {
  description = "Desired number of worker nodes"
  type        = number
  default     = 2
}
```

- [ ] **Step 4: Create `terraform/main.tf`**

```hcl
provider "aws" {
  region = var.region
}

data "aws_availability_zones" "available" {
  state = "available"
}
```

- [ ] **Step 5: Create `terraform/terraform.tfvars.example`**

```hcl
region             = "eu-north-1"
cluster_name       = "devroom"
node_instance_type = "t3.small"
node_desired_size  = 2
```

- [ ] **Step 6: Create `terraform/.gitignore`**

```
.terraform/
.terraform.lock.hcl
*.tfstate
*.tfstate.*
*.tfvars
!terraform.tfvars.example
crash.log
```

- [ ] **Step 7: Verify formatting + init + validate (no AWS credentials, $0)**

Run: `terraform -chdir=terraform fmt -check -recursive && echo FMT_OK`
Expected: `FMT_OK` (if it reports files, run `terraform -chdir=terraform fmt -recursive` and re-check).

Run: `terraform -chdir=terraform init -backend=false && terraform -chdir=terraform validate`
Expected: `Success! The configuration is valid.`

- [ ] **Step 8: Commit**

```bash
git add terraform/versions.tf terraform/variables.tf terraform/main.tf terraform/terraform.tfvars.example terraform/.gitignore
git commit -m "feat(plan-17): Terraform scaffolding (provider, variables, local backend)"
```

---

## Task 2: VPC + EKS modules

**Files:**
- Create: `terraform/vpc.tf`, `terraform/eks.tf`

- [ ] **Step 1: Create `terraform/vpc.tf`**

```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"

  name = "${var.cluster_name}-vpc"
  cidr = "10.0.0.0/16"

  azs             = slice(data.aws_availability_zones.available.names, 0, 3)
  private_subnets = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  public_subnets  = ["10.0.101.0/24", "10.0.102.0/24", "10.0.103.0/24"]

  # Single NAT keeps cost minimal *if ever applied* (we do not apply).
  enable_nat_gateway = true
  single_nat_gateway = true

  # Subnet tags required for EKS load-balancer discovery.
  public_subnet_tags  = { "kubernetes.io/role/elb" = 1 }
  private_subnet_tags = { "kubernetes.io/role/internal-elb" = 1 }

  tags = { Project = "devroom" }
}
```

- [ ] **Step 2: Create `terraform/eks.tf`**

```hcl
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.0"

  cluster_name    = var.cluster_name
  cluster_version = "1.30"

  cluster_endpoint_public_access = true

  # Access entries (EKS API) instead of the aws-auth ConfigMap → no kubernetes
  # provider needed, so `plan` stays clean against a not-yet-created cluster.
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = true

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  eks_managed_node_groups = {
    default = {
      instance_types = [var.node_instance_type]
      min_size       = 1
      max_size       = 3
      desired_size   = var.node_desired_size
    }
  }

  tags = { Project = "devroom" }
}
```

- [ ] **Step 3: Verify (re-init to fetch the modules, then validate — $0, no credentials)**

Run: `terraform -chdir=terraform fmt -check -recursive && echo FMT_OK`
Expected: `FMT_OK`.

Run: `terraform -chdir=terraform init -backend=false && terraform -chdir=terraform validate`
Expected: `Success! The configuration is valid.` (init downloads the vpc + eks modules from the registry.)

- [ ] **Step 4: Commit**

```bash
git add terraform/vpc.tf terraform/eks.tf
git commit -m "feat(plan-17): VPC + EKS (access entries, managed node group)"
```

---

## Task 3: ECR repositories + outputs

**Files:**
- Create: `terraform/ecr.tf`, `terraform/outputs.tf`

- [ ] **Step 1: Create `terraform/ecr.tf`**

```hcl
locals {
  services = [
    "auth-service",
    "user-service",
    "message-service",
    "gateway",
    "bot-service",
    "frontend",
  ]
}

resource "aws_ecr_repository" "services" {
  for_each = toset(local.services)

  name = "devroom/${each.value}"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = { Project = "devroom" }
}
```

- [ ] **Step 2: Create `terraform/outputs.tf`**

```hcl
output "cluster_name" {
  description = "EKS cluster name"
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "EKS API server endpoint"
  value       = module.eks.cluster_endpoint
}

output "ecr_repository_urls" {
  description = "ECR repository URL per service"
  value       = { for name, repo in aws_ecr_repository.services : name => repo.repository_url }
}
```

- [ ] **Step 3: Verify (fmt + validate — $0, no credentials)**

Run: `terraform -chdir=terraform fmt -check -recursive && echo FMT_OK`
Expected: `FMT_OK`.

Run: `terraform -chdir=terraform init -backend=false && terraform -chdir=terraform validate`
Expected: `Success! The configuration is valid.`

- [ ] **Step 4: Commit**

```bash
git add terraform/ecr.tf terraform/outputs.tf
git commit -m "feat(plan-17): ECR repositories + cluster/ECR outputs"
```

---

## Task 4: terraform/README.md (plan-only guardrail)

**Files:**
- Create: `terraform/README.md`

- [ ] **Step 1: Create `terraform/README.md`**

```markdown
# Devroom AWS infrastructure (Terraform)

Real Terraform for Devroom's AWS cloud foundation: **VPC + EKS (cluster + managed node
group) + ECR + IAM** (via `terraform-aws-modules`).

## ⚠️ PLAN-ONLY — never `apply` ($0)

This code exists to demonstrate Terraform + AWS skills **without incurring cost**. Only run:

```bash
brew install terraform            # once

terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate          # no AWS credentials, $0

# Against your AWS account (read-only, $0 — nothing is created):
terraform -chdir=terraform init
terraform -chdir=terraform plan               # shows "Plan: N to add, 0 to change, 0 to destroy"
```

**Do NOT run `terraform apply`** — that would create an EKS cluster, NAT gateway, and EC2
nodes, which are billable. `plan` creates nothing and costs nothing.

## Layout

- `versions.tf` — provider constraints, local state backend.
- `variables.tf` / `terraform.tfvars.example` — region (`eu-north-1`), cluster name, node size.
- `vpc.tf` — VPC + subnets (single NAT).
- `eks.tf` — EKS cluster + node group, EKS access entries (no aws-auth ConfigMap).
- `ecr.tf` — one ECR repo per service.
- `outputs.tf` — cluster endpoint + ECR URLs.

State is local (`terraform.tfstate`, gitignored). A production setup would use an S3 +
DynamoDB backend (future work), and RDS + ALB/Route53 land in Plan 18.
```

- [ ] **Step 2: Commit**

```bash
git add terraform/README.md
git commit -m "docs(plan-17): terraform README with plan-only cost guardrail"
```

---

## Task 5: values-eks.yaml (chart portability)

**Files:**
- Create: `helm/devroom/values-eks.yaml`

- [ ] **Step 1: Create `helm/devroom/values-eks.yaml`**

```yaml
# Overrides for deploying the chart on EKS with images from ECR.
# Illustrative only — this plan does not deploy (see terraform/README.md, plan-only).
# Replace <ACCOUNT_ID> with the AWS account id (from `aws sts get-caller-identity`).
# infra.enabled stays true (in-cluster Postgres/RabbitMQ); RDS arrives in Plan 18.
global:
  imageRegistry: "<ACCOUNT_ID>.dkr.ecr.eu-north-1.amazonaws.com/devroom"
  imageTag: latest
  imagePullPolicy: Always
```

- [ ] **Step 2: Verify the chart renders ECR image strings**

Run: `helm template devroom helm/devroom -f helm/devroom/values-eks.yaml | grep -oE '<ACCOUNT_ID>.dkr.ecr.eu-north-1.amazonaws.com/devroom/[a-z-]+:latest' | sort -u | head`
Expected: image strings like `<ACCOUNT_ID>.dkr.ecr.eu-north-1.amazonaws.com/devroom/auth-service:latest` for the services.

Run: `helm lint helm/devroom`
Expected: `0 chart(s) failed`.

- [ ] **Step 3: Commit**

```bash
git add helm/devroom/values-eks.yaml
git commit -m "feat(plan-17): values-eks.yaml pointing the chart at ECR (illustrative)"
```

---

## Task 6: Terraform CI job

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Append a `terraform` job under `jobs:`**

In `.github/workflows/ci.yml`, find the end of the `images` job (its last `tags:` block). Add a new job under `jobs:` (2-space indent for the job key). Insert:

```yaml

  terraform:
    # Cost-free IaC gate: fmt + validate need NO AWS credentials and create nothing.
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v6

      - uses: hashicorp/setup-terraform@v3
        with:
          terraform_version: "1.9.5"

      - name: Terraform fmt
        run: terraform -chdir=terraform fmt -check -recursive

      - name: Terraform init
        run: terraform -chdir=terraform init -backend=false

      - name: Terraform validate
        run: terraform -chdir=terraform validate
```

- [ ] **Step 2: Verify the workflow YAML is valid and has the new job**

Run: `python3 -c "import yaml; d=yaml.safe_load(open('.github/workflows/ci.yml')); jobs=list(d['jobs']); print(jobs); assert 'terraform' in jobs and jobs[:2]==['build','helm']; print('OK')"`
Expected: the jobs list includes `terraform`, then `OK`.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(plan-17): terraform fmt + validate job (no credentials, cost-free)"
```

---

## Task 7: ADR-0016

**Files:**
- Create: `docs/adr/0016-aws-eks-terraform.md`

- [ ] **Step 1: Create `docs/adr/0016-aws-eks-terraform.md`**

```markdown
# ADR-0016: AWS EKS-fundament via Terraform (plan-only, $0)

**Status:** Accepted
**Date:** 2026-07-19

## Context

Fas D lyfter Devroom till AWS. Kravet: visa Terraform- + AWS-kompetens för rekryterare
**utan kostnad** och utan live-kluster. EKS/NAT/node group är billbara *om de skapas*.

## Decision

Skriva riktig Terraform (`terraform/`) för VPC + EKS + ECR + IAM via
`terraform-aws-modules/vpc` + `/eks` (v20, **EKS access entries** i stället för
aws-auth-ConfigMap → ingen kubernetes-provider → ren `plan`). Bevisa äktheten med
`terraform validate` (inga credentials) + `terraform plan` (läs-anrop, $0, inget skapas).
**`terraform apply` körs aldrig.** Ett CI-jobb kör fmt + validate gratis. En
`values-eks.yaml` visar chart-portabiliteten mot ECR (deployas ej).

## Considered alternatives

### 1. CDK / Pulumi
Terraform är vanligast i jobbannonser och mest efterfrågat. Vald.

### 2. LocalStack
Emulerar AWS lokalt, men EKS ingår inte i gratis-tier och Docker var nere i miljön.
Avvisat.

### 3. Faktisk `terraform apply`
Skulle ge ett live-kluster men kostar (EKS control plane ~$0.10/h + NAT + noder).
Avvisat mot kostnadskravet — `plan` bevisar äktheten gratis.

## Consequences

- Lokal state (ingen S3) → noll bootstrap-kostnad; S3+DynamoDB-backend är future work.
- Samma Helm-chart kör nu tre miljöer via values-filer: Minikube / GHCR / EKS — inga
  mall-ändringar.
- ECR-repos definieras men fylls ej (images finns i GHCR); image-push till ECR är future work.
- RDS + ALB/Route53 → Plan 18. GitOps-deploy till EKS → framtida (kräver live-kluster).
- CI:t har nu fyra jobb: build, helm, images, terraform.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0016-aws-eks-terraform.md
git commit -m "docs(plan-17): ADR-0016 AWS EKS Terraform plan-only"
```

---

## Task 8: Documentation

**Files:**
- Modify: `CLAUDE.md`, `README.md`

- [ ] **Step 1: Append a Plan 17 status block to `CLAUDE.md`** (after the Plan 16 block, Swedish bullet style)

Cover: `terraform/` root module (VPC + EKS + ECR + IAM via terraform-aws-modules vpc ~>5 / eks ~>20); EKS **access entries** (not aws-auth) so `plan` is clean; local state; **PLAN-ONLY cost guarantee — never `apply`, $0**; `validate` (no creds) + `plan` (read-only) as the "uses AWS" proof; new `terraform` CI job (fmt + validate, no creds); `values-eks.yaml` (chart → ECR, illustrative, `infra.enabled` stays true); ADR-0016; future work (RDS/ALB=Plan 18, S3 backend, GitOps). End with **Nästa steg:** `terraform plan` mot kontot + merge; sedan Plan 18 (RDS + ALB/Route53).

- [ ] **Step 2: Update `README.md`** — add an "AWS (Terraform, plan-only)" subsection after the CI/CD section:

```markdown
### AWS-infrastruktur (Terraform, plan-only)

Se [ADR-0016](docs/adr/0016-aws-eks-terraform.md). `terraform/` beskriver Devrooms
moln-fundament (VPC + EKS + ECR + IAM). **Körs aldrig med `apply`** — endast validate/plan,
så det kostar $0. Samma Helm-chart kör tre miljöer (Minikube / GHCR / EKS) via values-filer.

```bash
brew install terraform
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate          # inga credentials, $0
terraform -chdir=terraform init && terraform -chdir=terraform plan   # mot kontot, $0, inget skapas
# Deploya (hypotetiskt, ej i denna plan):  helm ... -f helm/devroom/values-eks.yaml
```
```

Add a status table row: `| 17 | AWS/EKS via Terraform (plan-only, $0 — ADR-0016) | 17 | 2026-07-19 |`.

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs(plan-17): document AWS/EKS Terraform (plan-only) and Plan 17 status"
```

---

## Task 9: `terraform plan` against the account (the "uses AWS" proof, $0)

Optional live proof. Requires AWS credentials configured (`aws sts get-caller-identity` works). **$0 — creates nothing. Never run `apply`.**

**Files:** none (verification only)

- [ ] **Step 1: Confirm credentials**

Run: `aws sts get-caller-identity`
Expected: your account id / ARN prints (credentials configured).

- [ ] **Step 2: Init (with backend) + plan**

Run: `terraform -chdir=terraform init`
Then: `terraform -chdir=terraform plan`
Expected: `Plan: N to add, 0 to change, 0 to destroy.` (N ≈ 50–70) listing VPC, EKS cluster, node group, ECR repos, IAM roles. Read-only AWS calls only; **nothing is created**.

- [ ] **Step 3: Record the result**

No commit. Note in the PR: `terraform validate` green; `terraform plan` produced a clean "N to add" against the real account with $0 cost. `apply` was never run.

---

## Self-Review Notes

- **Spec coverage:** scaffolding + local backend (Task 1), VPC+EKS via modules with access entries (Task 2), ECR + outputs (Task 3), plan-only README guardrail (Task 4), values-eks (Task 5), terraform CI job (Task 6), ADR-0016 (Task 7), docs (Task 8), plan-against-account proof (Task 9). All spec sections map to a task.
- **Cost guarantee** is stated in the prerequisites, Task 4 (README), Task 8 (docs), and Task 9; `apply` is never in any command.
- **Clean plan:** EKS module v20 with `authentication_mode = "API"` + `enable_cluster_creator_admin_permissions` uses access entries — no kubernetes provider — so `plan` works against a non-existent cluster.
- **Offline verifiability:** `terraform validate` (Tasks 1–3) needs network to fetch modules/providers but NO AWS credentials; the CI job (Task 6) runs it cost-free. `terraform plan` (Task 9) needs credentials and is $0.
- **Out of scope (spec §6):** apply/live cluster, RDS/ALB (Plan 18), S3 backend, GitOps, k8s resources in Terraform, image push to ECR.
```
