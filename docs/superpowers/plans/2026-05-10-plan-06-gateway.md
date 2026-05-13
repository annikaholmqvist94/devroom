# Plan 06: Gateway (Spring Cloud Gateway, BFF Pattern)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Revision 2026-05-12:** Pivot från Spring Web BFF till Spring Cloud Gateway. TokenRelay-filter ersätter handskrivna controllers. Reactive stack (Project Reactor). Se [ADR-0003](../../adr/0003-oauth2-stack.md) för motivering, särskilt Alt G (Spring Web BFF avvisad) och Alt H (Kong avvisad).

**Goal:** Implementera Gateway som Spring Cloud Gateway-applikation med OAuth2 Authorization Code + PKCE-flöde mot Auth Service, server-side session-management, HttpOnly cookie till browser, och TokenRelay-filter som propagerar access-token till nedströms-tjänster. Vid plan-slut: en frontend kan logga in via Gateway och pollas/postar via cookie-sessioner utan att frontend någonsin ser en JWT.

**Architecture:** Spring Boot 4.0.6 + `spring-cloud-starter-gateway-server-webflux` (reactive routing) + `spring-boot-starter-oauth2-client` (OAuth2 Authorization Code-flöde). Session-storage in-memory (i prod: Redis via spring-session-data-redis). Inga handskrivna controllers — all routing är YAML.

**Tech Stack:** Spring Boot 4.0.6, Spring Cloud Gateway 4.x (via Spring Cloud BOM), Spring Security OAuth2 Client, reactive Netty.

**Refererar spec:** sektion 2.1 Gateway, 4.5 BFF Pattern, 10 Frontend.

**Pre-conditions:** plan 01-05 klara. Auth Service kör med Spring Authorization Server och har `gateway`-klient registrerad. Resource Servers (User + Message) kör på sina respektive portar.

---

## File Structure

```
services/gateway/
├── pom.xml
├── src/main/java/com/devroom/gateway/
│   ├── GatewayApplication.java
│   └── config/
│       └── SecurityConfig.java          # SecurityWebFilterChain (reactive!)
├── src/main/resources/
│   └── application.yml                  # routing + OAuth2 client config
└── src/test/java/com/devroom/gateway/
    └── GatewayIntegrationTest.java
```

Mycket simplare än Spring Web BFF skulle ha varit — ~3 Java-filer, all logik i YAML.

---

## Task 1: Lägg till Spring Cloud BOM i parent POM

**Files:**
- Modify: `pom.xml`

Spring Cloud har sin egen versions-cadence frikopplad från Spring Boot. Vi måste importera Spring Cloud BOM i parent POM:s dependencyManagement.

- [ ] **Step 1: Identifiera kompatibel Spring Cloud version**

Run:
```bash
curl -s -A "Mozilla/5.0" https://repo1.maven.org/maven2/org/springframework/cloud/spring-cloud-dependencies/maven-metadata.xml | grep -E "<release>|<latest>"
```

Verifiera Spring Cloud version-compatibility mot Spring Boot 4.0.6 i tabellen: https://spring.io/projects/spring-cloud#overview

Spring Boot 4.0.x använder Spring Cloud 2025.0.x (Aurora-release). Vid execution-tid bekräfta exakt patch-version.

- [ ] **Step 2: Lägg till spring-cloud-dependencies BOM och property**

Edit `pom.xml`, lägg till i `<properties>`:

```xml
<spring-cloud.version>2025.0.x</spring-cloud.version>
```

Och i `<dependencyManagement>`:

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-dependencies</artifactId>
    <version>${spring-cloud.version}</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 3: Verifiera**

Run: `mvn -N validate`
Expected: BUILD SUCCESS.

Commit.

---

## Task 2: Scaffold gateway Maven-modul

**Files:**
- Create: `services/gateway/pom.xml`
- Modify: `pom.xml` (lägg till modulen)

