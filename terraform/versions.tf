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
