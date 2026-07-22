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

# Log statements slower than 1s to the instance's own PostgreSQL log, readable
# via the RDS console or `aws rds download-db-log-file-portion`. These logs do
# NOT reach the Loki/Grafana stack — nothing exports them to CloudWatch.
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
