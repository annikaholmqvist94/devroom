# ADR-0017: RDS, Secrets Manager och External Secrets Operator (plan-only)

**Status:** Accepted
**Date:** 2026-07-21

## Context

ADR-0016 (Plan 17) lade VPC/EKS/ECR-fundamentet, plan-only. Chartets `infra.enabled`-toggle
fanns redan sedan Plan 11 för att kunna flytta ut datalagret utan mall-ändringar. Devroom har
tre databaser bakom tre bounded contexts (ADR-0001, ADR-0005): authdb, userdb, messagedb. Plan
18 lägger RDS-instanser för dem samt vägen för de fem Spring-tjänsterna att hämta sina
credentials, fortfarande utan att någonsin köra `apply`.

## Decision

Tre `aws_db_instance`-resurser (`terraform/rds.tf`), en per databas, med
`manage_master_user_password = true` så lösenordet genereras och lagras av RDS i Secrets
Manager — det når aldrig Terraform-state. En IRSA-roll (`terraform/irsa.tf`) ger exakt
`secretsmanager:GetSecretValue` + `DescribeSecret` på de tre secreterna, inget annat. I
klustret hämtar External Secrets Operator dem via en `SecretStore` + tre `ExternalSecret`
(`helm/devroom/templates/secretstore.yaml`, `externalsecret.yaml`) in i tre Kubernetes Secrets
med samma namn tjänsternas `secretEnv` redan förväntar sig. Kostnadsgarantin är oförändrad:
`fmt`, `init -backend=false`, `validate`, `plan` — aldrig `apply`.

## Considered alternatives

### 1. En delad RDS-instans med tre logiska databaser
Billigare (en instans i stället för tre), men delad failure domain gör service-isoleringen
(ADR-0001, ADR-0005) till en konvention snarare än en gräns — ett instans-omstart eller
maintenance-fönster träffar alla tre bounded contexts samtidigt. Avvisat. Kostnadsargumentet
håller inte heller: antal instanser är en linjär multiplikator, medan `db_multi_az` och
`db_instance_class` är de faktiska kostnadsdrivarna — båda är variabler
(`terraform.tfvars.dev.example` / `.prod.example`), så en delad instans sparar bara på
multiplikatorn, inte på det som faktiskt kostar. Verifierat mot AWS Pricing API
(`aws pricing get-products`, region `eu-north-1`/Stockholm, `db.t4g.micro`, PostgreSQL,
2026-07-21): $0.016/instans-timme Single-AZ mot $0.033/instans-timme Multi-AZ — Multi-AZ
mer än fördubblar priset per instans, vilket är större än den besparing en delad instans ger
över tre separata.

### 2. Aurora Serverless v2
Annan produkt med annan skalningsmodell (ACU-baserad, kallstart) än de tre RDS Postgres-
instanser designspecen beskriver. Skulle dölja just de primitiv (instansklass, multi-AZ,
allokerad lagring) som Plan 18 ska demonstrera. Avvisat.

### 3. `random_password` + Terraform-managerat lösenord
Enklare att resonera om, men lösenordet hamnar då i klartext i `terraform.tfstate` (som är
lokalt, oskyddat state — se ADR-0016). `manage_master_user_password = true` låter RDS
generera, rotera och lagra hemligheten i Secrets Manager istället; Terraform ser aldrig
klartexten, bara en secret-ARN. Vald.

### 4. Spring Cloud AWS i tjänsterna
Varje Spring-tjänst skulle läsa sin databas-secret direkt från Secrets Manager vid uppstart.
Avvisat: kopplar tre tjänster (auth/user/message) till ett AWS-SDK-beroende, kräver en lokal
fallback-mekanism för Minikube (Secrets Manager finns inte där), och bryter ett-chart-tre-
miljöer-portabiliteten (ADR-0010, ADR-0015, ADR-0016) — chartet skulle behöva veta vilken
molnplattform det körs på.

