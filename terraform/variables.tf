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
