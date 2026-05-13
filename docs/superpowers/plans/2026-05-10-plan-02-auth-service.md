# Plan 02: Auth Service (Spring Authorization Server)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.
>
> **Revision 2026-05-12:** Pivot från handskriven JWT-issuer/validator till Spring Authorization Server (`spring-boot-starter-oauth2-authorization-server`). Se [ADR-0003](../../adr/0003-oauth2-stack.md) för motivering.
>
> **Revision 2026-05-13 — Variant C (JSON signup):** `/signup` är JSON-API (`@RestController`), inte Thymeleaf-form. Next.js-frontenden renderar signup-vyn i React och POSTar JSON via Gateway. Spring Security default-login används vid `/login` (inget Thymeleaf-templating krävs). Konsekvens: Task 1 POM tar inte in `spring-boot-starter-thymeleaf`, Task 10 droppar `.loginPage()`, Task 13 ersätter Thymeleaf-form med JSON, `templates/signup.html` skapas inte. Outbox-pattern oförändrat.
>
> **Revision 2026-05-13 — Boot 4.x starter-rename:** `spring-boot-starter-oauth2-authorization-server` är deprecated → `spring-boot-starter-security-oauth2-authorization-server`. `spring-boot-starter-web` är deprecated → drar `spring-boot-starter-webmvc` transitivt via nya SAS-startern.

**Goal:** Implementera Auth Service som en fullvärdig Spring Authorization Server med OAuth2 + OIDC. Två registrerade klienter (`gateway` för Authorization Code + PKCE, `bot-service` för Client Credentials). Custom `/signup`-endpoint som skapar user + skriver outbox-event atomärt. JWKS-endpoint auto-exponerad. RS256-signering med **in-memory generated RSA-keypair** (regenereras vid varje restart — acceptabelt för demo, future-work motiverat i ADR-0003).

**Architecture:** Spring Boot 4.0.6 + Spring Authorization Server 7.0.x (via Boot BOM) + Spring Security + JPA + Flyway + Postgres. `JdbcUserDetailsManager` för users, `JdbcRegisteredClientRepository` för OAuth2-klienter. **RSA-keypair genereras in-memory vid uppstart** — inga PEM-filer någonstans, restart = ny nyckel = existerande tokens invalida (acceptabelt för demo, future-work i ADR-0003 nämner Vault/KMS för persistent key). Custom `OAuth2TokenCustomizer` för att lägga `team_id`-claim på user-tokens. Outbox-pattern oförändrat.

**Tech Stack:** Spring Boot 4.0.6, Spring Authorization Server (kommer som transitiv via boot-starter), Spring Data JPA, Flyway, BCrypt, Postgres 16, Testcontainers.

**Refererar spec:** sektion 4.1-4.3, 5.3.

**Pre-condition:** plan 01 klar — parent POM + docker-compose-infra på plats.

---

## File Structure

```
services/auth-service/
├── pom.xml
├── src/main/java/com/devroom/auth/
│   ├── AuthServiceApplication.java
│   ├── config/
│   │   ├── AuthorizationServerConfig.java     # SecurityFilterChain + ClientRepository + JWKSource
│   │   ├── DefaultSecurityConfig.java          # filter chain (Spring default login, ingen custom path)
│   │   ├── TokenCustomizerConfig.java          # OAuth2TokenCustomizer för team_id-claim
│   │   ├── KeyConfig.java                      # RSAKey-bean från in-memory genererad keypair
│   │   └── OAuth2ClientSeeder.java             # @PostConstruct seedar gateway- + bot-klienter
│   ├── domain/
│   │   ├── DevroomUser.java                    # JPA-entitet, extra team_id-kolumn
│   │   ├── DevroomUserRepository.java
│   │   ├── OutboxEvent.java                    # @Entity
│   │   └── OutboxRepository.java
│   ├── application/
│   │   ├── SignupService.java                  # @Transactional user + outbox
│   │   └── DuplicateEmailException.java
│   ├── infra/
│   │   ├── OutboxPublisher.java                # @Scheduled, wires RabbitMQ in plan 04
│   │   └── DevroomUserDetailsService.java      # implements UserDetailsService över DevroomUser
│   └── web/
│       ├── SignupController.java               # @RestController POST /signup (JSON)
│       ├── SignupRequest.java                  # record(email, password)
│       ├── SignupResponse.java                 # record(userId)
│       └── ExceptionHandlers.java              # DuplicateEmailException → 409
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_users_and_authorities.sql
│       ├── V2__create_oauth2_registered_clients.sql
│       ├── V3__create_oauth2_authorization_tables.sql
│       └── V4__create_outbox_events.sql
└── src/test/java/com/devroom/auth/
    └── AuthServiceIntegrationTest.java         # Testcontainers + TestRestTemplate
```

