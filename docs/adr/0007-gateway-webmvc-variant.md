# ADR-0007: Spring Cloud Gateway WebMVC istället för WebFlux

**Status:** Accepted
**Date:** 2026-05-20
**Context:** Plan 06 ska implementera Gateway-rollen. Plan-texten (2026-05-12) specar `spring-cloud-starter-gateway-server-webflux` med motivering att Spring Cloud Gateway är "reactive only". Det är inte längre sant i Gateway 4.1+ / 5.0.

## Sammanhang

Gateway är BFF för frontend (se ADR-0003): tar emot cookie-baserade requests, kör OAuth2 Authorization Code-flöde mot Auth Service vid login, sätter HttpOnly session-cookie, och propagerar JWT till nedströms-services via `TokenRelay`-filtret.

När plan-texten skrevs antogs Spring Cloud Gateway vara reactive-only. Detta var sant fram till och med Gateway 4.0.x (Spring Cloud `2023.0.x` Leyton / `2024.0.x` Moorgate). Från **Gateway 4.1** (Spring Cloud `2024.0.0`) finns en parallell **Server WebMVC**-variant som har feature-paritet med WebFlux-varianten, inklusive `TokenRelay`-filtret som är central för plan 06.

Resten av Devroom (Auth, User, Message) bygger på Spring MVC + Tomcat med servlet-stack och `SecurityFilterChain`. Gateway som enda reactive-service skulle introducera en parallell mental-modell (Mono/Flux, `SecurityWebFilterChain`, `ServerHttpSecurity`, `WebTestClient`, Schedulers, backpressure) i ett system där ingenting annat motiverar reactive.

## Vad ger Gateway WebMVC vs WebFlux?

| Egenskap | WebFlux-variant | WebMVC-variant |
|---|---|---|
| Artefakt | `spring-cloud-starter-gateway-server-webflux` | `spring-cloud-starter-gateway-server-webmvc` |
| HTTP-server | Reactor Netty (icke-blockerande) | Tomcat (servlet, blockerande proxy-anrop) |
| Security-API | `SecurityWebFilterChain` + `@EnableWebFluxSecurity` | `SecurityFilterChain` + `@EnableWebSecurity` |
| Routing-API (Java) | `RouteLocator` (reactive) | `RouterFunction<ServerResponse>` (servlet) |
| Routing-API (YAML) | **identisk syntax** | **identisk syntax** |
| `TokenRelay`-filter | Ja | Ja (sedan 4.1) |
| OAuth2 Authorization Code | Ja | Ja |
| Test-API | `WebTestClient` | `MockMvc` |
| `RouterFunction`-paket för custom endpoints | `org.springframework.web.reactive.function.server` | `org.springframework.web.servlet.function` |

YAML-syntaxen för routes är medvetet designad att vara identisk i båda varianterna — det är endast Java-API:t och underliggande HTTP-stack som skiljer.

## Alternativ

| # | Alternativ | Fördelar | Nackdelar | Status |
|---|---|---|---|---|
| A | `spring-cloud-starter-gateway-server-webflux` (planens default) | Mest beprövat i prod-deployments; bättre throughput för proxy-tunga workloads | Inkonsistent med övriga services; reactive-mental-modell krävs för 1/5 av systemet | Avvägt |
| B | `spring-cloud-starter-gateway-server-webmvc` | Konsistent stack och security-API i hela repot; servlet-debugging; samma tester-pattern som övriga services | Yngre kodbas (4.1+); något lägre throughput-tak (irrelevant vid ~10 RPS demo-trafik) | **Valt** |
| C | Spring Cloud Gateway saknas helt — handskriven BFF (Spring Web + RestClient) | Maximal kontroll | ~250 rader handskriven kod istället för ~100 rader YAML+Java; inget TokenRelay-filter; återskapa cookie/session/refresh-logik | Förkastat redan i ADR-0003 (Alt G) |

## Beslut

**WebMVC-varianten (alternativ B).**

Konkret dependency-byte i `services/gateway/pom.xml`:

```diff
- <groupId>org.springframework.cloud</groupId>
- <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
+ <groupId>org.springframework.cloud</groupId>
+ <artifactId>spring-cloud-starter-gateway-server-webmvc</artifactId>
```

Plan 06 Task 5 (SecurityConfig) byter API-typer:

- `SecurityWebFilterChain` → `SecurityFilterChain`
- `ServerHttpSecurity` → `HttpSecurity`
- `@EnableWebFluxSecurity` → `@EnableWebSecurity`
- `RedirectServerLogoutSuccessHandler` → standard `LogoutSuccessHandler` med redirect

Plan 06 Task 6 (`/api/me`) byter `RouterFunction`-paket: `org.springframework.web.reactive.function.server` → `org.springframework.web.servlet.function`. Lambdans tar `ServerRequest` (servlet) istället för `request -> request.principal().filter(...)` (Mono-baserad).

Plan 06 Task 7 (integrationstest) byter `WebTestClient` → `MockMvc` (eller `TestRestTemplate` för att testa proxy-flöden mot upstream-mock).

Plan 06 Task 4 (YAML) är **oförändrat**.

## Konsekvenser

**Positivt:**

- En security-mental-modell i hela Devroom (`SecurityFilterChain` + `HttpSecurity`).
- Stack traces vid felsökning ser ut som i Auth/User/Message-services.
- Code review: ingen reviewer behöver kontextväxla mellan reactive och servlet inom samma PR.
- Tester använder `MockMvc` precis som övriga services — ingen ny test-DSL att lära.

**Negativt:**

- WebMVC-varianten introducerades i Gateway 4.1 (2024) — yngre kodbas, mindre community-content/StackOverflow-historik. Officiella reference-docs täcker dock båda varianterna paritärt.
- Servlet-stacken har lägre throughput-tak vid extrem belastning (tusentals RPS). Irrelevant för demo-systemet — om Devroom någonsin når den nivån är det enkelt att switcha BOM-import till webflux-artefakten (YAML-routes följer med, Java-koden behöver justeras).
- Plan 06-texten i `docs/superpowers/plans/2026-05-10-plan-06-gateway.md` har webflux-snippets. Revisions-banner läggs i plan-headern (samma mönster som Plan 02-04 efter Boot 4-renames och Spring gRPC-pivoten).

## Future paths

Om belastningskrav någonsin gör webflux nödvändigt:

1. Byt artefakt i `services/gateway/pom.xml`.
2. Byt `SecurityFilterChain` → `SecurityWebFilterChain`, `HttpSecurity` → `ServerHttpSecurity`.
3. Byt `RouterFunction`-paket på `/api/me`.
4. Byt tester till `WebTestClient`.

YAML-routes och OAuth2-client-config är portabla.

## Referenser

- Gateway 4.1 release notes — introduktion av WebMVC-variant: `github.com/spring-cloud/spring-cloud-gateway/wiki/Spring-Cloud-Gateway-4.1-Release-Notes`
- Spring Cloud Gateway reference (båda varianterna i samma docs-tree): `docs.spring.io/spring-cloud-gateway/reference/`
- Spring Cloud Gateway Server WebMVC TokenRelay: `docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway-server-webmvc/filters/tokenrelay.html`
- Relaterade ADRs: ADR-0003 (OAuth2-stack — Gateway som BFF), ADR-0006 (Spring gRPC istället för net.devh — samma mönster av community → Spring-portfolio)
