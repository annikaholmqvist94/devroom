# ADR-0003: OAuth2-stack — Spring Authorization Server + Resource Server + JWKS + Spring Cloud Gateway (BFF Pattern)

**Status:** Accepted
**Date:** 2026-05-12 (pivot från initial design 2026-05-10, två efterföljande revisioner samma dag)
**Context:** Devroom — Laboration 2 (microservices) + portfolio-projekt

## Sammanhang

Alla användarinitierade flöden behöver autentisering. Bot Service (som inte är en mänsklig användare) behöver också autentisera sig när den postar bot-svar mot Message Service. Frågorna att besvara:

1. **Hur utfärdas och valideras JWTs?**
2. **Hur distribueras den publika nyckeln för signaturverifikation?**
3. **Var lagras tokens på klienten?**
4. **Hur autentiserar Bot Service sig mot Message Service?**

## Beslut

Vi använder Spring Securitys officiella OAuth2-stack genomgående, med Spring Cloud Gateway som implementation av BFF-rollen:

| Komponent | Roll | Spring-modul |
|---|---|---|
| Auth Service | OAuth2 + OIDC provider, utfärdar JWTs, exponerar JWKS | `spring-boot-starter-oauth2-authorization-server` |
| **Gateway** (BFF-roll) | OAuth2 client + reactive routing + TokenRelay-filter | `spring-cloud-starter-gateway-server-webflux` + `spring-boot-starter-oauth2-client` |
| User Service, Message Service | Resource Server, validerar inkommande JWTs via JWKS | `spring-boot-starter-oauth2-resource-server` |
| Bot Service | OAuth2 client (Client Credentials grant), service-token för Message Service-anrop | `spring-boot-starter-oauth2-client` |
| Frontend | Inget auth-bibliotek. Cookie auto-medskickas. | — |

**Konkret:**

1. Auth Server exponerar `/.well-known/jwks.json`. Resource Servers fetchar (cache 24h).
2. Login-flöde: frontend → Gateway redirect → Auth Server `/login` → tillbaka till Gateway med code → Gateway → Auth Server `/oauth2/token` → access + refresh token → session → HttpOnly cookie till browser.
3. API-anrop från frontend: `fetch(url, { credentials: 'include' })`. Gateway:s `TokenRelay`-filter läser session, attachar access-token i `Authorization: Bearer` mot downstream — **en YAML-rad i routes-config**.
4. Bot Service auth: vid behov requests Bot Service en access-token från Auth Server via `client_credentials` grant med scope `bot:write`. Cachas in-memory 5 min av Spring's `ClientCredentialsOAuth2AuthorizedClientProvider`.

**Signatur-algoritm:** RS256 med 2048-bit RSA. **Privata nyckeln genereras in-memory vid Auth Service-uppstart** — finns aldrig på disk eller i K8s Secret. Restart = ny nyckel = befintliga tokens invalida. Publika nyckeln distribueras **endast** via JWKS — finns inte i något ConfigMap eller fil i nedströms-tjänster.

## Övervägda alternativ

### Alt A: Handskriven JWT-issuer + validator (initial design 2026-05-10)

En delad Maven-modul `auth-starter` med egen `JwtIssuer` (JJWT-baserad), `JwtValidator`, `JwtAuthenticationFilter`. Public key distribueras till alla services som ConfigMap-fil. Service-token för Bot pre-issued statiskt med 1 års exp.

**Varför avvisad:** Spring Security ger samma funktionalitet med 4 rader YAML-config per service istället för ~150 rader handskriven kod över 4-5 klasser. Vår variant skulle dessutom missa allt edge-case-arbete (clock-skew, JWK rotation, expired token-hantering) som Spring redan löst. Inget portfolio-värde i att rolla eget för en standardproblematik som har en industri-validerad lösning.

### Alt B: Keycloak som extern Identity Provider

Köra Keycloak i en container, registrera klienter där. Devroom-services pratar bara OAuth2/OIDC mot Keycloak.

**Varför avvisad:** Keycloak är en separat 600 MB-process som kräver egen DB och egen admin-UI. Mervärdet är minimalt för vårt scope (5 mentor-users + signup-flöde). Spring Authorization Server gör samma sak i samma JVM som Auth Service, integreras med vår signup-controller och outbox-publisher. Mindre operativ yta. Vi kunde dokumentera Keycloak som "production migration path" i README.

