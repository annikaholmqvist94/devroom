# Plan 09: Cross-service integrationstester

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Stärka testtäckningen med ett par cross-service integration-tester som kör multiple Spring Boot-applikationer i samma JVM (eller via Testcontainers Compose) och verifierar end-to-end-flöden. Kompletterar de service-lokala tester som redan finns från plan 02-08.

**Architecture:** Två test-strategier:

1. **In-process multi-context-test** — startar Auth + User + Message Spring Boot-kontext i samma JVM, mot delade Testcontainers (Postgres + RabbitMQ). Snabbt, deterministic.
2. **Docker Compose-test** — startar hela `docker-compose.yml` via `org.testcontainers.containers.ComposeContainer`, kör HTTP/gRPC-anrop mot exponerade portar. Långsammare, mer realistic.

Plan-9 lägger till strategi (1) som ett gemensamt `tests/cross-service/` Maven-modul. Strategi (2) är future work.

**Tech Stack:** JUnit 5, Spring Boot Test, Testcontainers (Postgres + RabbitMQ).

**Pre-conditions:** plan 01-08 klara.

---

## File Structure

```
tests/
└── cross-service/
    ├── pom.xml
    └── src/test/java/com/devroom/tests/
        ├── SignupToProfileE2ETest.java          # signup → user.registered → profil
        ├── MentionToBotReplyE2ETest.java        # mention → MQ → bot postar svar
        └── support/
            ├── TestServiceLauncher.java
            └── TestKeyMaterial.java
```

---

## Task 1: Scaffold cross-service test-modul

- [ ] **Step 1: Lägg till modulen i parent POM**

```xml
<modules>
    ...
    <module>tests/cross-service</module>
</modules>
```

- [ ] **Step 2: Modul-POM**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.devroom</groupId>
        <artifactId>devroom-parent</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>cross-service-tests</artifactId>
    <name>Devroom Cross-Service Tests</name>

    <dependencies>
        <!-- Drar in alla services så de kan startas i tester -->
        <dependency><groupId>com.devroom</groupId><artifactId>auth-service</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
        <dependency><groupId>com.devroom</groupId><artifactId>user-service</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
        <dependency><groupId>com.devroom</groupId><artifactId>message-service</artifactId><version>0.1.0-SNAPSHOT</version></dependency>
        <dependency><groupId>com.devroom</groupId><artifactId>bot-service</artifactId><version>0.1.0-SNAPSHOT</version></dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Commit**.

---

## Task 2: Hjälpklasser för test-setup

**Files:**
- Create: `tests/cross-service/src/test/java/com/devroom/tests/support/TestKeyMaterial.java`
- Create: `tests/cross-service/src/test/java/com/devroom/tests/support/TestServiceLauncher.java`

- [ ] **Step 1: Generera test-RSA-nycklar (en gång)**

Lägg klassiska test-PEM-filer under `src/test/resources/keys/`. Återanvänd från auth-service-modulens test-keys eller generera nya:

```bash
mkdir -p tests/cross-service/src/test/resources/keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out tests/cross-service/src/test/resources/keys/private.pem
openssl rsa -in tests/cross-service/src/test/resources/keys/private.pem \
  -pubout -out tests/cross-service/src/test/resources/keys/public.pem
```

- [ ] **Step 2: TestServiceLauncher**

Hjälpare som programmatiskt startar `SpringApplication` per service med en delad Postgres-URL och RabbitMQ-URL.

```java
package com.devroom.tests.support;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

public class TestServiceLauncher {

    public static ConfigurableApplicationContext launchAuth(String dbUrl, String rabbitHost, int rabbitPort) {
        Map<String, Object> props = baseProps(dbUrl, rabbitHost, rabbitPort);
        props.put("server.port", 0);
        props.put("devroom.auth.private-key-path", "classpath:keys/private.pem");
        return new SpringApplicationBuilder(com.devroom.auth.AuthServiceApplication.class)
                .properties(props)
                .run();
    }

    public static ConfigurableApplicationContext launchUser(String dbUrl, String rabbitHost, int rabbitPort) {
        // ... liknande
    }

    private static Map<String, Object> baseProps(String dbUrl, String rabbitHost, int rabbitPort) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", dbUrl);
        props.put("spring.datasource.username", "test");
        props.put("spring.datasource.password", "test");
        props.put("spring.rabbitmq.host", rabbitHost);
        props.put("spring.rabbitmq.port", rabbitPort);
        return props;
    }
}
```

OBS: Spring Boot multi-context-tester kan kollidera kring `@Component`-scanning. Alternativt: kör `SpringApplication.run` med `--spring.config.name=auth-service` per applikation och säkerställ att de har separata classpath-rotresurser. Detta är komplext — om det blir för krångligt, byt till **strategi 2** (Docker Compose Container) i Task 5.