Borttaget jämfört med original-strukturen: `templates/signup.html` (Variant C: JSON-API), `application/PasswordHasher.java` (PasswordEncoder-bean räcker), `V5__seed_oauth2_clients.sql` (programmatisk seeding via `OAuth2ClientSeeder`).

---

## Task 1: Scaffold auth-service Maven-modul

**Files:**
- Create: `services/auth-service/pom.xml`
- Modify: `pom.xml` (lägg till modulen)

- [ ] **Step 1: Skapa modul-POM**

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

    <artifactId>auth-service</artifactId>
    <name>Devroom Auth Service</name>

    <dependencies>
        <!-- Spring Authorization Server (drar -starter, -security, -webmvc transitivt i Boot 4.x) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-oauth2-authorization-server</artifactId>
        </dependency>

        <!-- Persistens -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>

        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
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

- [ ] **Step 2: Lägg till modulen i parent POM**

Edit `pom.xml`:

```xml
<modules>
    <module>services/auth-service</module>
</modules>
```

- [ ] **Step 3: Verifiera bygge**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/pom.xml pom.xml
git commit -m "feat(auth-service): scaffold module with Spring Authorization Server"
```

---

## Task 2: Application class + application.yml

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/AuthServiceApplication.java`
- Create: `services/auth-service/src/main/resources/application.yml`

- [ ] **Step 1: Application class**

```java
package com.devroom.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

- [ ] **Step 2: application.yml**

```yaml
server:
  port: 8081

spring:
  application:
    name: auth-service
  datasource:
    url: jdbc:postgresql://localhost:5432/authdb
    username: dbuser
    password: dbpass
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
  security:
    oauth2:
      authorizationserver:
        # Issuer URL — vad nedströms services förväntar sig i 'iss'-claim
        issuer: http://localhost:8081
        # Registreringen av klienter görs i koden via JdbcRegisteredClientRepository,
        # inte här. Detta är bara meta-konfig.

devroom:
  auth:
    demo-team-id: 11111111-1111-1111-1111-111111111111

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      probes:
        enabled: true
```

OBS: i K8s overrides:as `issuer` till intern-DNS-namnet (`http://auth-service:8081`) via env-var.

- [ ] **Step 3: Commit**

```bash
git add services/auth-service/src/
git commit -m "feat(auth-service): Spring Boot bootstrap with OAuth2 Authorization Server config"
```

---

## Task 3: KeyConfig — in-memory RSA-keypair generation

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/config/KeyConfig.java`

**Vad detta gör:** Spring Authorization Server förväntar sig en `JWKSource<SecurityContext>`-bean som tillhandahåller signaturnyckeln. Vi genererar ett RSA-keypair i RAM vid uppstart, bygger en `RSAKey` med Nimbus JOSE, och exponerar den via en `ImmutableJWKSet`. Ingen disk-IO, inga PEM-filer.

**Konsekvens:** restart av Auth Service genererar ny nyckel → alla existerande tokens blir invalida vid signaturverifikation. Resource servers' JWKS-cache (24h) blir stale men resolveas vid nästa fetch. För demo: bekvämt. För prod: rotera till HashiCorp Vault eller AWS KMS (dokumenterat i ADR-0003 som future work).

- [ ] **Step 1: Implementera**

```java
package com.devroom.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

