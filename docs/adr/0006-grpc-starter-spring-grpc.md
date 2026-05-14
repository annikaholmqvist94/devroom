# ADR-0006: gRPC-starter — Spring gRPC istället för net.devh

**Status:** Accepted
**Date:** 2026-05-14
**Context:** User Service (Plan 03) och Message Service (Plan 05) behöver Spring Boot-integration för gRPC. Plan 03 specade ursprungligen `net.devh:grpc-server-spring-boot-starter` 3.1.0.RELEASE.

## Sammanhang

Devroom använder gRPC för intern read-trafik mellan services (se ADR-0004). User Service exponerar `GetUser` och `ResolveMentions`; Message Service kommer agera klient. Båda sidorna är Spring Boot 4.0.6-applikationer och vi vill ha en idiomatisk integration: auto-config av server-port, automatisk service-registrering, klient-stubs som Spring-beans.

När Plan 03 skrevs (2026-05-10) togs `net.devh:grpc-server-spring-boot-starter` 3.1.0.RELEASE som default. Verifiering mot Maven Central inför Task 1 visade att den versionen är fast på Spring Boot **3.2.4** och gRPC **1.63.0** — inkompatibel med vårt parent POM (Boot 4.0.6, gRPC 1.81.0). Detta är samma typ av Boot 4-rename-cluster som vi mötte i Plan 02 (sju paket-renames i Spring Security/MVC).

## Vad är `net.devh`?

`net.devh:grpc-spring-boot-starter` är ett community-projekt, ingen del av Spring-paraplyet:

- Startat 2016 av Michael Zhang (GitHub: yidongnan). Paket-prefixet `net.devh` är utvecklarens egen domän.
- Repo: `github.com/grpc-ecosystem/grpc-spring`.
- Var de facto-standarden för Spring + gRPC från 2017 till 2024 eftersom Spring saknade officiell integration.
- Senaste release 3.1.0.RELEASE är från april 2024 och låst till Spring Boot 3.2.x. Inga commits sedan dess.

## Alternativ

| # | Alternativ | Boot 4-stöd | Underhåll | Status |
|---|---|---|---|---|
| A | `net.devh:grpc-server-spring-boot-starter` 3.1.0.RELEASE | Nej (Boot 3.2.4) | Stagnerat | Förkastat |
| B | `org.springframework.grpc:spring-grpc-server-spring-boot-starter` 1.0.3 | **Ja (Boot 4.0.x)** | Aktivt, Spring-portfolio | **Valt** |
| C | Hand-rolled: `io.grpc` direkt + manuell `@Bean ServerBuilder` | Ja | Vi äger allt | Avvägt |
| D | `spring-grpc-server-web-spring-boot-starter` (servlet-multiplex) | Ja | Aktivt | Avvägt |

### Alternativ A — `net.devh` 3.1.0.RELEASE

Plan-författarens default. Skulle krocka direkt med Maven dependency convergence eftersom den drar in Spring Boot 3.2.4-deps som krockar med vår 4.0.6-managed BOM. Även om vi tvingade den till att kompilera skulle runtime-fel uppstå pga paket-renames mellan Boot 3 och Boot 4 (se Plan 02-banner för sju verifierade renames). Förkastas.

### Alternativ B — Spring gRPC 1.0.3 (valt)

Officiellt Spring-projekt under `spring-projects/spring-grpc`. 1.0.0 GA tidigare 2026, 1.0.3 är aktuell. Officiella docs (`docs.spring.io/spring-grpc/reference/getting-started.html`) säger explicit: **"Spring gRPC 1.0.x supports Spring Boot 4.0.x"**.

