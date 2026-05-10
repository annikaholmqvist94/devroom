# Plan 03: User Service

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implementera User Service med teams + users-tabeller, seedade mentor-rader, gRPC-server som exponerar `GetUser` och `ResolveMentions`, samt en stub-MQ-consumer för `user.registered` (RabbitMQ-koppling görs i plan 04). Vid plan-slut kan `grpcurl` anropa både gRPC-metoderna och få korrekta svar för seedade mentorer.

**Architecture:** Spring Boot 4 + JPA + Flyway. gRPC-server via `grpc-spring-boot-starter` (Lognet) som auto-registrerar `@GrpcService`-bönor. Seed-data via Flyway-migration (idempotent INSERT med ON CONFLICT). MQ-consumer-bönan finns men `@Profile("rabbit")` så den inte aktiveras innan plan 04.

**Tech Stack:** Spring Boot 4, Spring Data JPA, Flyway, Postgres 16, gRPC 1.68 + protobuf 3.25, `net.devh:grpc-spring-boot-starter`, Testcontainers.

**Refererar spec:** sektion 3.2, 5.1.

---

## File Structure

```
services/user-service/
├── pom.xml
├── src/main/java/com/devroom/user/
│   ├── UserServiceApplication.java
│   ├── domain/
│   │   ├── Team.java
│   │   ├── User.java
│   │   ├── TeamRepository.java
│   │   └── UserRepository.java
│   ├── grpc/
│   │   └── UserGrpcServiceImpl.java
│   ├── application/
│   │   └── UserRegisteredHandler.java       # @Profile("rabbit") wired in plan 04
│   └── messaging/
│       └── UserRegisteredConsumer.java      # @Profile("rabbit") wired in plan 04
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_teams_and_users.sql
│       └── V2__seed_demo_team_and_mentors.sql
└── src/test/java/com/devroom/user/
    ├── UserGrpcServiceImplTest.java         # in-process gRPC test
    └── UserServiceIntegrationTest.java      # Testcontainers
```

---

## Task 1: Scaffold Maven-modul

