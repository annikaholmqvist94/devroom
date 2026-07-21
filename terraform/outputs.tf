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

output "rds_endpoints" {
  description = "RDS endpoint (host:port) per database — goes into values-eks.yaml"
  value       = { for k, db in aws_db_instance.devroom : k => db.endpoint }
}

output "rds_secret_arns" {
  description = "Secrets Manager ARN of the RDS-managed master credential, per database"
  value       = { for k, db in aws_db_instance.devroom : k => db.master_user_secret[0].secret_arn }
}
