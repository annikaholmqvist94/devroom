# Plan 16 — CI/CD (build + push images till GHCR): Designspecifikation

**Datum:** 2026-07-15
**Författare:** Annika Holmqvist
**Status:** Godkänd för implementeringsplan
**Fas:** C — CI/CD (del 1)

---

## 1. Kontext och mål

Devroom har CI sedan Plan 01/11 (`.github/workflows/ci.yml`): ett `build`-jobb
(`mvn -B verify`) + ett `helm`-jobb (`helm lint` + `helm template`). Ingen CD — inga
container-images byggs eller publiceras av pipelinen; images byggs bara lokalt
(`devroom/<svc>:latest` i Minikubes Docker via `deploy.sh`).

**Mål:** Utöka CI till en **CD-pipeline** som bygger de 6 Devroom-imagesarna i GitHub
Actions och pushar dem till **GHCR** (GitHub Container Registry), taggade och redo att dras
av Helm-chartet. Första planen i Fas C.

**Icke-mål:** Faktisk kluster-deploy (hör till Fas D/EKS, där GitHub kan nå klustret),
GitOps/ArgoCD, multi-arch, image-signering/SBOM, dev-mentor-imagen (externt repo).

**Verklighetsbegränsning:** GitHub Actions-runners kan inte nå den lokala Minikuben, så
"deploy" från CI är inte möjligt lokalt — därför bygger + publicerar pipelinen images, och
kluster-deployen sker i Fas D.

---

## 2. Arkitektur-översikt

### 2.1 Register

**GHCR** (`ghcr.io`) — gratis, integrerat med GitHub, auth via inbyggda `GITHUB_TOKEN`
(ingen extra secret). Image-namn: `ghcr.io/<owner>/<tjänst>`, där `<owner>` är
`annikaholmqvist94` (repots ägare) och `<tjänst>` ∈ {auth-service, user-service,
message-service, gateway, bot-service, frontend}.

### 2.2 Workflow — nytt `images`-jobb i `ci.yml`

- **Trigger:** kör bara på **push till `main`**. PR:er kör de befintliga `build` +
  `helm`-jobben (test/lint) men publicerar inga images.
- **Rättigheter:** `permissions: { contents: read, packages: write }`.
- **Matris** över de 6 tjänsterna → parallella byggen. Varje matris-element:
  1. `actions/checkout@v6`
  2. `docker/login-action@v3` mot `ghcr.io` med `github.actor` + `secrets.GITHUB_TOKEN`
  3. `docker/build-push-action@v6`: context = repo-rot, `file` = tjänstens Dockerfile,
     `push: true`, `tags` = `ghcr.io/<owner>/<tjänst>:<kort-sha>` **och**
     `ghcr.io/<owner>/<tjänst>:latest`.
- Backend-tjänsterna bygger från `services/<svc>/Dockerfile`; frontend från
  `frontend/Dockerfile`. Alla med repo-rot som build-context (som `deploy.sh`).

### 2.3 Chart-wiring — `values-ghcr.yaml`

En ny `helm/devroom/values-ghcr.yaml` som override:ar globalerna (mallar oförändrade —
samma portabilitets-mönster som Fas D-målet):

```yaml
global:
  imageRegistry: ghcr.io/annikaholmqvist94
  imageTag: latest
  imagePullPolicy: IfNotPresent
```

Chartet renderar image som `{imageRegistry}/{image}:{imageTag}` →
`ghcr.io/annikaholmqvist94/auth-service:latest`, vilket matchar exakt vad CD pushar.
`helm upgrade --install ... -f values-ghcr.yaml` drar då från GHCR istället för Minikubes
lokala images. Lokal (`devroom`/`Never`) och GHCR blir två values-filer.

**Dev-mentor-undantag:** den globala `imageRegistry` gäller alla tjänster i chartet, men CD
publicerar bara de 6 Devroom-imagesarna — **inte** dev-mentor (externt repo). En ren
GHCR-deploy måste därför förse dev-mentors image separat (dess egen registry/tag via en
per-tjänst-override i values-secrets/övrig values). Noteras här; löses inte i denna plan
(dev-mentor är out-of-scope, §6).

### 2.4 Låsta designval (från brainstorming 2026-07-15)

| Val | Beslut | Motivering |
|---|---|---|
| Register | GHCR | Gratis, GITHUB_TOKEN-auth, ingen extra secret |
| CD-scope | Bygg + pusha images (ingen kluster-deploy) | GitHub når inte lokal Minikube; deploy = Fas D |
| Trigger | Bara push till `main` publicerar; PR = test/lint | Publicera bara mergad kod |
| Taggar | Kort git-SHA + `latest` | Spårbarhet + bekväm senaste |
| Chart | `values-ghcr.yaml` (registry-override) | Portabelt; inga mall-ändringar |

---

## 3. Komponenter och filer (översikt)

| Fil | Ansvar |
|---|---|
| `.github/workflows/ci.yml` | Nytt `images`-jobb (matris, GHCR-login, build-push) |
| `helm/devroom/values-ghcr.yaml` | Registry/tag/pull-policy-override för GHCR |
| `docs/adr/0015-cicd-ghcr.md` | ADR |
| `README.md` / `CLAUDE.md` | Dokumentera CD + GHCR-deploy-väg |

---

## 4. ADR-0015

**CI/CD med GHCR + push-modell; kluster-deploy uppskjuten till Fas D.** Övervägda
alternativ: Docker Hub (kräver extra secrets, ingen fördel över GHCR), self-hosted runner på
laptopen (skulle låta GitHub nå Minikube men mer drift/säkerhetsyta), GitOps/ArgoCD
(pull-modell som löser "GitHub når inte klustret" — uppskjutet som egen framtida plan för att
hålla Plan 16 fokuserad).

---

## 5. Verifiering

Till skillnad från Plan 13–15 (lokal Docker/kluster krävdes) **kör Plan 16:s pipeline på
GitHub** — äkta e2e utan lokal miljö.

- **Statiskt (offline):** `actionlint` / Python-YAML-validering av `ci.yml`; bekräfta båda
  jobben `build`/`helm` kvar + nytt `images`-jobb; `helm template devroom helm/devroom
  -f helm/devroom/values-ghcr.yaml` renderar image-strängarna som
  `ghcr.io/annikaholmqvist94/<tjänst>:latest`.
- **Live (på GitHub):** när branchen/PR:en pushas triggas workflowen; efter merge till main
  kör `images`-jobbet och de 6 imagesarna dyker upp under repots **Packages** (GHCR).
  Verifieras genom att inspektera GHCR-paketen / Actions-loggen.

---

## 6. Out of scope (explicit)

- Kluster-deploy från CI (Fas D/EKS).
- GitOps/ArgoCD (pull-baserad auto-deploy).
- Multi-arch-images (bara linux/amd64).
- Image-signering / SBOM / provenance.
- dev-mentor-imagen (externt repo).
- Versions-taggar (semver) bortom SHA + `latest`.