@Configuration
public class KeyConfig {

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/config/KeyConfig.java
git commit -m "feat(auth-service): JWKSource bean with in-memory RSA keypair generation"
```

---

## Task 4: Flyway-migration: users + authorities (Spring Authorization Server-standard)

**Files:**
- Create: `services/auth-service/src/main/resources/db/migration/V1__create_users_and_authorities.sql`

Spring Security har ett standard-schema för users som `JdbcUserDetailsManager` förväntar sig. Vi extender det med `user_id` (UUID) och `team_id`.

- [ ] **Step 1: Skriv migrationen**

```sql
-- Standard Spring Security user schema, extended with user_id (UUID) and team_id

CREATE TABLE users (
    user_id        UUID PRIMARY KEY,
    username       VARCHAR(255) UNIQUE NOT NULL,    -- = email
    password       VARCHAR(255) NOT NULL,            -- BCrypt
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    team_id        UUID NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE authorities (
    username       VARCHAR(255) NOT NULL,
    authority      VARCHAR(255) NOT NULL,
    CONSTRAINT fk_authorities_users FOREIGN KEY (username) REFERENCES users(username),
    UNIQUE (username, authority)
);

CREATE INDEX idx_users_email ON users(username);
```

OBS: `username` är typiskt email i Spring Security. `authority` är t.ex. "ROLE_USER" eller "SCOPE_openid".

- [ ] **Step 2: Commit**

---

## Task 5: Flyway-migration: oauth2_registered_clients

Spring Authorization Server har sin egen `JdbcRegisteredClientRepository` med ett specifikt schema. Schemat är publicerat i Spring-källkoden (`oauth2-registered-client-schema.sql`).

- [ ] **Step 1: Hämta schemat från Spring Authorization Server**

```bash
curl -sL https://raw.githubusercontent.com/spring-projects/spring-authorization-server/main/oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql \
  > services/auth-service/src/main/resources/db/migration/V2__create_oauth2_registered_clients.sql
```

OBS: vid execution-tid verifiera att URL:en stämmer mot aktuell Spring Authorization Server-version. Annars hämta motsvarande från lokal Maven dependency.

- [ ] **Step 2: Commit**

---

## Task 6: Flyway-migration: oauth2_authorization + oauth2_authorization_consent

Spring Authorization Server lagrar pågående auth-flöden i två extra tabeller.

- [ ] **Step 1: Hämta schemat**

```bash
curl -sL https://raw.githubusercontent.com/spring-projects/spring-authorization-server/main/oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql \
  >> services/auth-service/src/main/resources/db/migration/V3__create_oauth2_authorization_tables.sql

curl -sL https://raw.githubusercontent.com/spring-projects/spring-authorization-server/main/oauth2-authorization-server/src/main/resources/org/springframework/security/oauth2/server/authorization/oauth2-authorization-consent-schema.sql \
  >> services/auth-service/src/main/resources/db/migration/V3__create_oauth2_authorization_tables.sql
```

- [ ] **Step 2: Commit**

---

## Task 7: Flyway-migration: outbox_events (oförändrat från originell plan)

```sql
CREATE TABLE outbox_events (
    id            BIGSERIAL PRIMARY KEY,
    event_type    VARCHAR(64) NOT NULL,
    payload       JSONB NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at  TIMESTAMPTZ NULL
);

CREATE INDEX idx_outbox_unprocessed
    ON outbox_events(created_at)
    WHERE processed_at IS NULL;
```

Commit.

---

## Task 8: Flyway-migration: seed OAuth2-klienter

`gateway`-klient (Spring Cloud Gateway, ersätter den tidigare-planerade BFF) och `bot-service`-klient seedas vid uppstart. `client_secret` BCrypt-hashas. Plaintext-värden lagras i K8s Secrets (samma värde måste konfigureras både i Auth Server och i klienten).

För demon använder vi statiska secrets — i prod skulle dessa roteras.

- [ ] **Step 1: Generera secrets manuellt och spara i .env (eller K8s Secret)**

```bash
GATEWAY_SECRET=$(openssl rand -hex 32)
BOT_SECRET=$(openssl rand -hex 32)
echo "GATEWAY_CLIENT_SECRET=$GATEWAY_SECRET" >> .env
echo "BOT_CLIENT_SECRET=$BOT_SECRET" >> .env
# Generera BCrypt-hashar för seed-migrationen:
GATEWAY_HASH=$(htpasswd -bnBC 10 "" "$GATEWAY_SECRET" | tr -d ':\n' | sed 's/$2y/$2a/')
BOT_HASH=$(htpasswd -bnBC 10 "" "$BOT_SECRET" | tr -d ':\n' | sed 's/$2y/$2a/')
echo "GATEWAY_BCRYPT=$GATEWAY_HASH"
echo "BOT_BCRYPT=$BOT_HASH"
```

- [ ] **Step 2: Skriv V5-migrationen med hash-värdena**

```sql
-- V5__seed_oauth2_clients.sql
INSERT INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at,
    client_name, client_authentication_methods, authorization_grant_types,
    redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings
) VALUES (
    'gateway-client-id-uuid-1',
    'gateway',
    NOW(),
    '<GATEWAY_BCRYPT från Step 1>',
    NULL,
    'Spring Cloud Gateway',
    'client_secret_basic',
    'authorization_code,refresh_token',
    'http://localhost:8080/login/oauth2/code/auth-service',
    'http://localhost:3000/',
    'openid,profile',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":true,"settings.client.require-authorization-consent":false}',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.access-token-time-to-live":["java.time.Duration",3600.0],"settings.token.refresh-token-time-to-live":["java.time.Duration",86400.0],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"self-contained"}}'
);

