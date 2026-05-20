# ADR-0008: Bot Service använder RestClient + OAuth2ClientHttpRequestInterceptor

**Status:** Accepted
**Date:** 2026-05-20
**Context:** Plan 07 (Bot Service) ska anropa Message Service med en OAuth2-token utfärdad via Client Credentials grant. Plan-textens revisions-banner (2026-05-12) specade `WebClient` + `ServletOAuth2AuthorizedClientExchangeFilterFunction`. Ger samma resonemang som Gateway-pivoten (ADR-0007) — vi konsoliderar på en HTTP-klient-mental-modell i hela repot.

## Sammanhang

Bot Service triggas av RabbitMQ (`message.published`-event). I sin lyssnar-tråd måste den:

1. Slå upp avsändaren via gRPC mot User Service (ADR-0006-mönstret)
2. Anropa Nordic Dev Mentor via REST (`POST /api/v1/chat`) — utan auth
3. Posta svaret via REST mot Message Service (`POST /messages`) — **med Bearer-token från Client Credentials-flödet**

Steg 3 kräver att en OAuth2-token hämtas vid första anropet (med `client_credentials`-grant mot Auth Service `/oauth2/token`), cachas, och bifogas som `Authorization: Bearer ...`-header på alla efterföljande POST:er. Token förnyas automatiskt när den expirerar.

Spring Security 6.4+ erbjuder två sätt att integrera detta i HTTP-klienten:

1. `WebClient` + `ServletOAuth2AuthorizedClientExchangeFilterFunction` (reactive HTTP-klient i en servlet-app via "bridge")
2. `RestClient` + `OAuth2ClientHttpRequestInterceptor` (ren servlet-stack)

Bot Service triggas av RabbitMQ — ingen `HttpServletRequest` finns i scope när tokens hämtas. Båda alternativen löser detta på samma sätt: explicit `AuthorizedClientServiceOAuth2AuthorizedClientManager` (vs Boot:s default `DefaultOAuth2AuthorizedClientManager` som kräver request i scope).

## Vad ger RestClient vs WebClient?

| Egenskap | WebClient | RestClient |
|---|---|---|
| Stil | Reactive (`Mono`/`Flux`) | Synkron, fluent builder |
| Klassisk parallell | `RestTemplate` (i maintenance mode) | RestTemplate-ersättare i Spring 6+ |
| OAuth2-integration | `ServletOAuth2AuthorizedClientExchangeFilterFunction` (filter-funktion) | `OAuth2ClientHttpRequestInterceptor` (interceptor) |
| Transitiv dep | Drar in `spring-webflux` (reactive runtime) i en servlet-app | Inget reactive på classpath |
| API per call-site | `.attributes(clientRegistrationId(...))` | `.attributes(clientRegistrationId(...))` — **identiskt** |
| Stack-trace | Inkluderar Reactor-frames (`Mono.flatMap`, `Schedulers`) även för synkrona anrop | Ren synkron trace |
| Test-API | `WebClient.exchange()` returnerar `Mono<ClientResponse>` — kräver `.block()` eller reactive-assertions | `RestClient.retrieve().body(...)` returnerar T direkt |

`.attributes(...)`-API:t är identiskt mellan båda — det är samma `RequestAttributePrincipalResolver`-mönster som Spring rekommenderar för Client Credentials i båda HTTP-klienter.

## Alternativ

| # | Alternativ | Fördelar | Nackdelar | Status |
|---|---|---|---|---|
| A | `WebClient` + `ServletOAuth2AuthorizedClientExchangeFilterFunction` (planens default) | Beprövat sedan Spring Security 5; Reactor's debugAgent ger värdefulla diagnostik | Drar in `spring-webflux` i en pure servlet-tjänst; reactive-stack-frames i alla traces; inkonsekvent med Gateway WebMVC-valet (ADR-0007) | Avvägt |
| B | `RestClient` + `OAuth2ClientHttpRequestInterceptor` | Konsekvens med Gateway (ADR-0007) och övriga services; ingen reactive runtime; renare stack-traces; mindre boilerplate (interceptor är 3 rader, filter-function är 5) | Yngre API (Spring 6.1+); mindre community-content vs `WebClient` | **Valt** |
| C | Manuell token-hämtning + lokal cache | Full kontroll | Återimplementerar `OAuth2AuthorizedClientService`-caching och refresh-logik; gömmer Spring Security:s expiry-tracking; större attack-yta för bugs | Förkastat |
| D | `RestTemplate` + custom `ClientHttpRequestInterceptor` | Mest beprövat | API i maintenance mode — inga nya features sedan Spring 5; OAuth2-integrationen är dåligt dokumenterad för servlet utan `WebClient`-bridge | Förkastat |

