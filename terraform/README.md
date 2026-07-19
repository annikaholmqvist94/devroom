# Devroom AWS infrastructure (Terraform)

Real Terraform for Devroom's AWS cloud foundation: **VPC + EKS (cluster + managed node
group) + ECR + IAM** (via `terraform-aws-modules`).

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