- [ ] **Step 1: Modul-POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.devroom</groupId>
        <artifactId>devroom-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>gateway</artifactId>
    <name>Devroom Gateway</name>

    <dependencies>
        <!-- Spring Cloud Gateway (reactive routing) -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway-server-webflux</artifactId>
        </dependency>

        <!-- OAuth2 Client för Authorization Code-flow + TokenRelay -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-client</artifactId>
        </dependency>

        <!-- Spring Security (kommer transitivt med oauth2-client, expliciterar för klarhet) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <!-- Actuator för health + metrics -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Lägg modulen i parent POM**

```xml
<modules>
    <module>services/auth-service</module>
    <module>services/user-service</module>
    <module>services/message-service</module>
    <module>services/gateway</module>
</modules>
```

- [ ] **Step 3: Verifiera bygge**

Run: `mvn -pl services/gateway compile`
Expected: BUILD SUCCESS.

Commit.

---

## Task 3: Application class

**Files:**
- Create: `services/gateway/src/main/java/com/devroom/gateway/GatewayApplication.java`

```java
package com.devroom.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

Commit.

---

## Task 4: application.yml — hjärtat i Gateway

**Files:**
- Create: `services/gateway/src/main/resources/application.yml`

Detta är där all magi händer. ~50 rader YAML som ersätter ~150 rader handskriven Spring Web BFF.

```yaml
server:
  port: 8080