INSERT INTO oauth2_registered_client (
    id, client_id, client_id_issued_at, client_secret, client_secret_expires_at,
    client_name, client_authentication_methods, authorization_grant_types,
    redirect_uris, post_logout_redirect_uris, scopes, client_settings, token_settings
) VALUES (
    'bot-client-id-uuid-1',
    'bot-service',
    NOW(),
    '<BOT_BCRYPT från Step 1>',
    NULL,
    'Bot Service',
    'client_secret_basic',
    'client_credentials',
    '',
    '',
    'bot:write',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.client.require-proof-key":false,"settings.client.require-authorization-consent":false}',
    '{"@class":"java.util.Collections$UnmodifiableMap","settings.token.access-token-time-to-live":["java.time.Duration",3600.0],"settings.token.access-token-format":{"@class":"org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat","value":"self-contained"}}'
);
```

OBS: client_settings och token_settings är Jackson-serialiserade Maps — formatet är hårt och måste matcha exakt vad Spring Authorization Server förväntar. Vid execution-tid verifiera mot Spring-dokumentationen för aktuell version.

**Alternativ approach:** seeda klienter programmatiskt i en `@PostConstruct` på en config-bean istället för Flyway. Mer pålitligt eftersom Spring själv kan serialisera settings. Detta är vad jag rekommenderar — Flyway-SQL för settings är fragil. Anpassa V5 till tom-fil och lägg seed-koden i en `OAuth2ClientSeeder`-komponent.

- [ ] **Step 3: Commit**

---

## Task 9: AuthorizationServerConfig — SecurityFilterChain + clients-bean

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/config/AuthorizationServerConfig.java`

```java
package com.devroom.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;

@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();

        http
            .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
            .with(authorizationServerConfigurer, configurer ->
                    configurer.oidc(Customizer.withDefaults())
            )
            .exceptionHandling(exceptions ->
                    exceptions.defaultAuthenticationEntryPointFor(
                            new LoginUrlAuthenticationEntryPoint("/login"),
                            new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                    )
            )
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));

        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }
}
```

Commit.

---

## Task 10: DefaultSecurityConfig — Spring default-login + permit /signup (JSON-API)

```java
package com.devroom.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class DefaultSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/signup", "/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            // Spring Security default-login används vid /login mid-OAuth2-flöde
            .formLogin(Customizer.withDefaults())
            .csrf(csrf -> csrf.ignoringRequestMatchers("/signup"));
        return http.build();
    }
}
```

**Variant C-notering:** ingen `.loginPage("/login")` — Spring genererar default-login-form (vit sida, två fält, knapp). `/signup` är JSON-API och behöver CSRF-undantag eftersom det inte använder Spring Security:s session-CSRF-token.

Commit.

---

