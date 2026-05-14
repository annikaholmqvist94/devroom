package com.devroom.user;

import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.ResolveMentionsRequest;
import com.devroom.user.grpc.UserGrpcServiceGrpc;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcPort;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
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

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Lazy
        UserGrpcServiceGrpc.UserGrpcServiceBlockingStub userStub(
                GrpcChannelFactory channels, @LocalGrpcPort int port) {
            return UserGrpcServiceGrpc.newBlockingStub(channels.createChannel("0.0.0.0:" + port));
        }
    }

    @Autowired
    UserGrpcServiceGrpc.UserGrpcServiceBlockingStub stub;

    @Test
    void getUserReturnsSeededMentor() {
        var resp = stub.getUser(GetUserRequest.newBuilder()
                .setUserId("22222222-2222-2222-2222-222222222203")
                .build());
        assertThat(resp.getDisplayName()).isEqualTo("code-reviewer");
        assertThat(resp.getIsSystem()).isTrue();
        assertThat(resp.getMentorPersonality()).isEqualTo("code-reviewer");
    }

    @Test
    void resolveMentionsFindsMentorsByName() {
        var resp = stub.resolveMentions(ResolveMentionsRequest.newBuilder()
                .setTeamId("11111111-1111-1111-1111-111111111111")
                .addDisplayNames("code-reviewer")
                .addDisplayNames("rubber-duck")
                .addDisplayNames("nonexistent")
                .build());
        assertThat(resp.getUsersCount()).isEqualTo(2);
    }
}