spring:
  application:
    name: gateway

  security:
    oauth2:
      client:
        registration:
          auth-service:
            client-id: gateway
            client-secret: ${GATEWAY_CLIENT_SECRET}
            client-authentication-method: client_secret_basic
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile
            client-name: Devroom Auth
        provider:
          auth-service:
            issuer-uri: ${AUTH_SERVICE_ISSUER:http://localhost:8081}
            # authorization-uri, token-uri, jwk-set-uri, user-info-uri
            # discoveras automatiskt via /.well-known/openid-configuration

  cloud:
    gateway:
      server:
        webflux:
          # CORS för frontend
          globalcors:
            corsConfigurations:
              "[/**]":
                allowedOrigins:
                  - http://localhost:3000
                allowedMethods: [GET, POST, PUT, DELETE, OPTIONS]
                allowedHeaders: ["*"]
                allowCredentials: true
          routes:
            - id: users
              uri: http://localhost:8082
              predicates:
                - Path=/api/users/**
              filters:
                - StripPrefix=1            # ta bort /api innan upstream-anrop
                - TokenRelay               # injicera Bearer-token från session

            - id: messages
              uri: http://localhost:8083
              predicates:
                - Path=/api/messages/**
              filters:
                - StripPrefix=1
                - TokenRelay

            - id: signup
              # Signup går direkt till Auth Service utan auth (Auth Server hanterar)
              uri: http://localhost:8081
              predicates:
                - Path=/signup/**
              # Ingen TokenRelay — signup behöver inte autentisering

management:
  endpoints:
    web:
      exposure:
        include: health, info, gateway
  endpoint:
    health:
      probes:
        enabled: true
    gateway:
      access: read-only      # exponerar /actuator/gateway för felsökning

logging:
  level:
    org.springframework.cloud.gateway: INFO
    org.springframework.security: INFO
    org.springframework.web: INFO
```

**Anatomi av denna config:**

- `spring.security.oauth2.client.registration.auth-service`: definierar att Gateway är en OAuth2-client som vill prata med Auth Service. `client-id: gateway` matchar det vi seedade i Plan 02 Task 8.
- `provider.auth-service.issuer-uri`: Gateway fetchar `/.well-known/openid-configuration` från Auth Service vid uppstart. Alla andra URLs (authorization, token, jwks) extraheras automatiskt.
- `cloud.gateway.server.webflux.routes`: deklarativ routing. Varje route har:
  - `predicates`: när matchar denna route (path-mönster)
  - `filters`: vad ska göras med request innan upstream-anrop
- `TokenRelay`-filter: extraherar access-token från Spring Security session och lägger i `Authorization: Bearer`-header. **Allt OAuth2-arbete sker här.**

Commit.

---

## Task 5: SecurityConfig — reaktiv SecurityWebFilterChain

**Files:**
- Create: `services/gateway/src/main/java/com/devroom/gateway/config/SecurityConfig.java`

OBS: Spring Cloud Gateway är reaktivt — vi använder `SecurityWebFilterChain` (webflux), inte `SecurityFilterChain` (servlet).

```java
package com.devroom.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(csrf -> csrf.disable())  // för demo; i prod använd CookieCsrfTokenRepository
            .authorizeExchange(exchange -> exchange
                .pathMatchers("/login/**", "/oauth2/**", "/actuator/**", "/api/me").permitAll()
                .pathMatchers("/signup/**").permitAll()        // signup proxas till Auth Service utan auth
                .anyExchange().authenticated()
            )
            .oauth2Login(oauth2 -> {})                          // aktiverar Authorization Code-flow
            .oauth2Client(oauth2 -> {})                         // aktiverar TokenRelay-stöd
            .logout(logout -> logout.logoutSuccessHandler(
                    new org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler() {{
                        setLogoutSuccessUrl(java.net.URI.create("http://localhost:3000"));
                    }}
            ));
        return http.build();
    }
}
```

Commit.

---

## Task 6: `/api/me`-endpoint för frontend session-check

**Files:**
- Modify: `services/gateway/src/main/java/com/devroom/gateway/config/SecurityConfig.java` (lägg till en RouterFunction-bean)

Frontend behöver veta: är jag inloggad just nu? Vi exponerar `/api/me` som returnerar user-info från session, eller 401.

Eftersom Gateway är reactive använder vi `RouterFunction` istället för `@RestController`:

```java
// I SecurityConfig eller en separat AuthApiConfig:

@Bean
public RouterFunction<ServerResponse> meRoute() {
    return RouterFunctions.route(
            RequestPredicates.GET("/api/me"),
            request -> request.principal()
                    .filter(p -> p instanceof org.springframework.security.oauth2.core.user.OAuth2User)
                    .cast(org.springframework.security.oauth2.core.user.OAuth2User.class)
                    .flatMap(user -> ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of(
                                    "userId", user.getAttribute("sub"),
                                    "displayName", user.getAttribute("email"),
                                    "teamId", user.getAttribute("team_id")
                            )))
                    .switchIfEmpty(ServerResponse.status(HttpStatus.UNAUTHORIZED).build())
    );
}
```

Commit.

---

## Task 7: Integration test

**Files:**
- Create: `services/gateway/src/test/java/com/devroom/gateway/GatewayIntegrationTest.java`

Test-strategi: vi mockar Auth Service + User + Message via WireMock, kör Gateway mot dem, verifierar att routing + TokenRelay fungerar.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    static WireMockServer authMock;
    static WireMockServer userMock;
    static WireMockServer messageMock;

    @BeforeAll
    static void setup() {
        authMock = new WireMockServer(0);
        userMock = new WireMockServer(0);
        messageMock = new WireMockServer(0);
        authMock.start();
        userMock.start();
        messageMock.start();

        // Mocka /.well-known/openid-configuration
        authMock.stubFor(get("/.well-known/openid-configuration")
                .willReturn(okJson("""
                    {
                      "issuer": "http://localhost:%d",
                      "authorization_endpoint": "http://localhost:%d/oauth2/authorize",
                      "token_endpoint": "http://localhost:%d/oauth2/token",
                      "jwks_uri": "http://localhost:%d/.well-known/jwks.json"
                    }
                    """.formatted(authMock.port(), authMock.port(), authMock.port(), authMock.port()))));
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.client.provider.auth-service.issuer-uri",
                () -> "http://localhost:" + authMock.port());
        r.add("spring.security.oauth2.client.registration.auth-service.client-secret", () -> "test-secret");
    }

    @Test
    void unauthenticatedRequestToProtectedRouteRedirectsToLogin() {
        // GET /api/messages utan session → redirect till /oauth2/authorization/auth-service
    }

    @Test
    void meEndpointReturns401WhenNotAuthenticated() {
        // GET /api/me utan session → 401
    }

    @Test
    void healthEndpointIsPublic() {
        // GET /actuator/health → 200
    }

    @Test
    void corsHeadersArePresent() {
        // OPTIONS /api/messages med Origin: http://localhost:3000 → CORS headers
    }
}
```

OBS: full e2e Authorization Code-flow (faktisk login) är svårt att testa i unit-test-stil eftersom det involverar browser-redirects. Det testas manuellt i Task 8.

Commit.

---

## Task 8: Manuell smoke-test (full flöde)

Förutsatt att Auth Service + User Service + Message Service redan kör (från tidigare plans):

```bash
# Sätt secrets
export GATEWAY_CLIENT_SECRET=<värdet från Plan 02 Task 8>
mvn -pl services/gateway spring-boot:run &
sleep 15

# 1. Hälsokoll
curl http://localhost:8080/actuator/health
# Expected: 200, {"status":"UP"}

# 2. Försök hämta data utan session
curl -v http://localhost:8080/api/messages?channelId=33333333-3333-3333-3333-333333333301
# Expected: 302 redirect till /oauth2/authorization/auth-service

# 3. Sessions-flöde via browser
# Öppna http://localhost:8080/api/messages?channelId=33333333-3333-3333-3333-333333333301
# Spring redirectar till Auth Service:s /login
# Logga in (skapa user först via http://localhost:8081/signup om du inte gjort det)
# Auth Service redirectar tillbaka till Gateway med code
# Gateway exchangar code mot tokens, lagrar i session, sätter SESSION-cookie
# Browser får meddelandena i JSON-svar

# 4. Verifiera cookie sätts korrekt
# I browser-DevTools, Application > Cookies, se SESSION; HttpOnly; SameSite=Lax

# 5. /api/me
# Kalla från browser-console: fetch('/api/me', { credentials: 'include' }).then(r => r.json()).then(console.log)
# Expected: { userId: "...", displayName: "...", teamId: "..." }

# 6. Logout
# Öppna http://localhost:8080/logout
# Browser redirectas till http://localhost:3000 (vår frontend)
# Cookie raderas
```

---

## Task 9: Plan-slut

- [ ] `mvn -B clean verify` passerar
- [ ] Full Authorization Code-flow fungerar i browser
- [ ] `/api/me` returnerar user-info när inloggad, 401 när ej
- [ ] CORS-headers tillåter `http://localhost:3000` med credentials
- [ ] TokenRelay-filter propagerar access-token mot upstream (User + Message Service)
- [ ] Logout rensar session-cookie

---

## Plan 6 — slut

Vid godkänd verifikation: gå vidare till plan 07 (Bot Service med Client Credentials).

**Pedagogisk reflektion:**

Jämfört med en Spring Web BFF skulle vi haft:
- 5-8 controllers (AuthProxyController, MessagesProxyController, etc) ≈ 150 rader Java
- JwtAuthenticationFilter eller motsvarande ≈ 50 rader
- HttpClientConfig med RestClient-beans ≈ 30 rader
- Session-config ≈ 20 rader
- **Total:** ~250 rader handskriven kod

Spring Cloud Gateway ersätter allt med:
- 1 application.yml ≈ 50 rader
- 1 SecurityConfig ≈ 30 rader
- 1 RouterFunction för /api/me ≈ 20 rader
- **Total:** ~100 rader, varav 50 är deklarativ YAML

Det är inte bara mindre kod — det är **mer korrekt** kod, eftersom Spring Cloud Gateway:s filter är testade i miljontals deployments och hanterar edge cases (cookie-attribute, exp-clock-skew, session-state, refresh-token-recycling) som vi annars hade missat.
