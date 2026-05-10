# Plan 02: Auth Service

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implementera Auth Service: signup + login REST-endpoints, BCrypt-hashning, JWT-utfärdande via `auth-starter`, `outbox_events`-tabell med lokal publisher (RabbitMQ-koppling tas i plan 04). Vid plan-slut kan en user signa upp via `curl`, få tillbaka en JWT, validera den, och en outbox-rad ska finnas i AuthDB.

**Architecture:** Spring Boot 4 + JPA + Flyway + Postgres. Hexagonal-light: `web` (controllers), `application` (services), `domain` (entities/repos), `infra` (config). Outbox-rad skrivs i samma `@Transactional`-metod som credentials-raden för atomicity. Outbox-publishern är ett `@Scheduled`-bean som tills vidare bara loggar — RabbitMQ-publish wires in plan 04.

**Tech Stack:** Spring Boot 4, Spring Data JPA, Flyway, BCrypt (Spring Security crypto), Postgres 16, Testcontainers.

**Refererar spec:** sektion 3.1, 4.1, 5.3.

---

## File Structure

```
services/auth-service/
├── pom.xml
├── src/main/java/com/devroom/auth/
│   ├── AuthServiceApplication.java
│   ├── config/
│   │   ├── JwtConfig.java                     # @Bean JwtIssuer från PEM-filer
│   │   └── SecurityConfig.java                # öppna endpoints (signup/login är public)
│   ├── domain/
│   │   ├── Credentials.java                   # @Entity
│   │   ├── CredentialsRepository.java         # JpaRepository
│   │   ├── OutboxEvent.java                   # @Entity
│   │   └── OutboxRepository.java              # JpaRepository
│   ├── application/
│   │   ├── SignupService.java                 # @Transactional: insert + outbox + JWT
│   │   ├── LoginService.java                  # validate password + JWT
│   │   ├── PasswordHasher.java                # BCrypt wrapper
│   │   ├── DuplicateEmailException.java
│   │   └── BadCredentialsException.java
│   ├── infra/
│   │   └── OutboxPublisher.java               # @Scheduled, loggar (plan 4 wires RabbitMQ)
│   └── web/
│       ├── SignupController.java
│       ├── LoginController.java
│       ├── AuthRequestDtos.java
│       └── ExceptionHandlers.java
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_credentials.sql
│       └── V2__create_outbox_events.sql
├── src/test/java/com/devroom/auth/
│   ├── AuthServiceIntegrationTest.java        # Testcontainers
│   └── application/
│       ├── SignupServiceTest.java             # mocked deps
│       └── LoginServiceTest.java
└── src/test/resources/
    ├── application-test.yml
    └── keys/
        ├── test-private.pem
        └── test-public.pem
```

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
        <dependency>
            <groupId>com.devroom</groupId>
            <artifactId>auth-starter</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
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
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-crypto</artifactId>
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

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
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

Edit `pom.xml`, ändra `<modules>`-blocket till:

```xml
<modules>
    <module>modules/auth-starter</module>
    <module>services/auth-service</module>
</modules>
```

- [ ] **Step 3: Verifiera bygge**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS (kompilerar tom modul utan källkod).

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/pom.xml pom.xml
git commit -m "feat(auth-service): scaffold module with Spring Boot, JPA, Flyway"
```

---

## Task 2: Skapa Spring Boot application skeleton

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/AuthServiceApplication.java`
- Create: `services/auth-service/src/main/resources/application.yml`

- [ ] **Step 1: Skapa application class**

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

- [ ] **Step 2: Skapa application.yml**

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

devroom:
  auth:
    private-key-path: ${AUTH_PRIVATE_KEY_PATH:./keys/private.pem}
    public-key-path: ${AUTH_PUBLIC_KEY_PATH:./keys/public.pem}
    issuer: auth-service
    user-token-ttl: PT1H        # 1 hour
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

- [ ] **Step 3: Commit**

```bash
git add services/auth-service/src/
git commit -m "feat(auth-service): bootstrap Spring Boot application with config"
```

---

## Task 3: Generera RSA-nyckelpar för dev (commitas inte)

- [ ] **Step 1: Generera nycklar lokalt**

Run:
```bash
mkdir -p keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
ls -la keys/
```

Expected: två PEM-filer.

- [ ] **Step 2: Verifiera att `keys/` ignoreras av git**

Run: `git status`
Expected: `keys/` listas INTE som untracked (matchar `*.pem` i `.gitignore`).

- [ ] **Step 3: Skapa README i keys-mappen**

Create `keys/README.md`:

```markdown
# Local development keys

Generera lokalt:

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out keys/private.pem
openssl rsa -in keys/private.pem -pubout -out keys/public.pem
```

