# Plan 18 — Managed data + secrets (plan-only, utan kostnad): Designspecifikation

**Datum:** 2026-07-19
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** D — AWS (del 2: managed data + secrets)

---

## 1. Kontext och mål

Plan 17 lade Devrooms moln-fundament i Terraform: VPC, EKS (access entries), sex ECR-repos och
IAM, verifierat med `terraform plan` mot ett riktigt AWS-konto (65 to add) utan att någonsin köra
`apply`. Chartet kör redan tre miljöer ur samma mallar via values-filer (Minikube / GHCR / EKS),
och `infra.enabled`-togglen byggdes i Plan 11 uttryckligen för att kunna flytta ut Postgres och
RabbitMQ ur klustret.

Plan 18 fyller i den andra grenen av den gaffeln för databaserna: **Devrooms tre Postgres flyttar
från in-cluster StatefulSets till Amazon RDS, och credentialen kommer från AWS Secrets Manager
istället för en handskriven Kubernetes-Secret.**

**Mål:** Beskriva tre RDS-instanser, deras nätverks- och konfigurationsomgivning, och en komplett
kedja från Secrets Manager till poddens miljövariabler — i riktig Terraform och riktiga
Helm-mallar, verifierade statiskt.

**Kostnadsgaranti:** `apply` körs ALDRIG. Endast `fmt` / `init -backend=false` / `validate`
(lokalt, inga AWS-anrop) + `plan` (läs-anrop mot kontot, skapar inget). RDS, Secrets Manager och
ESO skulle kosta *om* de applicerades — det gör vi inte. **Utan kostnad.**

**Icke-mål (denna plan):**
- `apply`, live-kluster, live-databas.
- RabbitMQ → Amazon MQ. Kön står kvar in-cluster; en migration dit har inget att göra med den
  här planens tema och skulle dubbla dess yta.
- ALB / ACM / Route53 → **Plan 19** (edge). Se avsnitt 6.
- Dataflytt eller Flyway-körning mot RDS. Migrationerna är oförändrade; att de skulle köras mot
  en RDS-endpoint istället för en in-cluster-tjänst kräver ingen kodändring.

---

## 2. Designregel: strikt wiring

Eftersom `apply` aldrig körs finns ingen naturlig broms — inget kraschar om vi lägger till en
AWS-resurs som ingen använder. Utan en regel skulle `terraform/` gradvis bli en katalog över AWS
istället för en beskrivning av Devroom.

**Regeln:** varje resurs måste ha en identifierbar konsument i Devroom — en tjänst, en
miljövariabel, en nyckel i `values-eks.yaml`, eller en kodrad. Kan konsumenten inte pekas ut åker
resursen ut ur planen.

Regeln gäller alla återstående Fas D-planer, inte bara denna.

---

## 3. Arkitektur

### 3.1 RDS-topologi: tre instanser, inte en

Devroom har tre databaser (`authdb`, `userdb`, `messagedb`) i tre separata Postgres-instanser.
Separationen är ett medvetet arkitekturval — ADR-0001 (bounded contexts) och ADR-0005 (inga
foreign keys över databasgränser) vilar på den.

**Valt:** en `aws_db_instance` per tjänst, genererade med `for_each` över en `locals`-lista, i
samma stil som `ecr.tf` redan gör för sina sex repos. Separata failure domains, egen skalning per
tjänst, ~20 rader HCL för tre instanser.

**Kostnadsinvändningen och dess svar.** Tre instanser låter dyrare än en, men antal instanser är
en linjär multiplikator på val som redan gjorts — det som faktiskt driver notan är, i fallande
ordning: **multi-AZ** (fördubblar instanskostnaden; en standby körs varmt dygnet runt),
**instansklass** (`db.m5.large` mot `db.t4g.micro` skiljer storleksordningar, och Graviton är
billigare än motsvarande `t3`), **storage och IOPS** (`gp3` på miniminivå är nära gratis;
provisioned IOPS är det inte), och **backup-retention**.

