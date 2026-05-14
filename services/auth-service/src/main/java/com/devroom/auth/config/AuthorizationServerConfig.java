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
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Filter chain för Spring Authorization Server-endpoints (/oauth2/authorize, /oauth2/token,
 * /oauth2/introspect, /oauth2/revoke, /userinfo, /.well-known/*).
 *
 * Variant F: ingen RegisteredClientRepository-bean här — Boot auto-konfigurerar
 * InMemoryRegisteredClientRepository från application.yml-properties.
 *
 * SAS 7.0.5 API: använder `new OAuth2AuthorizationServerConfigurer()` (public konstruktor).
 * Den statiska `authorizationServer()`-fabriken visas i nyare SAS-samples men finns inte i 7.0.5.
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpointsMatcher = configurer.getEndpointsMatcher();

        http
                // Begränsa denna chain till SAS-endpoints — andra requests fångas av Task 10:s chain.
                .securityMatcher(endpointsMatcher)
                .with(configurer, c ->
                        // OIDC: aktiverar /userinfo + /.well-known/openid-configuration.
                        c.oidc(Customizer.withDefaults())
                )
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions ->
                        // Browser-träffar (text/html) på /oauth2/authorize utan session → redirect till /login.
                        // Spring default-login-form servas där (ingen Thymeleaf-vy, Variant C).
                        exceptions.defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                        )
                );

        return http.build();
    }
}
