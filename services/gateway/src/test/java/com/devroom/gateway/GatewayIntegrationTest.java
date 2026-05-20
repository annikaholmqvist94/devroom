package com.devroom.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Integration-test för Gateway. Mockar Auth Service:s OIDC discovery via
 * WireMock så Spring Security kan boot:a sin OAuth2-client utan att en
 * faktisk Auth Service är igång.
 *
 * Test-fall (alla utan inloggad session):
 *   1. Skyddad route triggar redirect till Authorization Code-flödet
 *   2. /api/me returnerar 401 istället för redirect
 *   3. /actuator/health är publik (för k8s-probes)
 *   4. CORS pre-flight ger förväntade headers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GatewayIntegrationTest {

    private static final WireMockServer authMock = new WireMockServer(0);

    // Statisk init körs FÖRE @DynamicPropertySource. Krävs eftersom Spring
    // Security kontaktar issuer-uri vid bean-skapande.
    static {
        authMock.start();
        String issuer = "http://localhost:" + authMock.port();
        authMock.stubFor(get("/.well-known/openid-configuration")
                .willReturn(okJson("""
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/oauth2/authorize",
                          "token_endpoint": "%s/oauth2/token",
                          "jwks_uri": "%s/.well-known/jwks.json",
                          "response_types_supported": ["code"],
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"],
                          "scopes_supported": ["openid", "profile"]
                        }
                        """.formatted(issuer, issuer, issuer, issuer))));
        authMock.stubFor(get("/.well-known/jwks.json")
                .willReturn(okJson("{\"keys\": []}")));
    }

    @DynamicPropertySource
    static void overrideOAuth2Props(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.auth-service.issuer-uri",
                () -> "http://localhost:" + authMock.port());
        registry.add("GATEWAY_CLIENT_SECRET", () -> "test-secret");
    }

    @AfterAll
    static void stopMocks() {
        authMock.stop();
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    /**
     * Spring Security:s OAuth2 entry point svarar med 302 till
     * /oauth2/authorization/auth-service när en oautentiserad request
     * når en skyddad route. Vi använder Java:s HttpClient med
     * Redirect.NEVER så vi kan inspektera 302-svaret — TestRestTemplate
     * följer redirects by default.
     */
    @Test
    void protectedRouteRedirectsToOAuth2Authorization() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/messages"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("location"))
                .isPresent()
                .get()
                .asString()
                .contains("/oauth2/authorization/auth-service");
    }

    @Test
    void apiMeReturns401WhenNotAuthenticated() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/me", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void actuatorHealthIsPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void corsPreflightAllowsFrontendOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:3000");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/messages",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }
}