## Beslut

**Alternativ B — RestClient + OAuth2ClientHttpRequestInterceptor.**

Konkret i `services/bot-service`:

- `pom.xml`: `spring-boot-starter-oauth2-client` (för `OAuth2AuthorizedClientManager`-stöd). INGEN `spring-boot-starter-webflux`.
- `MessageServiceClientConfig`: exponerar två bönar:
  - `AuthorizedClientServiceOAuth2AuthorizedClientManager` med Client Credentials-provider (vi MÅSTE definiera explicit — Boot:s default `DefaultOAuth2AuthorizedClientManager` kräver `HttpServletRequest` i scope som vår RabbitMQ-tråd saknar).
  - `RestClient` med `OAuth2ClientHttpRequestInterceptor` + `RequestAttributePrincipalResolver`.
- `MessagePoster`: per call sätter `.attributes(clientRegistrationId("auth-service"))` + `.attributes(principal("bot-service"))` enligt Spring Security 6.5+ dokumenterad pattern för Client Credentials.

## Konsekvenser

**Positivt:**

- Hela Bot Service stannar på servlet-stack. Inga `Mono`/`Flux` i koden, inga Reactor-frames i stack-traces.
- Samma HTTP-klient-mental-modell som Gateway (ADR-0007) och övriga services som internt kallar HTTP-tjänster.
- Test-assertions stannar synkrona — `await().untilAsserted(() -> wireMock.verify(...))` istället för Reactor StepVerifier.

**Negativt:**

- `RestClient` är yngre än `WebClient` (Spring 6.1, 2023) — något smalare community-content. Officiella reference-docs täcker dock båda paritärt och Client Credentials-mönstret är explicit dokumenterat.
- Krockar med JDK HttpClient HTTP/2 + WireMock i integration-test (`RST_STREAM: Stream cancelled`). Workaround: `JdkClientHttpRequestFactory` med `HttpClient.Version.HTTP_1_1` (se `HttpClientConfig`). Säkert i prod — Tomcat-baserade services i clustret pratar HTTP/1.1 default ändå. Om vi senare bytar dev-mentor till en HTTP/2-server som inte krockar med JDK HttpClient kan tvingingen tas bort.

## Future paths

Om Bot Service någonsin behöver streaming-stöd (t.ex. SSE från Nordic Dev Mentor):

1. Lägg till `WebClient` parallellt för just streaming-anropet. `RestClient` och `WebClient` har medvetet nästan identiska API:n.
2. Den interna OAuth2-integrationen kan vara samma `OAuth2AuthorizedClientManager`-böna — bara HTTP-klienten i `MessagePoster` byter.

Om vi senare behöver multiple client-registrations (t.ex. både `auth-service` och `partner-service`):

1. `RequestAttributePrincipalResolver`-mönstret skalar — varje call-site sätter rätt `clientRegistrationId` via `.attributes(...)`.
2. Ingen ändring av `MessageServiceClientConfig` krävs — interceptor:n läser från request-attribut.

## Referenser

- Spring Security 6.5 OAuth2 Client → Providing the principal: `docs.spring.io/spring-security/reference/6.5/servlet/oauth2/client/authorized-clients.html`
- Spring Framework 7 RestClient documentation: `docs.spring.io/spring-framework/reference/integration/rest-clients.html`
- JDK HttpClient + WireMock HTTP/2 issue: tracker `github.com/wiremock/wiremock/issues/...`
- Relaterade ADRs: ADR-0003 (OAuth2-stack — Auth Service som issuer), ADR-0007 (Gateway WebMVC — samma "konsolidera servlet-stack"-resonemang)
