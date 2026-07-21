# Devroom AWS infrastructure (Terraform)

Real Terraform for Devroom's AWS cloud foundation: **VPC + EKS (cluster + managed node
group) + ECR + IAM + RDS + IRSA for External Secrets** (via `terraform-aws-modules`).

## ⚠️ PLAN-ONLY — never `apply` (no cost)

This code exists to demonstrate Terraform + AWS skills **without incurring cost**. Only run:

```bash
brew install terraform            # once

terraform -chdir=terraform fmt -check -recursive
terraform -chdir=terraform init -backend=false
terraform -chdir=terraform validate          # no AWS credentials, no cost

# Against your AWS account (read-only, no cost — nothing is created):
terraform -chdir=terraform init
terraform -chdir=terraform plan               # shows "Plan: N to add, 0 to change, 0 to destroy"
```

**Do NOT run `terraform apply`** — that would create an EKS cluster, NAT gateway, RDS
instances, and EC2 nodes, all of which are billable. `plan` creates nothing and costs nothing.

## Layout

- `versions.tf` — provider constraints, local state backend.
- `variables.tf` / `terraform.tfvars.dev.example` / `.prod.example` — region (`eu-north-1`),
  cluster name, node size, RDS cost profile.
- `vpc.tf` — VPC + subnets (single NAT).
- `eks.tf` — EKS cluster + node group, EKS access entries (no aws-auth ConfigMap).
- `ecr.tf` — one ECR repo per service.
- `rds.tf` — one RDS PostgreSQL instance per database (authdb/userdb/messagedb),
  `manage_master_user_password = true` so the master password never touches Terraform state.
- `irsa.tf` — IAM role the chart's External Secrets Operator service account assumes, scoped
  to reading exactly the three RDS master secrets.
- `outputs.tf` — cluster endpoint, ECR URLs, RDS endpoints/secret ARNs, ESO role ARN.

State is local (`terraform.tfstate`, gitignored). A production setup would use an S3 +
DynamoDB backend (future work). See [ADR-0017](../docs/adr/0017-rds-secrets-manager.md) for
the RDS/Secrets Manager/External Secrets Operator decisions.

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
