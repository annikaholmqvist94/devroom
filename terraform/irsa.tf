# IRSA: the service account the Devroom chart creates may assume this role via
# the cluster's OIDC provider. The role may read exactly the three RDS master
# secrets — nothing else in Secrets Manager.
data "aws_iam_policy_document" "eso_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [module.eks.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:sub"
      values   = ["system:serviceaccount:${var.app_namespace}:${var.eso_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${module.eks.oidc_provider}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eso" {
  name               = "${var.cluster_name}-external-secrets"
  description        = "Read RDS master credentials from Secrets Manager"
  assume_role_policy = data.aws_iam_policy_document.eso_assume_role.json

  tags = { Project = "devroom" }
}

data "aws_iam_policy_document" "eso_read_rds_secrets" {
  statement {
    effect = "Allow"

    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]

    resources = [
      for db in aws_db_instance.devroom : db.master_user_secret[0].secret_arn
    ]
  }
}

resource "aws_iam_role_policy" "eso_read_rds_secrets" {
  name   = "read-rds-master-secrets"
  role   = aws_iam_role.eso.id
  policy = data.aws_iam_policy_document.eso_read_rds_secrets.json
}