## Task 11: TokenCustomizerConfig — lägg `team_id`-claim på user-tokens

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/config/TokenCustomizerConfig.java`

**Vad detta gör:** När Auth Server utfärdar ett access-token efter Authorization Code-flödet, kallar den vår `OAuth2TokenCustomizer`-bean. Vi använder den för att lägga till `team_id`-claim baserat på vilken user som autentiserats.

```java
package com.devroom.auth.config;

import com.devroom.auth.domain.DevroomUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

@Configuration
public class TokenCustomizerConfig {

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(DevroomUserRepository repo) {
        return context -> {
            if ("access_token".equals(context.getTokenType().getValue())
                    && context.getPrincipal() != null
                    && context.getPrincipal().getName() != null) {
                repo.findByUsername(context.getPrincipal().getName())
                        .ifPresent(user -> context.getClaims().claim("team_id", user.getTeamId().toString()));
            }
        };
    }
}
```

Commit.

---

## Task 12: DevroomUser-entitet + UserDetailsService

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/DevroomUser.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/DevroomUserRepository.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/infra/DevroomUserDetailsService.java`

**DevroomUser.java:**

```java
package com.devroom.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class DevroomUser {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;     // = email

    @Column(nullable = false)
    private String password;     // BCrypt

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DevroomUser() {}

    public DevroomUser(UUID userId, String username, String password, UUID teamId) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.enabled = true;
        this.teamId = teamId;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public boolean isEnabled() { return enabled; }
    public UUID getTeamId() { return teamId; }
}
```

**Repository:**

```java
package com.devroom.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface DevroomUserRepository extends JpaRepository<DevroomUser, UUID> {
    Optional<DevroomUser> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

**UserDetailsService:**

```java
package com.devroom.auth.infra;

import com.devroom.auth.domain.DevroomUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DevroomUserDetailsService implements UserDetailsService {

    private final DevroomUserRepository repo;

    public DevroomUserDetailsService(DevroomUserRepository repo) {
        this.repo = repo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var u = repo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.builder()
                .username(u.getUsername())
                .password(u.getPassword())
                .authorities("ROLE_USER")
                .disabled(!u.isEnabled())
                .build();
    }
}
```

Commit.

---

## Task 13: SignupService + SignupController

**SignupService.java:**

```java
package com.devroom.auth.application;

import com.devroom.auth.domain.DevroomUser;
import com.devroom.auth.domain.DevroomUserRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SignupService {

    private final DevroomUserRepository userRepo;
    private final OutboxRepository outboxRepo;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;
    private final UUID demoTeamId;

    public SignupService(DevroomUserRepository userRepo, OutboxRepository outboxRepo,
                          PasswordEncoder encoder, ObjectMapper mapper,
                          @Value("${devroom.auth.demo-team-id}") String demoTeamId) {
        this.userRepo = userRepo;
        this.outboxRepo = outboxRepo;
        this.encoder = encoder;
        this.mapper = mapper;
        this.demoTeamId = UUID.fromString(demoTeamId);
    }

    @Transactional
    public Result signup(String email, String password) throws Exception {
        if (userRepo.existsByUsername(email)) {
            throw new DuplicateEmailException(email);
        }

        UUID userId = UUID.randomUUID();
        DevroomUser user = new DevroomUser(userId, email, encoder.encode(password), demoTeamId);
        userRepo.save(user);

        String payload = mapper.writeValueAsString(Map.of(
                "event_id", UUID.randomUUID().toString(),
                "event_type", "user.registered",
                "occurred_at", Instant.now().toString(),
                "user_id", userId.toString(),
                "email", email,
                "team_id", demoTeamId.toString()
        ));
        outboxRepo.save(new OutboxEvent("user.registered", payload));

        return new Result(userId);
    }

    public record Result(UUID userId) {}
}
```

Plus en `PasswordEncoder`-bean (BCrypt) i `@Configuration`-klass.

**SignupRequest.java + SignupResponse.java (records):**

```java
package com.devroom.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @Email @NotBlank String email,
        @NotBlank @Size(min = 8) String password
) {}
```

```java
package com.devroom.auth.web;

import java.util.UUID;