### Alt C: HS256 (symmetrisk signatur) istället för RS256

Använda en delad secret istället för asymmetrisk nyckelhantering.

**Varför avvisad:** Med HS256 måste samma secret distribueras till alla services. Om någon service hackas kan attackeraren *signera* tokens, inte bara läsa dem. Asymmetrisk RS256 låter bara Auth Server signera, alla andra kan bara verifiera. Det är industri-standardvalet och det Spring Authorization Server defaultar till.

### Alt D: localStorage för JWT på frontend

Spara access-token i `localStorage`, skicka i `Authorization`-header från frontend.

**Varför avvisad:** localStorage är åtkomligt för all JavaScript på domänen. En XSS-bug ger full session-stöld. HttpOnly cookies är inte åtkomliga för JS — XSS kan kalla API:er via cookien medan användaren är aktiv, men kan inte exfiltera token för senare användning. Cookie-baserade sessions är också industri-praxis för server-renderade web-apps.

### Alt E: Privata nycklar i databas (Keycloak-style intern lagring)

Lagra Auth Servers privata signaturnyckel i en DB-rad istället för K8s Secret.

**Varför avvisad:** Inget säkerhetsmässigt mervärde — båda är "secret at rest". Operationell flexibilitet (rotation utan filsystemsåtkomst) är värdefull i prod men inte i demon. Komplexitet utan kompenserande vinst.

### Alt F: Privat nyckel som PEM-fil mountad från K8s Secret

Generera nyckelpar manuellt med `openssl`, lagra som K8s Secret, mounta som fil, läs vid Auth Service-uppstart. Detta var initial-designen.

**Varför avvisad till förmån för in-memory generation:** PEM-filhantering kräver:
- `openssl`-kommandon i deploy-skript
- K8s Secret-skapande (`kubectl create secret generic ... --from-file=...`)
- Spring config för att läsa filen via `Resource`-mekanik
- Risken att råka commit:a en privat nyckel

In-memory generation eliminerar allt detta. Trade-off: tokens blir invalida vid Auth Service-restart. För demon är det acceptabelt eftersom vi inte behöver session-persistens över restarts. För prod skulle vi använda HashiCorp Vault eller AWS KMS för persistent key-management (dokumenterat som future-work nedan).

### Alt G: Spring Web BFF (handskriven controller-baserad)

En klassisk Spring Web-applikation med controllers per upstream (AuthProxyController, MessagesProxyController, etc) som anropar nedströms-tjänster via `RestClient`. Spring Security hanterar OAuth2-flödet och session.

**Varför avvisad till förmån för Spring Cloud Gateway:** Spring Web BFF kräver ~150 rader handskriven proxy-kod över 5+ controllers. Spring Cloud Gateway uppnår samma sak med ~50 rader YAML och dess `TokenRelay`-filter. Reactive stack ger marginellt högre throughput men det är inte den primära vinsten — det är **deklarativ routing** vs imperativ. Reactive learning-investment (~6h) kompenseras av tidsbesparing i kod + bättre portfolio-impact (Token Relay-pattern är industri-buzz).

### Alt H: Kong API Gateway

Kong är open-source API Gateway skriven i Lua ovanpå Nginx. Kursbeskrivningen nämner Kong explicit som accepterat BFF-substitut.

**Varför avvisad till förmån för Spring Cloud Gateway:**
- **Stack-konsistens:** alla våra andra services är Spring Boot — att introducera Lua/Nginx för gateway-rollen splittrar stacken. Felsökning kräver att man kan både Java stack traces och Nginx error logs.
- **OAuth2-integration:** Spring Cloud Gateway:s `TokenRelay` är inbyggd och fungerar omedelbart med `spring-security-oauth2-client`. Kong:s OAuth2-plugin för OSS-versionen är 3rd-party (`kong-oidc`) med patchy dokumentation, och Enterprise-versionen kostar pengar.
- **Konfig-stil:** Kong:s deklarativa YAML är i Kong:s eget format, frikopplat från resten av vår Spring-config. Spring Cloud Gateway:s YAML lever i samma `application.yml` som resten.

