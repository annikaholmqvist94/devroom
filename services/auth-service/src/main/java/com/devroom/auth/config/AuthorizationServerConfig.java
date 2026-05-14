package com.devroom.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Filter chain för Spring Authorization Server-endpoints (/oauth2/*, /userinfo, /connect/*, /.well-known/*).
 *
 * Variant F: ingen RegisteredClientRepository-bean här — Boot auto-konfigurerar
 * InMemoryRegisteredClientRepository från application.yml-properties.
 *
 * SAS 7.0.5-detalj: JWKS-endpointen är på /oauth2/jwks (default), inte /.well-known/jwks.json
 * som OAuth2-konventionen säger. Resource servers ska upptäcka rätt path via discovery-dokumentets
 * jwks_uri-fält (publicerat under /.well-known/openid-configuration), så det är inget problem i
 * praktiken. Custom mapping kan göras via AuthorizationServerSettings.builder().jwkSetEndpoint(...).
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher("/.well-known/**", "/oauth2/**", "/userinfo", "/connect/**", "/login/oauth2/**")
                .with(configurer, c -> c.oidc(Customizer.withDefaults()))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/**", "/.well-known/**"))
                .exceptionHandling(exceptions ->
                        exceptions.defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                );

        return http.build();
    }
}