- `spring-grpc-dependencies` 1.0.3 BOM är designad att samexistera med `spring-boot-dependencies` BOM. Citat från docs: *"declares the recommended versions of the dependencies used by a given release of Spring gRPC, excluding dependencies already managed by Spring Boot dependency management."*
- BOM pinnar `grpc.version=1.77.1`. Vi behåller `grpc.version=1.81.0` i parent POM som override — Maven properties i importing POM tar precedens över importerade BOM-properties. Bumpen 1.77→1.81 är 4 minor-versioner inom gRPC:s starkt backwards-compatible stub/server-API.
- Konventioner (från docs):
  - Service: `@Service`-annoterad klass som extendar proto-genererad `XxxImplBase`. Alla `BindableService`-beans auto-registreras av starter:n. **Ingen separat `@GrpcService`-annotation finns** — Spring gRPC återanvänder Spring:s stereotype-annotations.
  - Server-port-property: `spring.grpc.server.port` (sätt `0` för ephemeral i tester).
  - Test-stub: `@LocalGrpcPort int port` + `GrpcChannelFactory channels` (`@Lazy`-bean krävs så port-resolution sker vid första anrop).
  - Klient-stubs: `@ImportGrpcClients` är default-beteende, registrerar proto-genererade stubs som beans.

### Alternativ C — Hand-rolled

Skapa `io.grpc.Server` direkt via `ServerBuilder.forPort(9082)` i en `@Bean`-metod, plus `@PreDestroy`-shutdown. Ger full kontroll men vi tappar auto-config, observability-integration, graceful shutdown, och vi måste återimplementera health/reflection-services. Övervägt som fallback om Spring gRPC visat sig instabilt. Förkastas — Spring gRPC 1.0.3 är GA och stable.

### Alternativ D — Servlet-multiplex

`spring-grpc-server-web-spring-boot-starter` kör gRPC inom samma servlet-container som HTTP, multiplexed på en port. Praktiskt om man inte vill hantera en separat port. Men Plan 03 specar explicit gRPC på port 9082 (separat från HTTP 8082), och servlet-baserad gRPC har historiskt sämre prestanda jämfört med Netty. Vi väljer Netty-varianten (B).

## Beslut

Spring gRPC 1.0.3 (alternativ B) — server + klient i hela Devroom.

| Modul | Dependency |
|---|---|
| BOM (parent POM) | `org.springframework.grpc:spring-grpc-dependencies:1.0.3` (scope=import, type=pom) |
| User Service server | `org.springframework.grpc:spring-grpc-server-spring-boot-starter` |
| Message Service (Plan 05) klient | `org.springframework.grpc:spring-grpc-client-spring-boot-starter` |
| Tester | `org.springframework.grpc:spring-grpc-test` |

`grpc.version` förblir `1.81.0` i parent POM och override:ar BOM:ens 1.77.1.

## Konsekvenser

**Positivt:**

- Officiellt Spring-projekt — samma supportcykel som Boot, Security, Cloud, AI. Förutsägbar Boot-major-uppgraderingsväg.
- Native Spring-konventioner (auto-config, beans, properties) — minimal wiring i applikationen.
- `@LocalGrpcPort` + `GrpcChannelFactory` är design:at för Testcontainers-stil integrationstester.
- Aktiv release-cadens (0.x → 1.0 → 1.0.3 inom <12 månader).

**Negativt:**

- Plan 03 och Plan 05 i `docs/superpowers/plans/` har `net.devh`-snippets — kräver revisions-banner.
- gRPC 1.81 vs Spring gRPC:s testade 1.77.1 är en versions-divergens. Om vi någonsin ser binär-kompatibilitetsproblem är fallback att sätta `<grpc.version>1.77.1</grpc.version>` i parent.
- Färre StackOverflow-träffar än `net.devh` (community-bibliotek dominerar äldre tutorials). Vi förlitar oss på Spring:s reference docs istället.

## Referenser

- Spring gRPC reference (getting-started): `docs.spring.io/spring-grpc/reference/getting-started.html`
- Repo: `github.com/spring-projects/spring-grpc`
- BOM-POM som verifierade Boot 4-support: `repo.maven.apache.org/maven2/org/springframework/grpc/spring-grpc-server-spring-boot-starter/1.0.3/`
- Relaterade ADRs: ADR-0004 (gRPC för intern read-trafik)