public record SignupResponse(UUID userId) {}
```

**SignupController.java (Variant C: @RestController JSON):**

```java
package com.devroom.auth.web;

import com.devroom.auth.application.SignupService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/signup")
public class SignupController {

    private final SignupService service;

    public SignupController(SignupService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest req) throws Exception {
        var result = service.signup(req.email(), req.password());
        return ResponseEntity
                .status(201)
                .body(new SignupResponse(result.userId()));
    }
}
```

**ExceptionHandlers.java — duplicate email → 409:**

```java
package com.devroom.auth.web;

import com.devroom.auth.application.DuplicateEmailException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ExceptionHandlers {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateEmailException e) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("error", "email_already_exists", "email", e.getEmail()));
    }
}
```

`DuplicateEmailException` behöver en `getEmail()`-getter — uppdatera entitetsklassen i Task 13:s applicationsdel.

Commit.

---

## Task 14: OutboxEvent + Repository + OutboxPublisher (stub)

Identisk struktur som i ursprunglig plan — `@Scheduled` publisher som loggar tills RabbitMQ-koppling görs i Plan 04.

(Inkluderar inte koden här för korthet — se ursprunglig Plan 02 Task 9 + Task 13. Logiken är oförändrad.)

Commit.

---

## Task 15: Testcontainers integrationstest

**Files:**
- Create: `services/auth-service/src/test/java/com/devroom/auth/AuthServiceIntegrationTest.java`

```java
package com.devroom.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AuthServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authdb");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    @Test
    void jwksEndpointExposesPublicKey() {
        ResponseEntity<Map> resp = http.getForEntity("/.well-known/jwks.json", Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsKey("keys");
    }

    @Test
    void openidConfigurationIsAvailable() {
        ResponseEntity<Map> resp = http.getForEntity("/.well-known/openid-configuration", Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsKey("issuer");
    }

    @Test
    void clientCredentialsGrantReturnsAccessToken() {
        // Auth med bot-service-klienten
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("bot-service", "<BOT_CLIENT_SECRET>");  // injicera via @Value eller env
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");
        body.add("scope", "bot:write");

        ResponseEntity<Map> resp = http.postForEntity("/oauth2/token",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsKey("access_token");
    }

    @Test
    void signupCreatesUserAndOutboxEvent() {
        ResponseEntity<String> resp = http.postForEntity("/signup",
                new HttpEntity<>("email=test@test.com&password=password123",
                        formHeaders()), String.class);
        // Expect redirect to /login?signup=success
        assertThat(resp.getStatusCode().is3xxRedirection()).isTrue();
        // Verifiera att rad finns i users + outbox via DataSource
    }

    private static HttpHeaders formHeaders() {
        var h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return h;
    }
}
```

Commit.

---

## Task 16: ADR-0002 (Outbox Pattern) — oförändrat

(Identiskt med ursprunglig plan task 15. Outbox-mönstret är oförändrat av OAuth2-pivoten.)

Commit.

---

## Task 17: ADR-0005 (Inga FK över DB-gränser) — oförändrat

(Identiskt med ursprunglig plan task 16.)

Commit.

---

## Task 18: Plan-slut: full verifikation

- [ ] `mvn -B clean verify` passerar (alla integrationstester)
- [ ] Manuell smoke-test:
  - `mvn -pl services/auth-service spring-boot:run`
  - Öppna http://localhost:8081/.well-known/jwks.json → JSON med RSA-nyckel
  - Öppna http://localhost:8081/.well-known/openid-configuration → discovery-dokument
  - Curl `client_credentials`-grant:
    ```bash
    curl -X POST http://localhost:8081/oauth2/token \
      -u "bot-service:$BOT_CLIENT_SECRET" \
      -d "grant_type=client_credentials&scope=bot:write" | jq
    ```
    → access_token i svar
  - Öppna http://localhost:8081/signup → formulär, skapa user
  - Verifiera user + outbox-rad i Postgres
- [ ] ADR-0002 + ADR-0005 skrivna (-0003 är redan skriven i pivot-arbete)

---

## Plan 2 — slut

Vid godkänd verifikation: gå vidare till plan 03 (User Service).