**När hade Kong varit rätt val:** flerspråkigt microservice-system (några services Java, andra Python eller Go) där gateway behöver vara stack-agnostisk. Eller om vi ville återanvända Kong:s rich plugin-ekosystem (rate limiting, transformations, IP filtering) out-of-the-box. För Devroom-scope är ingen av dessa giltigt argument.

Vi noterar Kong som "production migration path" om Devroom någonsin skulle behöva multi-language services.

## Konsekvenser

**Positiva:**

- Minimum handskriven security-kod. Resource Server-config är 4 rader YAML per service.
- Gateway-routing är deklarativt YAML — TokenRelay-filter på 1 rad per route.
- Inga PEM-filer någonstans i systemet — privat nyckel finns bara i Auth Server-RAM. Eliminerar hela "key-file-mounting"-problemområdet.
- JWKS-distribution: rotera nyckel (eller restart Auth Service) → resource servers picks upp automatiskt vid nästa cache-refresh.
- Cookie-baserad frontend-session är XSS-säker.
- Bot Service service-auth via Client Credentials följer RFC 6749. Recruiters känner igen mönstret.
- Refresh-tokens inkluderade by default — vi får dem "gratis" snarare än att markera som future work.
- Möjlighet att senare migrera Auth Service till Keycloak utan att röra resource servers eller Gateway (samma OAuth2-protokoll mot båda).
- **Reactive stack i Gateway** — pedagogiskt värdefullt + bevis på Spring-mognad i portfolio.

**Negativa:**

- Spring Authorization Server är ett komplext bibliotek. Initial learning-investment ~6-8h.
- Spring Cloud Gateway introducerar reactive stack (`Mono`/`Flux`) — ny mental modell. Initial learning-investment ~6h.
- Auth Service:s `/signup`-endpoint behöver custom controller (Spring Authorization Server har inbyggt login, men inte signup).
- Client Credentials cache-mekanik i Bot Service behöver edge-case-hantering (token-refresh, error retry).
- Multipla `application.yml`-konfigurationer för OAuth2-flöden — fel där felsöks i Spring Security-trace.
- **Tokens invalida vid Auth Service-restart** (in-memory key consequence). För demon: bekvämt. För prod: kräver Vault/KMS-migration (future work).
- Total tidsbudget-tillägg vs initial design: ~25h. Marginal kvar i 140h-budgeten: ~25h. Risken är att en av de nya komponenterna (Spring Authorization Server, Spring Cloud Gateway, Client Credentials cache) tar längre tid än uppskattat och äter marginalen.

**Saker som inte ändras (oberoende av denna ADR):**

- Outbox-pattern för `user.registered` (ADR-0002) — det handlar om dual-write, inte JWT.
- Microservice-decomposition (ADR-0001) — services ändras inte, bara hur de pratar.
- Inga FK över DB-gränser (ADR-0005) — orelaterad.

## Implementation references

- Spec sektion 4
- Plan 02 (Auth Service som Spring Authorization Server, in-memory keypair)
- Plan 05 (Message Service Resource Server-config)
- Plan 06 (Gateway som Spring Cloud Gateway + OAuth2 Client + TokenRelay)
- Plan 07 (Bot Service Client Credentials)

## Future work

- ADR-0010: motivera Client Credentials över pre-issued static service-token (kan skrivas i polish-veckan).
- ADR-0011: dokumentera XSS-hot-modell och varför cookie över localStorage.
- **Persistent key-management:** migrera från in-memory keypair till HashiCorp Vault eller AWS KMS för Auth Servers privata nyckel. Bevarar tokens över restarts. ~10-15h arbete.
- Migrera till Keycloak om vi någonsin behöver multi-realm eller social login.
- Kong API Gateway om vi någonsin får non-JVM services och behöver stack-agnostisk gateway.
- Custom signup-flöde — för demo räcker email/password mot Auth Servers `JdbcUserDetailsManager`.
- Spring Cloud Gateway:s `RequestRateLimiter`-filter för rate limiting (out-of-the-box, ~1h att aktivera).

## Referenser

- [Spring Authorization Server reference](https://docs.spring.io/spring-authorization-server/reference/index.html)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [OWASP: Token Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html) — cookie vs localStorage threat model
- [RFC 6749 §4.4](https://datatracker.ietf.org/doc/html/rfc6749#section-4.4) — Client Credentials grant
- [RFC 7636](https://datatracker.ietf.org/doc/html/rfc7636) — PKCE