Därför parametriseras kostnaden istället för att arkitekturen kompromissas. `db_instance_class`,
`db_multi_az`, `db_allocated_storage` och `db_backup_retention_period` blir variabler, och två
exempel-tfvars gör trappan konkret:

| Profil | `db_instance_class` | `db_multi_az` | `db_backup_retention_period` |
|---|---|---|---|
| `terraform.tfvars.dev.example` | `db.t4g.micro` | `false` | 1 |
| `terraform.tfvars.prod.example` | `db.t4g.small` | `true` | 7 |

Tre rätt konfigurerade instanser blir billigare än en felkonfigurerad. Exakta priser för
`eu-north-1` verifieras mot AWS prislista när ADR-0017 skrivs — de citeras inte ur minnet.

**Förkastat:** *en instans med tre logiska databaser* (delad failure domain och delad parameter
group gör bounded-context-isoleringen till en konvention istället för en gräns — det motverkar
ADR-0005) och *Aurora Serverless v2* (annan produkt än RDS Postgres, och döljer just de primitiv
— parameter group, multi-AZ, subnet group — som är poängen att beskriva).

### 3.2 Lösenordet genereras inte av oss

`manage_master_user_password = true` låter RDS själv skapa och rotera master-credentialen i
Secrets Manager, KMS-krypterad. Terraform refererar den via instansens
`master_user_secret[0].secret_arn`.

**Förkastat:** `random_password` + `aws_secretsmanager_secret_version`. Det skulle lägga
lösenordet i **klartext i Terraform-state**, vilket är fel oavsett hur state lagras — och alltså
inte något som Plan 20:s S3-backend skulle "rädda".

### 3.3 Secrets-kedjan: External Secrets Operator

Poddarna läser idag databas-credentialen som miljövariabler ur en Kubernetes-Secret, via chartets
`secretEnv`-mönster:

```yaml
secretEnv:
  - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
```

**Valt:** External Secrets Operator som egen Helm-release, plus en `ExternalSecret`-mall i
chartet som synkar Secrets Manager → Kubernetes-Secret. **Tjänsterna ändras inte alls** — de
läser samma miljövariabler i Minikube som i EKS, och `app-deployment.yaml` rörs inte. Terraform
bidrar med IRSA (OIDC-provider, IAM-roll, scoped policy).

**Förkastat:**
- *Spring Cloud AWS Secrets Manager i tjänsterna* — enda alternativet som ger kod vi kan
  `mvn verify`:a lokalt och renodlad Developer-cert-materia, men kopplar tre tjänster till AWS och
  kräver en lokal fallback-profil. Bryter den env-var-drivna portabilitet som hållit chartet
  ett-chart-tre-miljöer sedan Plan 11.
- *Secrets Store CSI Driver* — kräver volume-mounts i den generiska `app-deployment.yaml` som
  idag betjänar alla sju tjänster.
- *Enbart Terraform, ingen runtime-integration* — bryter mot strikt wiring: secreten får ingen
  konsument.

### 3.4 Konsekvens: `db-credentials` måste delas upp

Detta är planens enda ändring som rör den redan fungerande Minikube-vägen. Den är värd att
motivera i detalj, eftersom den både är påtvingad och lätt att göra på fel sätt.

**Nuläget.** Chartet har idag *en* Secret för databaser:

```yaml
secrets:
  db-credentials:
    username: dbuser
    password: dbpass
```

Två uppsättningar konsumenter läser den. Dels de tre tjänsterna, via `secretEnv` i `values.yaml`
(två rader per tjänst, sex totalt):

```yaml
secretEnv:
  - { name: SPRING_DATASOURCE_USERNAME, secret: db-credentials, key: username }
  - { name: SPRING_DATASOURCE_PASSWORD, secret: db-credentials, key: password }
```

Dels de tre Postgres-StatefulSets, där `postgres-statefulset.yaml` hårdkodar samma namn för
`POSTGRES_USER` och `POSTGRES_PASSWORD`. Det fungerar därför att alla tre in-cluster-databaser
seedas av samma mall med samma värden — ett och samma lösenord för tre databaser.