**Files:**
- Create: `services/user-service/pom.xml`
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

    <artifactId>user-service</artifactId>
    <name>Devroom User Service</name>

    <properties>
        <grpc-spring-boot-starter.version>3.1.0.RELEASE</grpc-spring-boot-starter.version>
    </properties>

    <dependencies>
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
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
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

        <!-- gRPC server -->
        <dependency>
            <groupId>net.devh</groupId>
            <artifactId>grpc-server-spring-boot-starter</artifactId>
            <version>${grpc-spring-boot-starter.version}</version>
        </dependency>
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-protobuf</artifactId>
            <version>${grpc.version}</version>
        </dependency>
        <dependency>
            <groupId>io.grpc</groupId>
            <artifactId>grpc-stub</artifactId>
            <version>${grpc.version}</version>
        </dependency>
        <dependency>
            <groupId>com.google.protobuf</groupId>
            <artifactId>protobuf-java</artifactId>
            <version>${protobuf.version}</version>
        </dependency>
        <dependency>
            <groupId>jakarta.annotation</groupId>
            <artifactId>jakarta.annotation-api</artifactId>
        </dependency>

        <!-- Test -->
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
        <dependency>
            <groupId>net.devh</groupId>
            <artifactId>grpc-client-spring-boot-starter</artifactId>
            <version>${grpc-spring-boot-starter.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <extensions>
            <extension>
                <groupId>kr.motd.maven</groupId>
                <artifactId>os-maven-plugin</artifactId>
                <version>1.7.1</version>
            </extension>
        </extensions>
        <plugins>
            <plugin>
                <groupId>org.xolstice.maven.plugins</groupId>
                <artifactId>protobuf-maven-plugin</artifactId>
                <version>0.6.1</version>
                <configuration>
                    <protocArtifact>com.google.protobuf:protoc:${protobuf.version}:exe:${os.detected.classifier}</protocArtifact>
                    <pluginId>grpc-java</pluginId>
                    <pluginArtifact>io.grpc:protoc-gen-grpc-java:${grpc.version}:exe:${os.detected.classifier}</pluginArtifact>
                    <protoSourceRoot>${project.basedir}/../../proto</protoSourceRoot>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>compile</goal>
                            <goal>compile-custom</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Lägg modulen i parent POM**

Edit `pom.xml`:

```xml
<modules>
    <module>modules/auth-starter</module>
    <module>services/auth-service</module>
    <module>services/user-service</module>
</modules>
```

- [ ] **Step 3: Verifiera proto-generering**

Run: `mvn -pl services/user-service compile`
Expected: BUILD SUCCESS, generated sources i `target/generated-sources/protobuf/`.

- [ ] **Step 4: Commit**

```bash
git add services/user-service/pom.xml pom.xml
git commit -m "feat(user-service): scaffold module with JPA, Flyway, gRPC"
```

---

## Task 2: Application skeleton + config

**Files:**
- Create: `services/user-service/src/main/java/com/devroom/user/UserServiceApplication.java`
- Create: `services/user-service/src/main/resources/application.yml`

- [ ] **Step 1: Application class**

```java
package com.devroom.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class UserServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
```

- [ ] **Step 2: application.yml**

```yaml
server:
  port: 8082

grpc:
  server:
    port: 9082

spring:
  application:
    name: user-service
  datasource:
    url: jdbc:postgresql://localhost:5433/userdb
    username: dbuser
    password: dbpass
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  rabbitmq:
    host: localhost
    port: 5672
    username: devroom
    password: devroom

devroom:
  user:
    demo-team-id: 11111111-1111-1111-1111-111111111111

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: Commit**

```bash
git add services/user-service/src/
git commit -m "feat(user-service): bootstrap Spring Boot application"
```

---

## Task 3: Flyway-migration för teams + users

**Files:**
- Create: `services/user-service/src/main/resources/db/migration/V1__create_teams_and_users.sql`

- [ ] **Step 1: Skriv migrationen**

```sql
CREATE TABLE teams (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    user_id              UUID PRIMARY KEY,
    display_name         VARCHAR(100) NOT NULL,
    avatar_url           VARCHAR(500),
    team_id              UUID NOT NULL REFERENCES teams(id),
    is_system            BOOLEAN NOT NULL DEFAULT FALSE,
    mentor_personality   VARCHAR(50),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_team ON users(team_id);
CREATE INDEX idx_users_team_name ON users(team_id, display_name);
```

- [ ] **Step 2: Commit**

```bash
git add services/user-service/src/main/resources/db/migration/V1__create_teams_and_users.sql
git commit -m "feat(user-service): create teams and users tables"
```

---

## Task 4: Seed-data migration (demo-team + 4 mentor-users)

**Files:**
- Create: `services/user-service/src/main/resources/db/migration/V2__seed_demo_team_and_mentors.sql`

- [ ] **Step 1: Skriv seed-migrationen**

UUID:erna är hårdkodade så att tester och dev-data är förutsägbara.

```sql
INSERT INTO teams (id, name)
VALUES ('11111111-1111-1111-1111-111111111111', 'Devroom Demo Team')
ON CONFLICT (id) DO NOTHING;

INSERT INTO users (user_id, display_name, team_id, is_system, mentor_personality)
VALUES
    ('22222222-2222-2222-2222-222222222201', 'junior-helper',
     '11111111-1111-1111-1111-111111111111', TRUE, 'junior-helper'),
    ('22222222-2222-2222-2222-222222222202', 'senior-architect',
     '11111111-1111-1111-1111-111111111111', TRUE, 'senior-architect'),
    ('22222222-2222-2222-2222-222222222203', 'code-reviewer',
     '11111111-1111-1111-1111-111111111111', TRUE, 'code-reviewer'),
    ('22222222-2222-2222-2222-222222222204', 'rubber-duck',
     '11111111-1111-1111-1111-111111111111', TRUE, 'rubber-duck')
ON CONFLICT (user_id) DO NOTHING;
```

- [ ] **Step 2: Commit**

```bash
git add services/user-service/src/main/resources/db/migration/V2__seed_demo_team_and_mentors.sql
git commit -m "feat(user-service): seed demo team and four mentor users"
```

---

## Task 5: Entiteter och repositories

**Files:**
- Create: `services/user-service/src/main/java/com/devroom/user/domain/Team.java`
- Create: `services/user-service/src/main/java/com/devroom/user/domain/User.java`
- Create: `services/user-service/src/main/java/com/devroom/user/domain/TeamRepository.java`
- Create: `services/user-service/src/main/java/com/devroom/user/domain/UserRepository.java`

- [ ] **Step 1: Team entity**

```java
package com.devroom.user.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {
    @Id private UUID id;
    @Column(nullable = false) private String name;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected Team() {}
    public Team(UUID id, String name) {
        this.id = id; this.name = name; this.createdAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getName() { return name; }
}
```

- [ ] **Step 2: User entity**

```java
package com.devroom.user.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id @Column(name = "user_id") private UUID userId;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Column(name = "avatar_url") private String avatarUrl;
    @Column(name = "team_id", nullable = false) private UUID teamId;
    @Column(name = "is_system", nullable = false) private boolean isSystem;
    @Column(name = "mentor_personality") private String mentorPersonality;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected User() {}

    public User(UUID userId, String displayName, UUID teamId, boolean isSystem, String mentorPersonality) {
        this.userId = userId;
        this.displayName = displayName;
        this.teamId = teamId;
        this.isSystem = isSystem;
        this.mentorPersonality = mentorPersonality;
        this.createdAt = Instant.now();
    }

    public UUID getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public UUID getTeamId() { return teamId; }
    public boolean isSystem() { return isSystem; }
    public String getMentorPersonality() { return mentorPersonality; }
}
```

- [ ] **Step 3: Repositories**

```java
package com.devroom.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<Team, UUID> {}
```

```java
package com.devroom.user.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    List<User> findAllByTeamIdAndDisplayNameIn(UUID teamId, List<String> displayNames);
    boolean existsByUserId(UUID userId);
}
```

- [ ] **Step 4: Kompilera**

Run: `mvn -pl services/user-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add services/user-service/src/main/java/com/devroom/user/domain/
git commit -m "feat(user-service): add Team, User entities and repositories"
```

---

## Task 6: Implementera gRPC-service

**Files:**
- Create: `services/user-service/src/main/java/com/devroom/user/grpc/UserGrpcServiceImpl.java`

- [ ] **Step 1: Implementera servicen**

```java
package com.devroom.user.grpc;

import com.devroom.user.domain.User;
import com.devroom.user.domain.UserRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;
import java.util.UUID;

@GrpcService
public class UserGrpcServiceImpl extends UserGrpcServiceGrpc.UserGrpcServiceImplBase {

    private final UserRepository repo;

    public UserGrpcServiceImpl(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<com.devroom.user.grpc.User> responseObserver) {
        UUID id;
        try {
            id = UUID.fromString(request.getUserId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid user_id").asRuntimeException());
            return;
        }
        repo.findById(id).ifPresentOrElse(
                u -> {
                    responseObserver.onNext(toProto(u));
                    responseObserver.onCompleted();
                },
                () -> responseObserver.onError(Status.NOT_FOUND.withDescription("User not found").asRuntimeException())
        );
    }

    @Override
    public void resolveMentions(ResolveMentionsRequest request,
                                 StreamObserver<ResolveMentionsResponse> responseObserver) {
        UUID teamId;
        try {
            teamId = UUID.fromString(request.getTeamId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid team_id").asRuntimeException());
            return;
        }

        List<String> names = request.getDisplayNamesList();
        List<User> found = names.isEmpty()
                ? List.of()
                : repo.findAllByTeamIdAndDisplayNameIn(teamId, names);

        ResolveMentionsResponse.Builder resp = ResolveMentionsResponse.newBuilder();
        for (User u : found) {
            resp.addUsers(toProto(u));
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    private static com.devroom.user.grpc.User toProto(User u) {
        com.devroom.user.grpc.User.Builder b = com.devroom.user.grpc.User.newBuilder()
                .setUserId(u.getUserId().toString())
                .setDisplayName(u.getDisplayName())
                .setTeamId(u.getTeamId().toString())
                .setIsSystem(u.isSystem());
        if (u.getMentorPersonality() != null) {
            b.setMentorPersonality(u.getMentorPersonality());
        }
        return b.build();
    }
}
```

OBS: Vi importerar både domain `User` och proto `User` med fullt qualified name `com.devroom.user.grpc.User` för att undvika namnkollision.

- [ ] **Step 2: Kompilera**

Run: `mvn -pl services/user-service compile`
Expected: BUILD SUCCESS (proto-genererade klasser bör vara lediga från Task 1).

- [ ] **Step 3: Commit**

```bash
git add services/user-service/src/main/java/com/devroom/user/grpc/
git commit -m "feat(user-service): implement UserGrpcService with GetUser and ResolveMentions"
```

---

## Task 7: Stub-MQ-consumer-bönor (wired in plan 04)

**Files:**
- Create: `services/user-service/src/main/java/com/devroom/user/application/UserRegisteredHandler.java`
- Create: `services/user-service/src/main/java/com/devroom/user/messaging/UserRegisteredConsumer.java`

- [ ] **Step 1: Handler (idempotent profil-skapande)**

```java
package com.devroom.user.application;

import com.devroom.user.domain.User;
import com.devroom.user.domain.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UserRegisteredHandler {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredHandler.class);

    private final UserRepository repo;

    public UserRegisteredHandler(UserRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void handle(UUID userId, String email, UUID teamId) {
        if (repo.existsByUserId(userId)) {
            log.info("User {} already exists, skipping (idempotency)", userId);
            return;
        }
        // display_name initialt = email — vi kan låta user uppdatera senare
        User user = new User(userId, email, teamId, false, null);
        repo.save(user);
        log.info("Created profile for user {}", userId);
    }
}
```

- [ ] **Step 2: Consumer (profile=rabbit, blir aktiv i plan 04)**

```java
package com.devroom.user.messaging;

import com.devroom.user.application.UserRegisteredHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("rabbit")  // disabled tills plan 04
public class UserRegisteredConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserRegisteredConsumer.class);

    private final UserRegisteredHandler handler;
    private final ObjectMapper mapper;

    public UserRegisteredConsumer(UserRegisteredHandler handler, ObjectMapper mapper) {
        this.handler = handler;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "user-service.user-registered")
    public void onMessage(String json) throws Exception {
        log.info("Received user.registered: {}", json);
        JsonNode node = mapper.readTree(json);
        UUID userId = UUID.fromString(node.get("user_id").asText());
        String email = node.get("email").asText();
        UUID teamId = UUID.fromString(node.get("team_id").asText());
        handler.handle(userId, email, teamId);
    }
}
```

- [ ] **Step 3: Kompilera**

Run: `mvn -pl services/user-service compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/user-service/src/main/java/com/devroom/user/application/ \
        services/user-service/src/main/java/com/devroom/user/messaging/
git commit -m "feat(user-service): add user.registered consumer (disabled, wired in plan 04)"
```

---

## Task 8: Testcontainers integration test

**Files:**
- Create: `services/user-service/src/test/resources/application-test.yml`
- Create: `services/user-service/src/test/java/com/devroom/user/UserServiceIntegrationTest.java`

- [ ] **Step 1: Test-config**

```yaml
# application-test.yml
spring:
  rabbitmq:
    listener:
      simple:
        auto-startup: false  # disable RabbitMQ-listeners under test

devroom:
  user:
    demo-team-id: 11111111-1111-1111-1111-111111111111

grpc:
  server:
    port: 0  # random port
```

- [ ] **Step 2: Integration test (verifierar gRPC-endpoints + seed-data)**

```java
package com.devroom.user;

import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.ResolveMentionsRequest;
import com.devroom.user.grpc.ResolveMentionsResponse;
import com.devroom.user.grpc.UserGrpcServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class UserServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("userdb")
            .withUsername("dbuser")
            .withPassword("dbpass");

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Value("${grpc.server.port}")
    int grpcPort;

    private ManagedChannel channel;
    private UserGrpcServiceGrpc.UserGrpcServiceBlockingStub stub;

    void initStub() {
        if (stub == null) {
            channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
            stub = UserGrpcServiceGrpc.newBlockingStub(channel);
        }
    }

    @Test
    void getUserReturnsSeededMentor() {
        initStub();
        var resp = stub.getUser(GetUserRequest.newBuilder()
                .setUserId("22222222-2222-2222-2222-222222222203")
                .build());
        assertThat(resp.getDisplayName()).isEqualTo("code-reviewer");
        assertThat(resp.getIsSystem()).isTrue();
        assertThat(resp.getMentorPersonality()).isEqualTo("code-reviewer");
    }

    @Test
    void resolveMentionsFindsMentorsByName() {
        initStub();
        var resp = stub.resolveMentions(ResolveMentionsRequest.newBuilder()
                .setTeamId("11111111-1111-1111-1111-111111111111")
                .addDisplayNames("code-reviewer")
                .addDisplayNames("rubber-duck")
                .addDisplayNames("nonexistent")
                .build());
        assertThat(resp.getUsersCount()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Kör testet**

Run: `mvn -pl services/user-service verify`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add services/user-service/src/test/
git commit -m "test(user-service): Testcontainers integration test for gRPC + seed data"
```

---

## Task 9: Plan-slut: full verifikation

- [ ] **Step 1: `mvn -B clean verify`**

Expected: BUILD SUCCESS, alla moduler bygger.

- [ ] **Step 2: Manuell smoke-test**

Run:
```bash
docker compose -f docker-compose.dev.yml up -d user-db
sleep 5
mvn -pl services/user-service spring-boot:run &
APP_PID=$!
sleep 15

# gRPC-anrop med grpcurl (brew install grpcurl)
grpcurl -plaintext -import-path proto -proto user.proto \
  -d '{"user_id":"22222222-2222-2222-2222-222222222203"}' \
  localhost:9082 devroom.user.v1.UserGrpcService/GetUser

grpcurl -plaintext -import-path proto -proto user.proto \
  -d '{"team_id":"11111111-1111-1111-1111-111111111111","display_names":["code-reviewer"]}' \
  localhost:9082 devroom.user.v1.UserGrpcService/ResolveMentions

kill $APP_PID
docker compose -f docker-compose.dev.yml down
```

Expected: båda anropen returnerar JSON-data med mentor-info.

---

## Plan 3 — slut

Vid godkänd verifikation: gå vidare till plan 04 (RabbitMQ end-to-end).