- [ ] **Step 3: Commit**.

---

## Task 3: SignupToProfileE2ETest

**Files:**
- Create: `tests/cross-service/src/test/java/com/devroom/tests/SignupToProfileE2ETest.java`

```java
package com.devroom.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

class SignupToProfileE2ETest {

    static PostgreSQLContainer<?> authDb;
    static PostgreSQLContainer<?> userDb;
    static RabbitMQContainer rabbit;

    static ConfigurableApplicationContext authCtx;
    static ConfigurableApplicationContext userCtx;

    @BeforeAll
    static void start() {
        authDb = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("authdb");
        userDb = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("userdb");
        rabbit = new RabbitMQContainer("rabbitmq:4-management-alpine");
        authDb.start(); userDb.start(); rabbit.start();

        authCtx = TestServiceLauncher.launchAuth(authDb.getJdbcUrl(), rabbit.getHost(), rabbit.getAmqpPort());
        userCtx = TestServiceLauncher.launchUser(userDb.getJdbcUrl(), rabbit.getHost(), rabbit.getAmqpPort());
    }

    @AfterAll
    static void stop() {
        authCtx.close();
        userCtx.close();
        rabbit.stop();
        userDb.stop();
        authDb.stop();
    }

    @Test
    void signupTriggersProfileCreation() {
        int authPort = ... // hämta från authCtx
        int userPort = ... // hämta från userCtx

        TestRestTemplate http = new TestRestTemplate();
        var resp = http.postForEntity(
                "http://localhost:" + authPort + "/auth/signup",
                java.util.Map.of("email", "e2e@test.com", "password", "password123"),
                java.util.Map.class
        );
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        String userId = (String) resp.getBody().get("userId");

        // Vänta tills profilen dyker upp i UserDB (via RabbitMQ)
        await().atMost(ofSeconds(15)).untilAsserted(() -> {
            var profile = http.getForEntity("http://localhost:" + userPort + "/users/" + userId, java.util.Map.class);
            assertThat(profile.getStatusCode().is2xxSuccessful()).isTrue();
        });
    }
}
```

OBS: detta är komplext att få att fungera p.g.a. multi-context-svårigheter. Om problem uppstår, byt till strategi 2 nedan.

Commit.

---

## Task 4: MentionToBotReplyE2ETest (mer komplex — kanske skippas)

Om Task 3 visar sig krångligt är ett *minimum* att skriva detta som en docker-compose-baserad test (Task 5).

Annars: liknande struktur men startar dessutom Message Service + Bot Service. Använd WireMock för Nordic Dev Mentor (mocka `/api/chat` att returnera "Test reply").

Commit.

---

## Task 5: Alternativ — Docker Compose Container-baserad test (om Task 3-4 är för krångliga)

```java
package com.devroom.tests;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;

class DockerComposeE2ETest {

    static ComposeContainer compose = new ComposeContainer(new File("../../docker-compose.yml"))
            .withExposedService("gateway", 8080, Wait.forHttp("/actuator/health"))
            .withExposedService("auth-service", 8081, Wait.forHttp("/actuator/health"));

    @org.junit.jupiter.api.BeforeAll
    static void up() { compose.start(); }

    @org.junit.jupiter.api.AfterAll
    static void down() { compose.stop(); }

    @Test
    void fullSignupAndMessageFlow() {
        // BFF-host:port via compose.getServiceHost("gateway", 8080)
        // ... HTTP-anrop end-to-end
    }
}
```

OBS: kräver att `docker-compose.yml` är komplett från plan 10 (services + Dockerfiles). Plan 9 kan därför vara en delvis-skiss som faktiskt fylls i efter plan 10.

**Pragmatisk justering:** kör plan 9 i en mindre form (bara hjälpklasser + Task 3-style-test där det fungerar) och flytta Compose-testet till plan 10 efter att Dockerfiles finns.

Commit.

---

## Task 6: Justera test-strategin baserat på vad som faktiskt går

- [ ] Om in-process multi-context fungerar: behåll Task 3-4 som primary
- [ ] Om det inte fungerar: bekräfta att service-lokala tester (plan 02-08) ger tillräcklig täckning, och flytta Compose-testet till efter plan 10
- [ ] Dokumentera valet i en README under `tests/cross-service/`

Commit.

---

## Task 7: Plan-slut

- [ ] `mvn -pl tests/cross-service -B verify` passerar (om in-process fungerar)
- [ ] Annars: dokumenterad förklaring av varför vi förlitar oss på service-lokala tester + manuell smoke-test som primärt acceptanskriterium
- [ ] README finns under `tests/cross-service/`

---

## Plan 9 — slut

Vid godkänd verifikation: gå vidare till plan 10 (Kubernetes).