**Varför uppdelningen är påtvingad, inte en preferens.** Den följer direkt ur 3.1 och 3.2
tillsammans. Tre `aws_db_instance` med `manage_master_user_password = true` betyder att RDS
genererar tre master-lösenord **oberoende av varandra** och lägger dem i tre separata secrets.
Det finns ingen mekanism för att tvinga dem lika — det enda sättet vore att gå tillbaka till
`random_password`, vilket 3.2 förkastade av state-skäl. En delad `db-credentials` går alltså
helt enkelt inte att synka från Secrets Manager. Antingen delar vi upp Secreten, eller så faller
hela ESO-kedjan.

**Namnkonventionen finns redan.** `.Values.databases` är nycklad `auth-db`, `user-db`,
`message-db` — samma nycklar som `postgres-statefulset.yaml` itererar över. Secreterna blir
`<db-nyckel>-credentials`, alltså `auth-db-credentials`, `user-db-credentials`,
`message-db-credentials`. StatefulSet-mallen kan då härleda sitt secret-namn ur loop-variabeln
istället för att hårdkoda, vilket tar bort en hårdkodning som redan var en lös tråd.

**Varför i alla miljöer och inte bara i `values-eks.yaml`.** Det här är det egentliga skälet, och
det är en Helm-detalj: **Helm djup-mergear maps men ersätter listor rakt av.** `secretEnv` är en
lista. Om `values.yaml` behöll den delade Secreten och bara `values-eks.yaml` delade upp den,
skulle EKS-filen tvingas skriva om *hela* `secretEnv`-listan för var och en av de tre tjänsterna
— inklusive de fyra raderna för RabbitMQ och OAuth-klienthemligheter som inte har med saken att
göra. Sexton rader duplicerad konfiguration som garanterat driver isär vid nästa ändring.

Att dela upp i `values.yaml` ger istället en sanningskälla: samma secret-namn i alla tre miljöer,
och `values-eks.yaml` behöver bara säga `externalSecrets.enabled: true`. Dev och prod får samma
form, vilket också betyder att EKS-vägens `secretEnv`-referenser faktiskt testas varje gång
Minikube-vägen renderas — istället för att ligga overifierade tills någon kör `apply`.

**Bieffekten är en förbättring.** Tre databaser som delar ett lösenord var en förenkling som
följde av att en mall seedade alla tre, inte ett designval. Uppdelningen gör chartet ärligare
även på Minikube.

**Risken och hur den kontrolleras.** Ändringen rör en fungerande deploy-väg, så
verifieringssteget för Minikube-rendern (avsnitt 5, steg 5) diffas mot nuvarande output: den enda
tillåtna skillnaden är secret-namnen. Inga ändrade env-var-namn, inga ändrade tjänster, inga
borttagna nycklar.

---

## 4. Komponenter och filer

### 4.1 Terraform

| Fil | Innehåll |
|---|---|
| `rds.tf` (ny) | `aws_db_instance` × 3 via `for_each`; **en** `aws_db_subnet_group` över VPC:ns privata subnät; **en** `aws_security_group` som släpper in 5432 enbart från `module.eks.node_security_group_id`; `aws_db_parameter_group` (family `postgres16`) som sätter `log_min_duration_statement` — slow query-loggning som kopplar tillbaka till Fas B:s observability. `storage_encrypted = true`, `manage_master_user_password = true` |
| `irsa.tf` (ny) | IAM-roll för ESO:s service account via EKS OIDC-provider (`module.eks.oidc_provider_arn`), med policy som tillåter `secretsmanager:GetSecretValue` **enbart** på de tre secret-ARN:erna |
| `variables.tf` | Nya: `db_instance_class`, `db_multi_az`, `db_allocated_storage`, `db_backup_retention_period`, `db_engine_version` |
| `outputs.tf` | Nya: `rds_endpoints`, `rds_secret_arns`, `eso_role_arn` — precis de värden `values-eks.yaml` behöver |
| `terraform.tfvars.dev.example`, `terraform.tfvars.prod.example` (nya) | Kostnadstrappan enligt 4.1 |