### 5. Secrets Store CSI Driver
Monterar secrets som volymer istället för att materialisera dem som Kubernetes Secrets.
Avvisat: kräver volume-mounts i den generiska `app-deployment.yaml`-mallen som alla sju
tjänster delar (ADR-0010), vilket gör mallen mer komplex för fem tjänster som redan använder
`secretEnv`. External Secrets Operator producerar vanliga Secrets, så samma `secretEnv`-
mekanism funkar oförändrad oavsett om värdet kommer från `values.yaml` eller Secrets Manager.

### 6. Enbart Terraform, ingen synk till klustret
Skulle bevisa att secreten existerar i AWS men ge den ingen konsument — bryter projektets
strikta wiring-regel (en resurs utan en läsare är inte klar). External Secrets Operator är den
komponent som faktiskt kopplar ihop Secrets Manager med tjänsternas `secretEnv`. Vald
tillsammans med alternativ 3.

## Consequences

- **`db-credentials` delas upp i tre**, ett per databas, i alla miljöer — inte bara på EKS.
  Anledningen är Helms mergesemantik: maps djup-mergeas mellan `values.yaml` och en
  override-fil, men listor ersätts helt, och varje tjänsts `secretEnv` (som pekar ut vilken
  secret + nyckel den läser för `SPRING_DATASOURCE_USERNAME`/`PASSWORD`) är en lista. Tre
  separata secrets — `auth-db-credentials`, `user-db-credentials`, `message-db-credentials` —
  låter `values-eks.yaml` bara override:a `databases.<namn>.secretName` per databas, utan att
  röra `secretEnv`-listorna alls.
- **`infra.enabled` visade sig för grov.** Den gatade ursprungligen både Postgres-
  StatefulSets och RabbitMQ som ett block. RabbitMQ ska stanna in-cluster på EKS (Amazon MQ är
  utanför scope), medan Postgres flyttar till RDS — samma flagga kunde inte uttrycka båda.
  Chartet fick en finkornigare `infra.postgres.enabled`
  (`helm/devroom/templates/postgres-statefulset.yaml`,
  `helm/devroom/templates/postgres-service.yaml`), medan `rabbitmq.yaml` fortsätter gatas av
  bara `infra.enabled`. `values-eks.yaml` sätter `infra.enabled: true` +
  `infra.postgres.enabled: false`.
- **`secret.yaml` gatas inte i sin helhet.** Loopen över `.Values.secrets` hoppar bara över de
  tre db-credentials när `externalSecrets.enabled` är sant och namnet matchar en databas
  (`hasKey $.Values.databases (trimSuffix "-credentials" $name)`); `rabbitmq-credentials`,
  `oauth-client-secrets` och `dev-mentor-secrets` renderas fortfarande från `values.yaml` som
  förut, på alla miljöer.
- **Ingen CloudWatch-export.** `aws_db_parameter_group` sätter
  `log_min_duration_statement = 1000` för att logga långsamma queries till instansens egna
  Postgres-loggar, men `aws_db_instance` saknar `enabled_cloudwatch_logs_exports` — loggarna
  når inte Loki/Grafana (ADR-0013), som skrapar container-stdout via Alloy, inte RDS. Export
  till CloudWatch valdes medvetet bort: ingen konsument (varken en Alloy CloudWatch-source
  eller ett annat verktyg) finns för dem i det här repot, och att lägga till exporten utan att
  koppla in en läsare bryter samma wiring-regel som avgjorde alternativ 6 ovan.
- **En kundhanterad KMS-nyckel** (istället för default `aws/secretsmanager`) skulle kräva en
  `kms:Decrypt`-sats i `aws_iam_role_policy.eso_read_rds_secrets` utöver
  `secretsmanager:GetSecretValue`. Inte gjort i Plan 18 — future work om kryptering med en
  kundhanterad nyckel blir ett krav.
- **Kostnadsgaranti oförändrad:** plan-only, `apply` körs aldrig. `terraform plan` verifierat
  mot ett riktigt AWS-konto (read-only, skapar inget).
