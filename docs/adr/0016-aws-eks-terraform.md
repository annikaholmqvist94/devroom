# ADR-0016: AWS EKS-fundament via Terraform (plan-only, $0)

**Status:** Accepted
**Date:** 2026-07-19

## Context

Fas D lyfter Devroom till AWS. Kravet: beskriva Devrooms moln-fundament med riktig
Terraform **utan kostnad** och utan live-kluster. EKS/NAT/node group är billbara *om de skapas*.

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