Ingen ändring i `vpc.tf`, `eks.tf` eller `ecr.tf`.

### 4.2 Helm

| Fil | Ändring |
|---|---|
| `templates/secretstore.yaml` (ny) | En `SecretStore` bakom samma toggle, som binder ESO:s AWS-provider (`service: SecretsManager`, region från values) till den service account IRSA-rollen är kopplad till. Utan den har `ExternalSecret` inget `storeRef` att peka på |
| `templates/externalsecret.yaml` (ny) | Tre `ExternalSecret` bakom `externalSecrets.enabled` (default `false`), som via `storeRef` mot ovanstående `SecretStore` plockar `username`/`password` ur RDS-secretens JSON till Secrets med **exakt de namn `secretEnv` redan refererar** |
| `templates/secret.yaml` | Gatas av när `externalSecrets.enabled` är sann, så de två inte skriver över varandra |
| `templates/postgres-statefulset.yaml` | Hårdkodat `db-credentials` härleds istället ur loop-nyckeln (`{{ $name }}-credentials`), så in-cluster-Postgres följer samma namnkonvention |
| `values.yaml` | `secrets:`-blocket delas upp i tre db-secrets; sex `secretEnv`-rader pekas om; nytt `externalSecrets`-block |
| `values-eks.yaml` | `infra.enabled: false` (nu sant på riktigt — Postgres flyttar ut), `externalSecrets.enabled: true`, per-tjänst `SPRING_DATASOURCE_URL` mot `<AUTH_DB_ENDPOINT>`-platshållare i samma stil som dagens `<ACCOUNT_ID>` |
| `helm/install-external-secrets.sh` (ny) | Installerar ESO som egen release, samma mönster som `install-traefik.sh` / `install-monitoring.sh` / `install-logging.sh` |

`app-deployment.yaml` rörs inte.

### 4.3 Dokumentation

`docs/adr/0017-rds-secrets-manager.md`, uppdaterad `terraform/README.md` (PLAN-ONLY-varningen
utökad med RDS), uppdaterad rot-`README.md` och `CLAUDE.md`.

---

## 5. Verifiering

Ingenting i planen kan verifieras dynamiskt — det följer av plan-only, precis som i Plan 14–17.
Verifieringen är därför statisk och uttömmande:

1. `terraform fmt -check -recursive` — formatering.
2. `terraform init -backend=false` + `terraform validate` — syntax och typer, inga credentials.
3. `terraform plan` mot AWS-kontot — läs-anrop, skapar inget. Beviset att modulen är äkta HCL mot
   riktiga provider-scheman och inte bara syntaktiskt korrekt.
4. `helm lint`.
5. `helm template` med `externalSecrets.enabled=false` — **Minikube-vägen orörd**. Diffas mot
   nuvarande render; enda skillnaden ska vara de tre uppdelade db-secreterna.
6. `helm template -f values-eks.yaml` — en `SecretStore` och tre `ExternalSecret` renderas,
   `secret.yaml` gatas av, inga Postgres-StatefulSets.
7. CI:s befintliga `terraform`-jobb och `helm`-jobb täcker de nya filerna utan ändring.

**Ingen `apply`. Ingen kostnad.**

---

## 6. Out of scope (explicit)

| Utanför | Var det hör hemma |
|---|---|
| ALB, ACM, Route53, pensionering av CoreDNS-hacket | Plan 19 |
| S3 + DynamoDB remote state, CloudWatch-larm, SNS | Plan 20 |
| ElastiCache, S3-uppladdning, CloudFront | Plan 21 |
| RabbitMQ → Amazon MQ | Ingen planerad plan; kön står kvar in-cluster |
| `apply`, live-kluster, live-databas, dataflytt | Utanför hela Fas D |
| GitOps-utrullning (ArgoCD) | Meningsfullt först med ett live-kluster |
