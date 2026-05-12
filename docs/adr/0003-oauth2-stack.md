# ADR-0003: OAuth2-stack — Spring Authorization Server + Resource Server + JWKS + BFF Pattern

**Status:** Accepted
**Date:** 2026-05-12 (pivot från initial design 2026-05-10)
**Context:** Devroom — Laboration 2 (microservices) + portfolio-projekt

## Sammanhang

Alla användarinitierade flöden behöver autentisering. Bot Service (som inte är en mänsklig användare) behöver också autentisera sig när den postar bot-svar mot Message Service. Frågorna att besvara:

1. **Hur utfärdas och valideras JWTs?**
2. **Hur distribueras den publika nyckeln för signaturverifikation?**
3. **Var lagras tokens på klienten?**
4. **Hur autentiserar Bot Service sig mot Message Service?**

## Beslut

Vi använder Spring Securitys officiella OAuth2-stack genomgående:

| Komponent | Roll | Spring-modul |
|---|---|---|
| Auth Service | OAuth2 + OIDC provider, utfärdar JWTs, exponerar JWKS | `spring-boot-starter-oauth2-authorization-server` |
| BFF | OAuth2 client (Authorization Code + PKCE), server-side session, sätter HttpOnly cookie | `spring-boot-starter-oauth2-client` |
| User Service, Message Service | Resource Server, validerar inkommande JWTs via JWKS | `spring-boot-starter-oauth2-resource-server` |
| Bot Service | OAuth2 client (Client Credentials grant), service-token för Message Service-anrop | `spring-boot-starter-oauth2-client` |
| Frontend | Inget auth-bibliotek. Cookie auto-medskickas. | — |

**Konkret:**

1. Auth Server exponerar `/.well-known/jwks.json`. Resource Servers fetchar (cache 24h).
2. Login-flöde: frontend → BFF redirect → Auth Server `/login` → tillbaka till BFF med code → BFF → Auth Server `/oauth2/token` → access + refresh token → session → HttpOnly cookie till browser.
3. API-anrop från frontend: `fetch(url, { credentials: 'include' })`. BFF läser session, attachar access-token i `Authorization: Bearer` mot downstream.
4. Bot Service auth: vid behov requests Bot Service en access-token från Auth Server via `client_credentials` grant med scope `bot:write`. Cachas in-memory 5 min. Inkluderas i `Authorization`-header vid POST /messages.

**Signatur-algoritm:** RS256 med 2048-bit RSA. Privata nyckeln är K8s Secret som mountas i Auth Server-podden. Publika nyckeln distribueras **endast** via JWKS — finns inte i något ConfigMap eller fil i nedströms-tjänster.

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

## Konsekvenser

**Positiva:**

- Minimum handskriven security-kod. Resource Server-config är 4 rader YAML per service.
- JWKS-distribution: rotera nyckel → resource servers picks upp automatiskt vid nästa cache-refresh. Ingen redeploy.
- Cookie-baserad frontend-session är XSS-säker.
- Bot Service service-auth via Client Credentials följer RFC 6749. Recruiters känner igen mönstret.
- Refresh-tokens inkluderade by default — vi får dem "gratis" snarare än att markera som future work.
- Möjlighet att senare migrera Auth Service till Keycloak utan att röra resource servers eller BFF (samma OAuth2-protokoll mot båda).

**Negativa:**

- Spring Authorization Server är ett komplext bibliotek. Initial learning-investment ~6-8h.
- Auth Service:s `/signup`-endpoint behöver custom controller (Spring Authorization Server har inbyggt login, men inte signup).
- Client Credentials cache-mekanik i Bot Service behöver edge-case-hantering (token-refresh, error retry).
- Multipla `application.yml`-konfigurationer för OAuth2-flöden — fel där felsöks i Spring Security-trace.

**Saker som inte ändras (oberoende av denna ADR):**

- Outbox-pattern för `user.registered` (ADR-0002) — det handlar om dual-write, inte JWT.
- Microservice-decomposition (ADR-0001) — services ändras inte, bara hur de pratar.
- Inga FK över DB-gränser (ADR-0005) — orelaterad.

## Implementation references

- Spec sektion 4
- Plan 02 (Auth Service som Spring Authorization Server)
- Plan 05 task 9 (Message Service Resource Server-config)
- Plan 06 (BFF som OAuth2 Client)
- Plan 07 (Bot Service Client Credentials)

## Future work

- ADR-0010: motivera Client Credentials över pre-issued static service-token (kan skrivas i polish-veckan).
- ADR-0011: dokumentera XSS-hot-modell och varför cookie över localStorage.
- Migrera till Keycloak om vi någonsin behöver multi-realm eller social login.
- Custom signup-flöde — för demo räcker email/password mot Auth Servers `JdbcUserDetailsManager`.

## Referenser

- [Spring Authorization Server reference](https://docs.spring.io/spring-authorization-server/reference/index.html)
- [Spring Security OAuth2 Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/index.html)
- [OWASP: Token Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html) — cookie vs localStorage threat model
- [RFC 6749 §4.4](https://datatracker.ietf.org/doc/html/rfc6749#section-4.4) — Client Credentials grant
- [RFC 7636](https://datatracker.ietf.org/doc/html/rfc7636) — PKCE
