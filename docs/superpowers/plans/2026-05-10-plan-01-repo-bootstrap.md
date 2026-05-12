# Plan 01: Repo Bootstrap + Infrastruktur

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sätt upp Maven multi-module monorepo, docker-compose med Postgres + RabbitMQ, delad `auth-starter`-modul med JWT-utility, gRPC `.proto`-fil, root CI. Vid plan-slut kan `mvn verify` och `docker compose up` köras framgångsrikt och `auth-starter` har testad JWT-issue/validate.

**Architecture:** Maven multi-module med parent POM som låser Spring Boot 4 BOM och Java 21. `auth-starter` är ett delat library som varje service drar in. Tre separata Postgres-containrar i compose (matchar K8s-topologin senare). RabbitMQ med management-UI för debug.

**Tech Stack:** Java 21, Spring Boot 4.0.0, Maven 3.9+, Docker Compose, Postgres 16, RabbitMQ 4 (management), JJWT 0.12 för JWT, Bouncycastle för RSA-keygen i tester.

**Refererar spec:** `docs/superpowers/specs/2026-05-10-devroom-design.md` sektion 7 (repo-struktur) + sektion 4 (säkerhet/JWT).

---

## File Structure

**Skapas i denna plan:**

```
devroom/
├── pom.xml                              # parent POM
├── .gitignore
├── .editorconfig
├── README.md                            # bare-bones, expanderas i plan 10
├── docker-compose.yml                   # full stack (services kommer senare)
├── docker-compose.dev.yml               # bara infra (postgres + rabbitmq)
├── infra/
│   └── postgres-init/
│       ├── auth-db-init.sql
│       ├── user-db-init.sql
│       └── message-db-init.sql
├── modules/auth-starter/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/devroom/auth/
│       │   ├── JwtIssuer.java
│       │   ├── JwtValidator.java
│       │   ├── JwtClaims.java
│       │   └── KeyLoader.java
│       └── test/java/com/devroom/auth/
│           ├── JwtIssuerTest.java
│           └── JwtValidatorTest.java
├── proto/
│   └── user.proto                       # gRPC-kontrakt för User Service
└── .github/workflows/ci.yml             # bygg + test alla moduler
```

---

## Task 1: Initiera Maven multi-module skelett

**Files:**
- Create: `pom.xml`
- Create: `.gitignore`
- Create: `.editorconfig`
- Create: `README.md`

- [ ] **Step 1: Skapa parent `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.devroom</groupId>
    <artifactId>devroom-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Devroom Parent</name>
    <description>Distributed chat with @-mentionable AI mentors</description>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>4.0.0</spring-boot.version>
        <jjwt.version>0.12.6</jjwt.version>
        <testcontainers.version>1.20.4</testcontainers.version>
        <grpc.version>1.68.0</grpc.version>
        <protobuf.version>3.25.5</protobuf.version>
    </properties>

    <modules>
        <module>modules/auth-starter</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.5.2</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-failsafe-plugin</artifactId>
                    <version>3.5.2</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 2: Skapa `.gitignore`**

```
# Build output
target/
build/
out/
*.class

# IDE
.idea/
*.iml
.vscode/
.project
.classpath
.settings/

# OS
.DS_Store
Thumbs.db

# Maven
.mvn/wrapper/maven-wrapper.jar
dependency-reduced-pom.xml

# Node / Next.js (frontend kommer senare)
node_modules/
.next/
.env.local

