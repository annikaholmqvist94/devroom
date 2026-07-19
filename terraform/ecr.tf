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