Dessa filer commitas INTE (matchar `*.pem` i `.gitignore`).
För K8s: skapas via `kubectl create secret generic auth-private-key --from-file=...` (se plan 10).
```

- [ ] **Step 4: Edit `.gitignore` för att tillåta README**

Verifiera att `.gitignore` har en undantagsregel — om inte, lägg till:

```
*.pem
!**/keys/sample/*.pem
!keys/README.md
```

- [ ] **Step 5: Commit**

```bash
git add keys/README.md .gitignore
git commit -m "docs: add local key generation instructions"
```

---

## Task 4: Skriv Flyway-migration för `credentials`-tabell

**Files:**
- Create: `services/auth-service/src/main/resources/db/migration/V1__create_credentials.sql`

- [ ] **Step 1: Skriv migrationen**

```sql
CREATE TABLE credentials (
    user_id        UUID PRIMARY KEY,
    email          VARCHAR(255) UNIQUE NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_credentials_email ON credentials(email);
```

- [ ] **Step 2: Verifiera migration-syntax mot lokal Postgres**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d auth-db
sleep 5
docker exec -i devroom-auth-db psql -U dbuser -d authdb < services/auth-service/src/main/resources/db/migration/V1__create_credentials.sql
docker exec devroom-auth-db psql -U dbuser -d authdb -c "\d credentials"
```

Expected: tabellen `credentials` listas, kolumner stämmer.

Run:
```bash
docker exec devroom-auth-db psql -U dbuser -d authdb -c "DROP TABLE credentials;"
docker compose -f docker-compose.dev.yml down
```

(Cleanup — Flyway hanterar detta i nästa run.)

- [ ] **Step 3: Commit**

```bash
git add services/auth-service/src/main/resources/db/migration/V1__create_credentials.sql
git commit -m "feat(auth-service): add credentials table migration"
```

---

## Task 5: Implementera `Credentials`-entitet och repository

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/Credentials.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/CredentialsRepository.java`

- [ ] **Step 1: Skapa entitet**

```java
package com.devroom.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credentials")
public class Credentials {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Credentials() {}

    public Credentials(UUID userId, String email, String passwordHash) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 2: Skapa repository**

```java
package com.devroom.auth.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface CredentialsRepository extends JpaRepository<Credentials, UUID> {
    Optional<Credentials> findByEmail(String email);
    boolean existsByEmail(String email);
}
```

- [ ] **Step 3: Kompilera**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/domain/
git commit -m "feat(auth-service): add Credentials entity and repository"
```

---

## Task 6: Implementera `PasswordHasher` (BCrypt-wrapper)

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/application/PasswordHasher.java`
- Create: `services/auth-service/src/test/java/com/devroom/auth/application/PasswordHasherTest.java`

- [ ] **Step 1: Skriv det failande testet**

```java
package com.devroom.auth.application;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashIsDifferentFromPlainText() {
        String hash = hasher.hash("supersecret");
        assertNotEquals("supersecret", hash);
        assertTrue(hash.startsWith("$2a$") || hash.startsWith("$2b$"), "BCrypt prefix expected");
    }

    @Test
    void verifiesCorrectPassword() {
        String hash = hasher.hash("supersecret");
        assertTrue(hasher.matches("supersecret", hash));
    }

    @Test
    void rejectsWrongPassword() {
        String hash = hasher.hash("supersecret");
        assertFalse(hasher.matches("wrong", hash));
    }
}
```

- [ ] **Step 2: Kör — ska faila**

Run: `mvn -pl services/auth-service test`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implementera**

```java
package com.devroom.auth.application;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public String hash(String plain) {
        return encoder.encode(plain);
    }

    public boolean matches(String plain, String hash) {
        return encoder.matches(plain, hash);
    }
}
```

- [ ] **Step 4: Kör — ska passa**

Run: `mvn -pl services/auth-service test`
Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/application/PasswordHasher.java \
        services/auth-service/src/test/java/com/devroom/auth/application/PasswordHasherTest.java
git commit -m "feat(auth-service): add PasswordHasher (BCrypt wrapper)"
```

---

## Task 7: Konfigurera `JwtIssuer`-bean

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/config/JwtConfig.java`

- [ ] **Step 1: Skapa config**

```java
package com.devroom.auth.config;

import com.devroom.auth.JwtIssuer;
import com.devroom.auth.KeyLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtIssuer jwtIssuer(@Value("${devroom.auth.private-key-path}") String privateKeyPath) {
        return new JwtIssuer(KeyLoader.loadPrivateKey(Path.of(privateKeyPath)));
    }

    @Bean
    public JwtSettings jwtSettings(
            @Value("${devroom.auth.issuer}") String issuer,
            @Value("${devroom.auth.user-token-ttl}") Duration userTokenTtl,
            @Value("${devroom.auth.demo-team-id}") String demoTeamId
    ) {
        return new JwtSettings(issuer, userTokenTtl, demoTeamId);
    }

    public record JwtSettings(String issuer, Duration userTokenTtl, String demoTeamId) {}
}
```

- [ ] **Step 2: Kompilera**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/config/JwtConfig.java
git commit -m "feat(auth-service): wire JwtIssuer bean from PEM config"
```

---

## Task 8: Skriv Flyway-migration för `outbox_events`

**Files:**
- Create: `services/auth-service/src/main/resources/db/migration/V2__create_outbox_events.sql`

- [ ] **Step 1: Skriv migrationen**

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

- [ ] **Step 2: Commit**

```bash
git add services/auth-service/src/main/resources/db/migration/V2__create_outbox_events.sql
git commit -m "feat(auth-service): add outbox_events table migration"
```

---

## Task 9: Implementera `OutboxEvent`-entitet och repository

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/OutboxEvent.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/domain/OutboxRepository.java`

- [ ] **Step 1: Skapa entitet**

```java
package com.devroom.auth.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected OutboxEvent() {}

    public OutboxEvent(String eventType, String payload) {
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }

    public void markProcessed() {
        this.processedAt = Instant.now();
    }
}
```

- [ ] **Step 2: Skapa repository**

```java
package com.devroom.auth.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.processedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> findUnprocessed(Pageable pageable);
}
```

- [ ] **Step 3: Kompilera**

Run: `mvn -pl services/auth-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/domain/Outbox*.java
git commit -m "feat(auth-service): add OutboxEvent entity and repository"
```

---

## Task 10: Implementera `SignupService`

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/application/SignupService.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/application/DuplicateEmailException.java`
- Create: `services/auth-service/src/test/java/com/devroom/auth/application/SignupServiceTest.java`

- [ ] **Step 1: Skapa exception**

```java
package com.devroom.auth.application;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String email) {
        super("Email already exists: " + email);
    }
}
```

- [ ] **Step 2: Skriv det failande testet**

```java
package com.devroom.auth.application;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtIssuer;
import com.devroom.auth.config.JwtConfig.JwtSettings;
import com.devroom.auth.domain.Credentials;
import com.devroom.auth.domain.CredentialsRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SignupServiceTest {

    private CredentialsRepository credentialsRepo;
    private OutboxRepository outboxRepo;
    private PasswordHasher hasher;
    private JwtIssuer issuer;
    private SignupService service;

    @BeforeEach
    void setup() {
        credentialsRepo = mock(CredentialsRepository.class);
        outboxRepo = mock(OutboxRepository.class);
        hasher = mock(PasswordHasher.class);
        issuer = mock(JwtIssuer.class);
        ObjectMapper mapper = new ObjectMapper();
        JwtSettings settings = new JwtSettings("auth-service", Duration.ofHours(1), "team-demo");

        service = new SignupService(credentialsRepo, outboxRepo, hasher, issuer, settings, mapper);
    }

    @Test
    void signupCreatesCredentialsAndOutboxEventAndIssuesJwt() {
        when(credentialsRepo.existsByEmail("annika@example.com")).thenReturn(false);
        when(hasher.hash("password123")).thenReturn("hashed");
        when(issuer.issue(any(JwtClaims.class))).thenReturn("jwt-token");

        SignupService.Result result = service.signup("annika@example.com", "password123");

        assertNotNull(result.userId());
        assertEquals("jwt-token", result.jwt());

        ArgumentCaptor<Credentials> credCaptor = ArgumentCaptor.forClass(Credentials.class);
        verify(credentialsRepo).save(credCaptor.capture());
        assertEquals("annika@example.com", credCaptor.getValue().getEmail());
        assertEquals("hashed", credCaptor.getValue().getPasswordHash());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepo).save(outboxCaptor.capture());
        assertEquals("user.registered", outboxCaptor.getValue().getEventType());
        assertTrue(outboxCaptor.getValue().getPayload().contains(result.userId().toString()));
    }

    @Test
    void signupWithExistingEmailThrows() {
        when(credentialsRepo.existsByEmail("dup@example.com")).thenReturn(true);

        assertThrows(DuplicateEmailException.class,
                () -> service.signup("dup@example.com", "password"));
        verify(credentialsRepo, never()).save(any());
        verify(outboxRepo, never()).save(any());
    }
}
```

- [ ] **Step 3: Kör — ska faila**

Run: `mvn -pl services/auth-service test`
Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implementera `SignupService`**

```java
package com.devroom.auth.application;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtIssuer;
import com.devroom.auth.config.JwtConfig.JwtSettings;
import com.devroom.auth.domain.Credentials;
import com.devroom.auth.domain.CredentialsRepository;
import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class SignupService {

    private final CredentialsRepository credentialsRepo;
    private final OutboxRepository outboxRepo;
    private final PasswordHasher hasher;
    private final JwtIssuer issuer;
    private final JwtSettings settings;
    private final ObjectMapper mapper;

    public SignupService(CredentialsRepository credentialsRepo, OutboxRepository outboxRepo,
                          PasswordHasher hasher, JwtIssuer issuer, JwtSettings settings,
                          ObjectMapper mapper) {
        this.credentialsRepo = credentialsRepo;
        this.outboxRepo = outboxRepo;
        this.hasher = hasher;
        this.issuer = issuer;
        this.settings = settings;
        this.mapper = mapper;
    }

    @Transactional
    public Result signup(String email, String password) {
        if (credentialsRepo.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        UUID userId = UUID.randomUUID();
        String hash = hasher.hash(password);
        credentialsRepo.save(new Credentials(userId, email, hash));

        String payload = serializeUserRegistered(userId, email);
        outboxRepo.save(new OutboxEvent("user.registered", payload));

        Instant now = Instant.now();
        JwtClaims claims = JwtClaims.forUser(
                userId.toString(),
                settings.demoTeamId(),
                now,
                now.plus(settings.userTokenTtl())
        );
        String jwt = issuer.issue(claims);

        return new Result(userId, jwt);
    }

    private String serializeUserRegistered(UUID userId, String email) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "event_type", "user.registered",
                    "occurred_at", Instant.now().toString(),
                    "user_id", userId.toString(),
                    "email", email,
                    "team_id", settings.demoTeamId()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }

    public record Result(UUID userId, String jwt) {}
}
```

- [ ] **Step 5: Kör — ska passa**

Run: `mvn -pl services/auth-service test`
Expected: BUILD SUCCESS, 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/application/SignupService.java \
        services/auth-service/src/main/java/com/devroom/auth/application/DuplicateEmailException.java \
        services/auth-service/src/test/java/com/devroom/auth/application/SignupServiceTest.java
git commit -m "feat(auth-service): add SignupService with atomic outbox write"
```

---

## Task 11: Implementera `LoginService`

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/application/LoginService.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/application/BadCredentialsException.java`
- Create: `services/auth-service/src/test/java/com/devroom/auth/application/LoginServiceTest.java`

- [ ] **Step 1: Skapa exception**

```java
package com.devroom.auth.application;

public class BadCredentialsException extends RuntimeException {
    public BadCredentialsException() {
        super("Invalid email or password");
    }
}
```

- [ ] **Step 2: Skriv det failande testet**

```java
package com.devroom.auth.application;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtIssuer;
import com.devroom.auth.config.JwtConfig.JwtSettings;
import com.devroom.auth.domain.Credentials;
import com.devroom.auth.domain.CredentialsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginServiceTest {

    private CredentialsRepository credentialsRepo;
    private PasswordHasher hasher;
    private JwtIssuer issuer;
    private LoginService service;

    @BeforeEach
    void setup() {
        credentialsRepo = mock(CredentialsRepository.class);
        hasher = mock(PasswordHasher.class);
        issuer = mock(JwtIssuer.class);
        JwtSettings settings = new JwtSettings("auth-service", Duration.ofHours(1), "team-demo");
        service = new LoginService(credentialsRepo, hasher, issuer, settings);
    }

    @Test
    void loginIssuesJwtForValidCredentials() {
        UUID userId = UUID.randomUUID();
        Credentials cred = new Credentials(userId, "annika@example.com", "stored-hash");
        when(credentialsRepo.findByEmail("annika@example.com")).thenReturn(Optional.of(cred));
        when(hasher.matches("password", "stored-hash")).thenReturn(true);
        when(issuer.issue(any(JwtClaims.class))).thenReturn("jwt-xyz");

        LoginService.Result result = service.login("annika@example.com", "password");

        assertEquals(userId, result.userId());
        assertEquals("jwt-xyz", result.jwt());
    }

    @Test
    void loginRejectsUnknownEmail() {
        when(credentialsRepo.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        assertThrows(BadCredentialsException.class,
                () -> service.login("unknown@example.com", "password"));
    }

    @Test
    void loginRejectsWrongPassword() {
        UUID userId = UUID.randomUUID();
        Credentials cred = new Credentials(userId, "a@b.com", "stored-hash");
        when(credentialsRepo.findByEmail("a@b.com")).thenReturn(Optional.of(cred));
        when(hasher.matches("wrong", "stored-hash")).thenReturn(false);

        assertThrows(BadCredentialsException.class,
                () -> service.login("a@b.com", "wrong"));
    }
}
```

- [ ] **Step 3: Kör — ska faila**

Run: `mvn -pl services/auth-service test`
Expected: COMPILATION FAILURE.

- [ ] **Step 4: Implementera**

```java
package com.devroom.auth.application;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtIssuer;
import com.devroom.auth.config.JwtConfig.JwtSettings;
import com.devroom.auth.domain.Credentials;
import com.devroom.auth.domain.CredentialsRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class LoginService {

    private final CredentialsRepository credentialsRepo;
    private final PasswordHasher hasher;
    private final JwtIssuer issuer;
    private final JwtSettings settings;

    public LoginService(CredentialsRepository credentialsRepo, PasswordHasher hasher,
                         JwtIssuer issuer, JwtSettings settings) {
        this.credentialsRepo = credentialsRepo;
        this.hasher = hasher;
        this.issuer = issuer;
        this.settings = settings;
    }

    public Result login(String email, String password) {
        Credentials cred = credentialsRepo.findByEmail(email)
                .orElseThrow(BadCredentialsException::new);
        if (!hasher.matches(password, cred.getPasswordHash())) {
            throw new BadCredentialsException();
        }
        Instant now = Instant.now();
        JwtClaims claims = JwtClaims.forUser(
                cred.getUserId().toString(),
                settings.demoTeamId(),
                now,
                now.plus(settings.userTokenTtl())
        );
        return new Result(cred.getUserId(), issuer.issue(claims));
    }

    public record Result(UUID userId, String jwt) {}
}
```

- [ ] **Step 5: Kör — ska passa**

Run: `mvn -pl services/auth-service test`
Expected: BUILD SUCCESS, 8 tests passed.

- [ ] **Step 6: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/application/LoginService.java \
        services/auth-service/src/main/java/com/devroom/auth/application/BadCredentialsException.java \
        services/auth-service/src/test/java/com/devroom/auth/application/LoginServiceTest.java
git commit -m "feat(auth-service): add LoginService"
```

---

## Task 12: Implementera REST-controllers + DTOs + global exception handling

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/web/AuthRequestDtos.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/web/SignupController.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/web/LoginController.java`
- Create: `services/auth-service/src/main/java/com/devroom/auth/web/ExceptionHandlers.java`

- [ ] **Step 1: Skapa DTOs**

```java
package com.devroom.auth.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class AuthRequestDtos {

    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 128) String password
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record AuthResponse(UUID userId, String jwt) {}

    public record ErrorResponse(String error, String message) {}

    private AuthRequestDtos() {}
}
```

- [ ] **Step 2: Skapa SignupController**

```java
package com.devroom.auth.web;

import com.devroom.auth.application.SignupService;
import com.devroom.auth.web.AuthRequestDtos.AuthResponse;
import com.devroom.auth.web.AuthRequestDtos.SignupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class SignupController {

    private final SignupService service;

    public SignupController(SignupService service) {
        this.service = service;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        SignupService.Result result = service.signup(req.email(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(result.userId(), result.jwt()));
    }
}
```

- [ ] **Step 3: Skapa LoginController**

```java
package com.devroom.auth.web;

import com.devroom.auth.application.LoginService;
import com.devroom.auth.web.AuthRequestDtos.AuthResponse;
import com.devroom.auth.web.AuthRequestDtos.LoginRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class LoginController {

    private final LoginService service;

    public LoginController(LoginService service) {
        this.service = service;
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        LoginService.Result result = service.login(req.email(), req.password());
        return new AuthResponse(result.userId(), result.jwt());
    }
}
```

- [ ] **Step 4: Skapa ExceptionHandlers**

```java
package com.devroom.auth.web;

import com.devroom.auth.application.BadCredentialsException;
import com.devroom.auth.application.DuplicateEmailException;
import com.devroom.auth.web.AuthRequestDtos.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ExceptionHandlers {

    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateEmailException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate_email", e.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCreds(BadCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_credentials", "Invalid email or password"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("validation_error", msg));
    }
}
```

- [ ] **Step 5: Kompilera och kör tester**

Run: `mvn -pl services/auth-service test`
Expected: BUILD SUCCESS, 8 tester (controllerns testas i integration-testet i Task 14).

- [ ] **Step 6: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/web/
git commit -m "feat(auth-service): add REST controllers for signup and login"
```

---

## Task 13: Implementera `OutboxPublisher`-stub

**Files:**
- Create: `services/auth-service/src/main/java/com/devroom/auth/infra/OutboxPublisher.java`

- [ ] **Step 1: Skapa publisher-stub (loggar bara — RabbitMQ-koppling i plan 04)**

```java
package com.devroom.auth.infra;

import com.devroom.auth.domain.OutboxEvent;
import com.devroom.auth.domain.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repo;

    public OutboxPublisher(OutboxRepository repo) {
        this.repo = repo;
    }

    @Scheduled(fixedDelayString = "PT0.5S")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> events = repo.findUnprocessed(PageRequest.of(0, BATCH_SIZE));
        if (events.isEmpty()) {
            return;
        }
        for (OutboxEvent event : events) {
            // TODO plan-04: publish to RabbitMQ
            log.info("Outbox publish (stub): type={} payload={}", event.getEventType(), event.getPayload());
            event.markProcessed();
        }
        repo.saveAll(events);
        log.info("Marked {} outbox events as processed", events.size());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add services/auth-service/src/main/java/com/devroom/auth/infra/OutboxPublisher.java
git commit -m "feat(auth-service): add OutboxPublisher stub (RabbitMQ wiring in plan 04)"
```

---

## Task 14: Skriv Testcontainers-baserat integrationstest

**Files:**
- Create: `services/auth-service/src/test/resources/application-test.yml`
- Create: `services/auth-service/src/test/resources/keys/test-private.pem`
- Create: `services/auth-service/src/test/resources/keys/test-public.pem`
- Create: `services/auth-service/src/test/java/com/devroom/auth/AuthServiceIntegrationTest.java`

- [ ] **Step 1: Generera test-nycklar**

Run:
```bash
mkdir -p services/auth-service/src/test/resources/keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out services/auth-service/src/test/resources/keys/test-private.pem
openssl rsa -in services/auth-service/src/test/resources/keys/test-private.pem \
  -pubout -out services/auth-service/src/test/resources/keys/test-public.pem
```

OBS: dessa nycklar är *bara för tester*, ingår i repo. Lägg till specifik undantagsregel i `.gitignore`:

Modify `.gitignore` om nödvändigt:

```
*.pem
!services/*/src/test/resources/keys/*.pem
!keys/README.md
```

- [ ] **Step 2: Skapa test-config**

```yaml
# services/auth-service/src/test/resources/application-test.yml
devroom:
  auth:
    private-key-path: classpath:keys/test-private.pem
    public-key-path: classpath:keys/test-public.pem
    issuer: auth-service
    user-token-ttl: PT1H
    demo-team-id: 11111111-1111-1111-1111-111111111111

logging:
  level:
    com.devroom.auth: DEBUG
```

OBS: `KeyLoader.loadPrivateKey(Path)` tar `Path`, men classpath: går via Resource. Vi behöver justera config för att stödja classpath-paths. Alternativt: kopiera nycklarna till en temp-dir i testet via `@TempDir`. Vi gör det enklare: skriv `JwtConfig` så den läser via Spring `Resource` istället för `Path`.

Fix: revidera `JwtConfig.java`:

```java
package com.devroom.auth.config;

import com.devroom.auth.JwtIssuer;
import com.devroom.auth.KeyLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class JwtConfig {

    @Bean
    public JwtIssuer jwtIssuer(@Value("${devroom.auth.private-key-path}") Resource privateKeyResource) throws IOException {
        Path tempPath = Files.createTempFile("auth-private-", ".pem");
        Files.copy(privateKeyResource.getInputStream(), tempPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        tempPath.toFile().deleteOnExit();
        return new JwtIssuer(KeyLoader.loadPrivateKey(tempPath));
    }

    @Bean
    public JwtSettings jwtSettings(
            @Value("${devroom.auth.issuer}") String issuer,
            @Value("${devroom.auth.user-token-ttl}") Duration userTokenTtl,
            @Value("${devroom.auth.demo-team-id}") String demoTeamId
    ) {
        return new JwtSettings(issuer, userTokenTtl, demoTeamId);
    }

    public record JwtSettings(String issuer, Duration userTokenTtl, String demoTeamId) {}
}
```

Detta använder Spring `Resource` så både `file:./keys/private.pem` och `classpath:keys/test-private.pem` fungerar.

Uppdatera `application.yml`:

```yaml
devroom:
  auth:
    private-key-path: ${AUTH_PRIVATE_KEY_PATH:file:./keys/private.pem}
    ...
```

- [ ] **Step 3: Skriv integration-testet**

```java
package com.devroom.auth;

import com.devroom.auth.JwtClaims;
import com.devroom.auth.JwtValidator;
import com.devroom.auth.KeyLoader;
import com.devroom.auth.domain.CredentialsRepository;
import com.devroom.auth.domain.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("authdb")
            .withUsername("dbuser")
            .withPassword("dbpass");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort int port;

    @Autowired TestRestTemplate http;
    @Autowired CredentialsRepository credRepo;
    @Autowired OutboxRepository outboxRepo;
    @Autowired ObjectMapper mapper;

    @AfterEach
    void cleanup() {
        outboxRepo.deleteAll();
        credRepo.deleteAll();
    }

    @Test
    void signupCreatesCredentialsOutboxAndReturnsValidJwt() throws Exception {
        ResponseEntity<Map> resp = http.postForEntity(
                "/auth/signup",
                Map.of("email", "annika@example.com", "password", "password123"),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsKeys("userId", "jwt");

        String userIdStr = (String) resp.getBody().get("userId");
        String jwt = (String) resp.getBody().get("jwt");

        // Credentials skrevs
        assertThat(credRepo.findByEmail("annika@example.com")).isPresent();

        // Outbox-rad finns
        var outboxRows = outboxRepo.findAll();
        assertThat(outboxRows).hasSize(1);
        assertThat(outboxRows.get(0).getEventType()).isEqualTo("user.registered");
        JsonNode payload = mapper.readTree(outboxRows.get(0).getPayload());
        assertThat(payload.get("user_id").asText()).isEqualTo(userIdStr);

        // JWT är valid
        Path tempPub = Files.createTempFile("test-pub-", ".pem");
        Files.copy(new ClassPathResource("keys/test-public.pem").getInputStream(), tempPub,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        PublicKey pub = KeyLoader.loadPublicKey(tempPub);
        JwtValidator validator = new JwtValidator(pub, "auth-service");
        JwtClaims claims = validator.validate(jwt);
        assertThat(claims.subject()).isEqualTo(userIdStr);
    }

    @Test
    void duplicateSignupReturns409() {
        http.postForEntity("/auth/signup", Map.of("email", "dup@example.com", "password", "pass1234"), Map.class);
        ResponseEntity<Map> resp = http.postForEntity(
                "/auth/signup", Map.of("email", "dup@example.com", "password", "pass1234"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginReturnsJwtForValidCredentials() {
        http.postForEntity("/auth/signup", Map.of("email", "login@example.com", "password", "password123"), Map.class);

        ResponseEntity<Map> resp = http.postForEntity(
                "/auth/login", Map.of("email", "login@example.com", "password", "password123"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("jwt");
    }

    @Test
    void loginRejectsWrongPassword() {
        http.postForEntity("/auth/signup", Map.of("email", "wrong@example.com", "password", "password123"), Map.class);
        ResponseEntity<Map> resp = http.postForEntity(
                "/auth/login", Map.of("email", "wrong@example.com", "password", "WRONG"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

- [ ] **Step 4: Kör testet**

Run: `mvn -pl services/auth-service verify`
Expected: BUILD SUCCESS, alla tester passar (inkl. Testcontainers).

OBS: första körningen tar tid pga Docker-image-pull.

- [ ] **Step 5: Commit**

```bash
git add services/auth-service/src/test/
git commit -m "test(auth-service): add Testcontainers integration test for signup + login"
```

---

## Task 15: Skriv ADR-0002 (Outbox Pattern)

**Files:**
- Create: `docs/adr/0002-outbox-pattern.md`

- [ ] **Step 1: Skriv ADR-0002**

```markdown
# ADR-0002: Outbox Pattern för signup-event

**Status:** Accepted
**Date:** 2026-05-10

## Context

Vid signup måste Auth Service göra två saker:
1. Skapa credentials-rad i AuthDB
2. Publicera `user.registered`-event till RabbitMQ så User Service kan skapa profil

Om vi gör dessa som två separata operationer riskerar vi dual-write-problemet: krasch mellan steg 1 och 2 lämnar systemet i ett läge där en user finns i Auth men aldrig fick en profil.

## Decision

Vi använder Outbox Pattern. I samma DB-transaktion som credentials-raden skapas, skrivs också en rad till `outbox_events`-tabellen. Postgres garanterar atomicity. En `@Scheduled`-publisher pollar `outbox_events`-tabellen och publicerar olästa rader till RabbitMQ.

## Considered alternatives

**Alt A: Synkron orchestration via BFF.** BFF anropar Auth, sedan User. Vid fel: kompenserande delete. Avvisad — "best effort"-kompensering är fortfarande en eventually-consistent semantik dold bakom synkron API. Outbox är mer transparent.

**Alt B: Event-driven choreography utan outbox.** Auth publicerar direkt till RabbitMQ efter DB-write. Avvisad — exakt det dual-write-problem vi vill undvika.

**Alt C: CDC-baserad outbox (Debezium).** Avvisad — kraftfullt men för komplext för 140h budget. Polling fungerar utmärkt för demon.

## Consequences

**Positiva:**
- Stark garanti: om credentials skrevs har eventet skrivits till outbox.
- Isolering: RabbitMQ kan vara nere utan att signup failar.
- En outbox-tabell är enkel att förstå i kodgranskning.

**Negativa:**
- At-least-once-leverans: publishern kan krascha mellan publish och mark-processed → samma event publiceras igen. Mitigation: idempotency på consumer-sidan (User Service kollar om user_id redan finns).
- Liten latens (~500ms-polling-cykel) mellan signup och profil-skapande. Acceptabelt: frontend visar "Laddar profil..." och retryar.
- En tabell att städa över tid. Cron-rensning av `processed_at IS NOT NULL`-rader äldre än 30 dagar (future work).

## References

- Spec sektion 5.3
- Pattern: https://microservices.io/patterns/data/transactional-outbox.html
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0002-outbox-pattern.md
git commit -m "docs(adr): 0002 outbox pattern for signup event"
```

---

## Task 16: Skriv ADR-0005 (Inga FK över DB-gränser)

**Files:**
- Create: `docs/adr/0005-no-cross-db-foreign-keys.md`

- [ ] **Step 1: Skriv ADR-0005**

```markdown
# ADR-0005: Inga foreign keys över databas-gränser

**Status:** Accepted
**Date:** 2026-05-10

## Context

Devroom har tre databaser (AuthDB, UserDB, MessageDB), en per service. Många kolumner refererar till entiteter i andra databaser: `messages.sender_id` pekar på en row i `users` (UserDB), `messages.channel_id` pekar på `channels` (MessageDB själv, OK), credentials.user_id matchar users.user_id, och så vidare.

## Decision

Vi använder **inga foreign keys över databas-gränser**. FK-constraints är enbart tillåtna inom samma databas (t.ex. `messages.parent_message_id REFERENCES messages.id`). Cross-DB-referenser är "soft references" — bara UUID:er som application-koden förväntar sig matchar.

## Considered alternatives

**Alt A: Distributed transactions (XA).** Avvisad — Postgres har stöd för det, men det kopplar samman tjänster i ett gemensamt commit-protokoll. Bryter mot service-isolering.

**Alt B: Event-driven sync med materialized views.** Auth publicerar `user.registered`, andra services lyssnar och bygger up read-replicas av users-tabellen i sina egna DB:er. Avvisad — för stor för 140h, värd för future work.

## Consequences

**Positiva:**
- Service-isolering: en service kan migrera sin databas utan att hänsyn tas till andras.
- Inga distributed-transaction-problem.
- Tydlig boundary i kod: cross-service-uppslag sker via gRPC, inte JOIN.

**Negativa:**
- Application-level integrity: koden måste hantera att en `sender_id` kanske inte längre finns i UserDB (t.ex. user borttagen). Mitigation: vi tillåter inte user-borttagning i v1; om implementerad senare hanteras dangling references via soft-delete.
- Hård fail vid mention-resolution-miss (ADR-0007 reservpott motiverar valet).

## References

- Spec sektion 3.3
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0005-no-cross-db-foreign-keys.md
git commit -m "docs(adr): 0005 no foreign keys across database boundaries"
```

---

## Task 17: Plan-slut: full verifikation

- [ ] **Step 1: Bygg från scratch**

Run: `mvn -B clean verify`
Expected: BUILD SUCCESS, alla tester passar (auth-starter: 9, auth-service: 8 unit + 4 integration = 12).

- [ ] **Step 2: Manuell smoke-test mot lokal infra**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d auth-db
sleep 5
mvn -pl services/auth-service spring-boot:run &
APP_PID=$!
sleep 15

# Signup
curl -X POST http://localhost:8081/auth/signup \
  -H "Content-Type: application/json" \
  -d '{"email":"manual@test.com","password":"password123"}'

# Login
curl -X POST http://localhost:8081/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"manual@test.com","password":"password123"}'

# Verifiera outbox-rad
docker exec devroom-auth-db psql -U dbuser -d authdb -c \
  "SELECT id, event_type, processed_at FROM outbox_events;"

kill $APP_PID
docker compose -f docker-compose.dev.yml down
```

Expected:
- Signup returnerar 201 med `userId` + `jwt`
- Login returnerar 200 med `jwt`
- Outbox-tabellen har en rad med `event_type='user.registered'` och `processed_at` satt (av stub-publishern)

- [ ] **Step 3: Slutkontroll mot Plan-2-Goal**

Checklista:
- [ ] `mvn verify` passerar
- [ ] Signup via curl ger JWT + 201
- [ ] Login via curl ger JWT + 200
- [ ] Outbox-rad finns efter signup
- [ ] Stub-publisher loggar och markerar processed
- [ ] ADR-0002 + ADR-0005 skrivna

---

## Plan 2 — slut

Vid godkänd verifikation: gå vidare till plan 03 (User Service).