# Secrets
*.pem
!**/keys/sample/*.pem
secrets.yaml

# Logs
*.log
logs/
```

- [ ] **Step 3: Skapa `.editorconfig`**

```
root = true

[*]
end_of_line = lf
insert_final_newline = true
charset = utf-8
indent_style = space
indent_size = 4
trim_trailing_whitespace = true

[*.{md,yml,yaml,json,xml}]
indent_size = 2

[*.{ts,tsx,js,jsx}]
indent_size = 2
```

- [ ] **Step 4: Skapa minimal `README.md`**

```markdown
# Devroom

Distributed chat with @-mentionable AI mentors built on a microservice architecture.

See [design spec](docs/superpowers/specs/2026-05-10-devroom-design.md).

## Quick start

```bash
docker compose -f docker-compose.dev.yml up -d   # Postgres + RabbitMQ only
mvn verify                                        # build + test all modules
```

Status: under utveckling. Full body README skrivs i plan 10.
```

- [ ] **Step 5: Verifiera att Maven kan parsa parent POM**

Run: `mvn validate`
Expected: BUILD SUCCESS, "Reactor Summary" listar `devroom-parent` och `auth-starter` (sub-module finns inte än men det räcker att Maven läser POM utan fel — den klagar dock på saknad sub-modul, vilket är förväntat för det här steget. Skip till Task 2.)

Faktiskt — vi ska skippa step 5 och göra det efter Task 2 när auth-starter finns. Markera `mvn validate` som verifikation efter Task 2 istället.

- [ ] **Step 6: Initial commit**

```bash
cd ~/IdeaProjects/devroom
git add pom.xml .gitignore .editorconfig README.md
git commit -m "chore: initialize Maven multi-module skeleton

Parent POM with Spring Boot 4 BOM, Java 21, dependency management for
JJWT, Testcontainers, gRPC. Module list will grow as services are added."
```

---

## Task 2: Skapa `auth-starter` Maven-modul (tom skelett)

**Files:**
- Create: `modules/auth-starter/pom.xml`
- Create: `modules/auth-starter/src/main/java/com/devroom/auth/.gitkeep`
- Create: `modules/auth-starter/src/test/java/com/devroom/auth/.gitkeep`

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

    <artifactId>auth-starter</artifactId>
    <name>Devroom Auth Starter</name>
    <description>Shared JWT issuance and validation library for all services</description>

    <dependencies>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>${jjwt.version}</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>${jjwt.version}</version>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 2: Skapa tomma directory-markörer**

```bash
mkdir -p modules/auth-starter/src/main/java/com/devroom/auth
mkdir -p modules/auth-starter/src/test/java/com/devroom/auth
touch modules/auth-starter/src/main/java/com/devroom/auth/.gitkeep
touch modules/auth-starter/src/test/java/com/devroom/auth/.gitkeep
```

- [ ] **Step 3: Verifiera bygge**

Run: `mvn validate`
Expected: BUILD SUCCESS, både `devroom-parent` och `auth-starter` listas i Reactor Summary.

Run: `mvn compile`
Expected: BUILD SUCCESS, "Nothing to compile" eller motsvarande för auth-starter.

- [ ] **Step 4: Commit**

```bash
git add modules/auth-starter/
git commit -m "feat(auth-starter): scaffold module with JJWT dependencies"
```

---

## Task 3: Implementera `JwtClaims` värdeklass

**Files:**
- Create: `modules/auth-starter/src/main/java/com/devroom/auth/JwtClaims.java`

- [ ] **Step 1: Skapa `JwtClaims` record**

```java
package com.devroom.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record JwtClaims(
        String subject,
        String issuer,
        Optional<String> teamId,
        List<String> roles,
        Instant issuedAt,
        Instant expiresAt
) {
    public static JwtClaims forUser(String userId, String teamId, Instant issuedAt, Instant expiresAt) {
        return new JwtClaims(
                userId,
                "auth-service",
                Optional.of(teamId),
                List.of(),
                issuedAt,
                expiresAt
        );
    }

    public static JwtClaims forService(String serviceName, List<String> roles, Instant issuedAt, Instant expiresAt) {
        return new JwtClaims(
                serviceName,
                "auth-service",
                Optional.empty(),
                List.copyOf(roles),
                issuedAt,
                expiresAt
        );
    }

    public boolean isService() {
        return roles.contains("system");
    }
}
```

- [ ] **Step 2: Kompilera**

Run: `mvn -pl modules/auth-starter compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add modules/auth-starter/src/main/java/com/devroom/auth/JwtClaims.java
git commit -m "feat(auth-starter): add JwtClaims value object"
```

---

## Task 4: Implementera `KeyLoader` för RSA-nycklar

**Files:**
- Create: `modules/auth-starter/src/main/java/com/devroom/auth/KeyLoader.java`
- Create: `modules/auth-starter/src/test/java/com/devroom/auth/KeyLoaderTest.java`

- [ ] **Step 1: Skriv det failande testet**

```java
package com.devroom.auth;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class KeyLoaderTest {

    @Test
    void loadsPrivateKeyFromPemFile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        KeyPair pair = generateKeyPair();
        Path privateKeyPath = tempDir.resolve("private.pem");
        Files.writeString(privateKeyPath, toPem("PRIVATE KEY", pair.getPrivate().getEncoded()));

        PrivateKey loaded = KeyLoader.loadPrivateKey(privateKeyPath);

        assertNotNull(loaded);
        assertEquals("RSA", loaded.getAlgorithm());
    }

    @Test
    void loadsPublicKeyFromPemFile(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        KeyPair pair = generateKeyPair();
        Path publicKeyPath = tempDir.resolve("public.pem");
        Files.writeString(publicKeyPath, toPem("PUBLIC KEY", pair.getPublic().getEncoded()));

        PublicKey loaded = KeyLoader.loadPublicKey(publicKeyPath);

        assertNotNull(loaded);
        assertEquals("RSA", loaded.getAlgorithm());
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String toPem(String type, byte[] keyBytes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyBytes);
        return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
    }
}
```

- [ ] **Step 2: Kör testet — ska faila med "KeyLoader not found"**

Run: `mvn -pl modules/auth-starter test`
Expected: COMPILATION FAILURE — `KeyLoader` saknas.

- [ ] **Step 3: Implementera `KeyLoader`**

```java
package com.devroom.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class KeyLoader {

    private KeyLoader() {}

    public static PrivateKey loadPrivateKey(Path path) {
        byte[] decoded = decodePem(path, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse private key from " + path, e);
        }
    }

    public static PublicKey loadPublicKey(Path path) {
        byte[] decoded = decodePem(path, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse public key from " + path, e);
        }
    }

    private static byte[] decodePem(Path path, String type) {
        try {
            String pem = Files.readString(path);
            String body = pem
                    .replace("-----BEGIN " + type + "-----", "")
                    .replace("-----END " + type + "-----", "")
                    .replaceAll("\\s+", "");
            return Base64.getDecoder().decode(body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
```

- [ ] **Step 4: Kör testet — ska passa**

Run: `mvn -pl modules/auth-starter test`
Expected: BUILD SUCCESS, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add modules/auth-starter/src/main/java/com/devroom/auth/KeyLoader.java \
        modules/auth-starter/src/test/java/com/devroom/auth/KeyLoaderTest.java
git commit -m "feat(auth-starter): add KeyLoader for RSA PEM files"
```

---

## Task 5: Implementera `JwtIssuer`

**Files:**
- Create: `modules/auth-starter/src/main/java/com/devroom/auth/JwtIssuer.java`
- Create: `modules/auth-starter/src/test/java/com/devroom/auth/JwtIssuerTest.java`

- [ ] **Step 1: Skriv det failande testet**

```java
package com.devroom.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JwtIssuerTest {

    private KeyPair keyPair;
    private JwtIssuer issuer;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        issuer = new JwtIssuer(keyPair.getPrivate());
    }

    @Test
    void issuesUserTokenWithRequiredClaims() {
        Instant now = Instant.parse("2026-05-10T12:00:00Z");
        JwtClaims claims = JwtClaims.forUser("user-123", "team-abc", now, now.plus(Duration.ofHours(1)));

        String token = issuer.issue(claims);

        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3, "JWT should have header.payload.signature");

        var parsed = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("user-123", parsed.getSubject());
        assertEquals("auth-service", parsed.getIssuer());
        assertEquals("team-abc", parsed.get("team_id", String.class));
    }

    @Test
    void issuesServiceTokenWithRoles() {
        Instant now = Instant.parse("2026-05-10T12:00:00Z");
        JwtClaims claims = JwtClaims.forService("bot-service", List.of("system"), now, now.plus(Duration.ofDays(365)));

        String token = issuer.issue(claims);

        var parsed = Jwts.parser()
                .verifyWith(keyPair.getPublic())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals("bot-service", parsed.getSubject());
        assertNull(parsed.get("team_id"));
        assertEquals(List.of("system"), parsed.get("roles", List.class));
    }
}
```

- [ ] **Step 2: Kör testet — ska faila**

Run: `mvn -pl modules/auth-starter test`
Expected: COMPILATION FAILURE — `JwtIssuer` saknas.

- [ ] **Step 3: Implementera `JwtIssuer`**

```java
package com.devroom.auth;

import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public final class JwtIssuer {

    private final PrivateKey privateKey;

    public JwtIssuer(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public String issue(JwtClaims claims) {
        Map<String, Object> extra = new HashMap<>();
        claims.teamId().ifPresent(t -> extra.put("team_id", t));
        if (!claims.roles().isEmpty()) {
            extra.put("roles", claims.roles());
        }

        return Jwts.builder()
                .issuer(claims.issuer())
                .subject(claims.subject())
                .issuedAt(Date.from(claims.issuedAt()))
                .expiration(Date.from(claims.expiresAt()))
                .claims(extra)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
```

- [ ] **Step 4: Kör testet — ska passa**

Run: `mvn -pl modules/auth-starter test`
Expected: BUILD SUCCESS, 4 tests passed (2 KeyLoader + 2 JwtIssuer).

- [ ] **Step 5: Commit**

```bash
git add modules/auth-starter/src/main/java/com/devroom/auth/JwtIssuer.java \
        modules/auth-starter/src/test/java/com/devroom/auth/JwtIssuerTest.java
git commit -m "feat(auth-starter): add JwtIssuer for RS256 user and service tokens"
```

---

## Task 6: Implementera `JwtValidator`

**Files:**
- Create: `modules/auth-starter/src/main/java/com/devroom/auth/JwtValidator.java`
- Create: `modules/auth-starter/src/test/java/com/devroom/auth/JwtValidatorTest.java`

- [ ] **Step 1: Skriv det failande testet**

```java
package com.devroom.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JwtValidatorTest {

    private KeyPair keyPair;
    private JwtIssuer issuer;
    private JwtValidator validator;

    @BeforeEach
    void setup() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        issuer = new JwtIssuer(keyPair.getPrivate());
        validator = new JwtValidator(keyPair.getPublic(), "auth-service");
    }

    @Test
    void validatesAndExtractsUserClaims() {
        Instant now = Instant.now();
        JwtClaims original = JwtClaims.forUser("user-1", "team-1", now, now.plus(Duration.ofHours(1)));
        String token = issuer.issue(original);

        JwtClaims parsed = validator.validate(token);

        assertEquals("user-1", parsed.subject());
        assertEquals(Optional.of("team-1"), parsed.teamId());
        assertFalse(parsed.isService());
    }

    @Test
    void validatesAndExtractsServiceClaims() {
        Instant now = Instant.now();
        JwtClaims original = JwtClaims.forService("bot-service", List.of("system"), now, now.plus(Duration.ofDays(365)));
        String token = issuer.issue(original);

        JwtClaims parsed = validator.validate(token);

        assertEquals("bot-service", parsed.subject());
        assertTrue(parsed.isService());
    }

    @Test
    void rejectsExpiredToken() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        JwtClaims expired = JwtClaims.forUser("user-1", "team-1", past, past.plus(Duration.ofHours(1)));
        String token = issuer.issue(expired);

        assertThrows(InvalidJwtException.class, () -> validator.validate(token));
    }

    @Test
    void rejectsWrongIssuer() {
        JwtValidator wrongIssuerValidator = new JwtValidator(keyPair.getPublic(), "different-service");
        Instant now = Instant.now();
        String token = issuer.issue(JwtClaims.forUser("user-1", "team-1", now, now.plus(Duration.ofHours(1))));

        assertThrows(InvalidJwtException.class, () -> wrongIssuerValidator.validate(token));
    }

    @Test
    void rejectsTokenSignedByDifferentKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair otherKeyPair = gen.generateKeyPair();
        JwtIssuer maliciousIssuer = new JwtIssuer(otherKeyPair.getPrivate());

        Instant now = Instant.now();
        String token = maliciousIssuer.issue(JwtClaims.forUser("user-1", "team-1", now, now.plus(Duration.ofHours(1))));

        assertThrows(InvalidJwtException.class, () -> validator.validate(token));
    }
}
```

- [ ] **Step 2: Kör testet — ska faila**

Run: `mvn -pl modules/auth-starter test`
Expected: COMPILATION FAILURE — `JwtValidator` och `InvalidJwtException` saknas.

- [ ] **Step 3: Implementera `InvalidJwtException`**

```java
// modules/auth-starter/src/main/java/com/devroom/auth/InvalidJwtException.java
package com.devroom.auth;

public class InvalidJwtException extends RuntimeException {
    public InvalidJwtException(String message, Throwable cause) {
        super(message, cause);
    }

    public InvalidJwtException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Implementera `JwtValidator`**

```java
package com.devroom.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JwtValidator {

    private final PublicKey publicKey;
    private final String expectedIssuer;

    public JwtValidator(PublicKey publicKey, String expectedIssuer) {
        this.publicKey = publicKey;
        this.expectedIssuer = expectedIssuer;
    }

    public JwtClaims validate(String token) {
        try {
            Claims raw = Jwts.parser()
                    .verifyWith(publicKey)
                    .requireIssuer(expectedIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String teamIdClaim = raw.get("team_id", String.class);
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) raw.getOrDefault("roles", List.of());

            return new JwtClaims(
                    raw.getSubject(),
                    raw.getIssuer(),
                    Optional.ofNullable(teamIdClaim),
                    roles == null ? List.of() : List.copyOf(roles),
                    raw.getIssuedAt().toInstant(),
                    raw.getExpiration().toInstant()
            );
        } catch (Exception e) {
            throw new InvalidJwtException("Invalid JWT: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: Kör testet — ska passa**

Run: `mvn -pl modules/auth-starter test`
Expected: BUILD SUCCESS, 9 tests passed.

- [ ] **Step 6: Commit**

```bash
git add modules/auth-starter/src/main/java/com/devroom/auth/JwtValidator.java \
        modules/auth-starter/src/main/java/com/devroom/auth/InvalidJwtException.java \
        modules/auth-starter/src/test/java/com/devroom/auth/JwtValidatorTest.java
git commit -m "feat(auth-starter): add JwtValidator with signature, exp, and issuer checks"
```

---

## Task 7: Skapa proto-fil för User Service gRPC

**Files:**
- Create: `proto/user.proto`

- [ ] **Step 1: Skriv `user.proto`**

```protobuf
syntax = "proto3";

package devroom.user.v1;

option java_multiple_files = true;
option java_package = "com.devroom.user.grpc";
option java_outer_classname = "UserProto";

service UserGrpcService {
  rpc GetUser(GetUserRequest) returns (User);
  rpc ResolveMentions(ResolveMentionsRequest) returns (ResolveMentionsResponse);
}

message GetUserRequest {
  string user_id = 1;
}

message ResolveMentionsRequest {
  string team_id = 1;
  repeated string display_names = 2;
}

message ResolveMentionsResponse {
  repeated User users = 1;
}

message User {
  string user_id = 1;
  string display_name = 2;
  string team_id = 3;
  bool is_system = 4;
  string mentor_personality = 5;  // empty for non-system users
}
```

- [ ] **Step 2: Verifiera att filen är giltig protobuf**

Run: `protoc --proto_path=proto --java_out=/tmp proto/user.proto && rm -rf /tmp/com`
Expected: ingen output, exit code 0. (Om protoc inte är installerat: `brew install protobuf`. Detta är bara en sanity-check; faktisk gen sker via maven-pluginen i konsumerande modul.)

- [ ] **Step 3: Commit**

```bash
git add proto/user.proto
git commit -m "feat(proto): add User gRPC contract with GetUser and ResolveMentions"
```

---

## Task 8: Skapa docker-compose för lokal infrastruktur

**Files:**
- Create: `docker-compose.dev.yml`
- Create: `infra/postgres-init/auth-db-init.sql`
- Create: `infra/postgres-init/user-db-init.sql`
- Create: `infra/postgres-init/message-db-init.sql`

- [ ] **Step 1: Skapa init-skript för Postgres**

```sql
-- infra/postgres-init/auth-db-init.sql
CREATE DATABASE authdb;
\connect authdb
CREATE USER dbuser WITH PASSWORD 'dbpass';
GRANT ALL PRIVILEGES ON DATABASE authdb TO dbuser;
GRANT ALL ON SCHEMA public TO dbuser;
```

```sql
-- infra/postgres-init/user-db-init.sql
CREATE DATABASE userdb;
\connect userdb
CREATE USER dbuser WITH PASSWORD 'dbpass';
GRANT ALL PRIVILEGES ON DATABASE userdb TO dbuser;
GRANT ALL ON SCHEMA public TO dbuser;
```

```sql
-- infra/postgres-init/message-db-init.sql
CREATE DATABASE messagedb;
\connect messagedb
CREATE USER dbuser WITH PASSWORD 'dbpass';
GRANT ALL PRIVILEGES ON DATABASE messagedb TO dbuser;
GRANT ALL ON SCHEMA public TO dbuser;
```

OBS: Tre separata Postgres-containrar är enklare än multi-DB i en. Vi kör tre containrar nedan.

- [ ] **Step 2: Skapa `docker-compose.dev.yml`**

```yaml
services:
  auth-db:
    image: postgres:16-alpine
    container_name: devroom-auth-db
    environment:
      POSTGRES_DB: authdb
      POSTGRES_USER: dbuser
      POSTGRES_PASSWORD: dbpass
    ports:
      - "5432:5432"
    volumes:
      - auth-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dbuser -d authdb"]
      interval: 5s
      timeout: 3s
      retries: 5

  user-db:
    image: postgres:16-alpine
    container_name: devroom-user-db
    environment:
      POSTGRES_DB: userdb
      POSTGRES_USER: dbuser
      POSTGRES_PASSWORD: dbpass
    ports:
      - "5433:5432"
    volumes:
      - user-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dbuser -d userdb"]
      interval: 5s
      timeout: 3s
      retries: 5

  message-db:
    image: postgres:16-alpine
    container_name: devroom-message-db
    environment:
      POSTGRES_DB: messagedb
      POSTGRES_USER: dbuser
      POSTGRES_PASSWORD: dbpass
    ports:
      - "5434:5432"
    volumes:
      - message-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dbuser -d messagedb"]
      interval: 5s
      timeout: 3s
      retries: 5

  rabbitmq:
    image: rabbitmq:4-management-alpine
    container_name: devroom-rabbitmq
    environment:
      RABBITMQ_DEFAULT_USER: devroom
      RABBITMQ_DEFAULT_PASS: devroom
    ports:
      - "5672:5672"     # AMQP
      - "15672:15672"   # management UI
    volumes:
      - rabbitmq-data:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  auth-db-data:
  user-db-data:
  message-db-data:
  rabbitmq-data:
```

OBS: Vi tog bort init-script-mounten — postgres-imagen kör automatiskt `POSTGRES_DB`-init via env-vars, så init-skripten i Step 1 är överflödiga. Ta bort filerna:

```bash
rm -rf infra/postgres-init/
```

- [ ] **Step 3: Starta infra och verifiera**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d
sleep 10
docker compose -f docker-compose.dev.yml ps
```

Expected: alla 4 containrar med status `healthy` (eller `running` om healthcheck inte körts än).

Run: `docker exec devroom-auth-db psql -U dbuser -d authdb -c "SELECT 1;"`
Expected: `?column?` med värde `1`, ingen error.

Run: `curl -s http://localhost:15672 | head -5`
Expected: HTML-svar (RabbitMQ management UI).

- [ ] **Step 4: Stäng ner**

Run: `docker compose -f docker-compose.dev.yml down`
Expected: containrar stoppade.

- [ ] **Step 5: Skapa platshållar `docker-compose.yml`**

För full stack skapas detta successivt när services finns. Just nu pekar `docker-compose.yml` till `docker-compose.dev.yml`-konfig + en notering om att services kommer:

```yaml
# docker-compose.yml
# Full stack compose. Services tilläggs i plan 2-7.
# För bara infra: docker compose -f docker-compose.dev.yml up

include:
  - docker-compose.dev.yml
```

- [ ] **Step 6: Commit**

```bash
git add docker-compose.dev.yml docker-compose.yml
git commit -m "feat(infra): add docker-compose for Postgres (3 instances) + RabbitMQ"
```

---

## Task 9: Skapa GitHub Actions CI

**Files:**
- Create: `.github/workflows/ci.yml`

- [ ] **Step 1: Skriv CI-workflow**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven

      - name: Build and test
        run: mvn -B verify

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/target/surefire-reports/*.xml'
```

- [ ] **Step 2: Verifiera lokalt att `mvn verify` fungerar**

Run: `mvn -B verify`
Expected: BUILD SUCCESS, alla 9 tester i auth-starter passar.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions workflow for build + test"
```

---

## Task 10: Skriv ADR-0001 (Microservice decomposition)

**Files:**
- Create: `docs/adr/0001-microservice-decomposition.md`

- [ ] **Step 1: Skapa ADR-mappen och skriv ADR-0001**

```markdown
# ADR-0001: Microservice Decomposition

**Status:** Accepted
**Date:** 2026-05-10
**Context:** Devroom — distribuerat chat-system med AI-mentorer på mikroservicearkitektur

## Context

Devroom kunde implementeras som en enda Spring Boot-applikation. Vi vill dock demonstrera microservice-mönster och bygga en lärbar, professionellt strukturerad kodbas. Vi behövde besluta hur systemet skulle delas upp.

## Decision

Vi delar upp systemet i fem backend-tjänster baserat på bounded contexts:

1. **BFF** — entry point, JWT-validering, propagering
2. **Auth Service** — credentials, JWT-utfärdande
3. **User Service** — profiler, teams, gRPC-uppslag
4. **Message Service** — kanaler, meddelanden, event-publishing
5. **Bot Service** — integrationslager runt befintlig Nordic Dev Mentor

## Considered alternatives

**Alt A: Monolit (en Spring Boot-app).** Avvisad — uppfyller inte kursens microservice-krav.

**Alt B: Tre tjänster (Auth, User, Message slås ihop).** Avvisad — credential-data och profil-data har olika känslighet och bör ägas av olika kontexter.

**Alt C: Sex tjänster (separat Notification Service).** Avvisad — för stort scope för 140h budget; notifikationer är future work.

## Consequences

**Positiva:**
- Tydliga bounded contexts (Auth ≠ User ≠ Message)
- Bot Service kan utvecklas oberoende av Message Service
- Demonstrerar microservice-gränssnitt (REST + gRPC + MQ) i ett system

**Negativa:**
- Distribuerad signup-transaktion — löses med outbox-pattern (ADR-0002)
- Inga foreign keys över databasgränser (ADR-0005)
- Fler containrar att starta lokalt — mitigeras av docker-compose

## References

- Spec: `docs/superpowers/specs/2026-05-10-devroom-design.md` sektion 2.1
```

- [ ] **Step 2: Commit**

```bash
mkdir -p docs/adr
# (skriv filen ovan till docs/adr/0001-microservice-decomposition.md)
git add docs/adr/0001-microservice-decomposition.md
git commit -m "docs(adr): 0001 microservice decomposition"
```

---

## Task 11: Skriv ADR-0003 (JWT defense-in-depth)

**Files:**
- Create: `docs/adr/0003-jwt-defense-in-depth.md`

- [ ] **Step 1: Skriv ADR-0003**

```markdown
# ADR-0003: JWT Defense-in-Depth (per-service-validering)

**Status:** Accepted
**Date:** 2026-05-10

## Context

JWT-baserad autentisering ska skydda alla användarinitierade flöden. Frågan: räcker det att BFF validerar JWT och vidarebefordrar identitet via header (X-User-Id), eller ska varje intern tjänst också validera samma JWT?

## Decision

Varje intern tjänst validerar JWT mot delad public key. BFF propagerar hela JWT i Authorization-header.

Bot Service har en separat service-JWT (statisk, lagrad i K8s Secret) med claims `sub: bot-service`, `roles: [system]`. Message Service kräver att denna service-JWT bara används tillsammans med ett `as_user_id`-fält som pekar på en `is_system=true` user.

JWT-bibliotek implementeras som delad Maven-modul (`auth-starter`) med `JwtIssuer` och `JwtValidator`. Använder RS256 (asymmetrisk signatur) — Auth Service har private key, alla andra services har public key.

## Considered alternatives

**Alt A: BFF-only validering + nätverksisolering.** Avvisad — om någon tjänst råkar exponeras (bug, missad NetworkPolicy) kan vem som helst posta som vem som helst. För svag säkerhetsmodell för ett system som vill vara prod-realistiskt.

**Alt B: Auth Service utfärdar service-tokens dynamiskt.** Avvisad — kräver login-cykel i Bot Service och refresh-logik (~6h extra). Marginell säkerhetsvinst.

**Alt C: HS256 (symmetrisk).** Avvisad — kräver att samma secret distribueras till alla services. RS256 låter bara Auth Service signera, vilket är bättre tillit-modell.

## Consequences

**Positiva:**
- Defense-in-depth: en exponerad service är inte automatiskt komprometterad
- `auth-starter`-modulen återanvänds i alla 5 tjänster (DRY)
- Tydlig service-identity-modell (Bot Service har sin egen identity)

**Negativa:**
- Public key måste distribueras till alla tjänster (ConfigMap i K8s)
- Service-JWT med 1 år exp — ingen rotation. Acceptabelt för demon, dokumenterat som future work.

## References

- Spec sektion 4
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0003-jwt-defense-in-depth.md
git commit -m "docs(adr): 0003 JWT defense-in-depth"
```

---

## Task 12: Plan-slut: full verifikation och slutcommit

- [ ] **Step 1: Kör hela bygget från scratch**

Run: `mvn -B clean verify`
Expected: BUILD SUCCESS, 9 tests passed.

- [ ] **Step 2: Starta och verifiera infra**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d
sleep 15
docker compose -f docker-compose.dev.yml ps
docker exec devroom-auth-db psql -U dbuser -d authdb -c "SELECT version();"
docker exec devroom-user-db psql -U dbuser -d userdb -c "SELECT version();"
docker exec devroom-message-db psql -U dbuser -d messagedb -c "SELECT version();"
curl -s -u devroom:devroom http://localhost:15672/api/overview | head
docker compose -f docker-compose.dev.yml down
```

Expected: alla kommandon ger version-info eller JSON utan fel.

- [ ] **Step 3: Verifiera commit-historiken**

Run: `git log --oneline`
Expected: ~12 commits sedan initial commit, alla logiska enheter.

- [ ] **Step 4: Slutkontroll mot Plan-1-Goal**

Checklista:
- [ ] `mvn verify` passerar (9 tester)
- [ ] `docker compose -f docker-compose.dev.yml up` startar 4 healthy containrar
- [ ] `auth-starter`-modulen har `JwtIssuer`, `JwtValidator`, `JwtClaims`, `KeyLoader`, `InvalidJwtException`
- [ ] `proto/user.proto` finns
- [ ] CI-workflow finns
- [ ] ADR-0001 + ADR-0003 skrivna
- [ ] Maven-versioner låsta i parent POM (Spring Boot 4, JJWT 0.12)

---

## Plan 1 — slut

Vid godkänd verifikation: gå vidare till plan 02 (Auth Service).
