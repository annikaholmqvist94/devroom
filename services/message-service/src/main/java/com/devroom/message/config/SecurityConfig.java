package com.devroom.message.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import static org.springframework.security.authorization.AuthorizationManagers.anyOf;
import static org.springframework.security.oauth2.core.authorization.OAuth2AuthorizationManagers.hasScope;

/**
 * Resource Server filter chain. JWT-validation (signature, exp, iss) sker via auto-config från
 * spring.security.oauth2.resourceserver.jwt.* i application.yml — vi konfigurerar bara authz-regler.
 *
 * Scopes som accepteras på POST /messages:
 * - "profile" — utfärdat av Auth Service när en användare signupar/loggar in. Vanlig flöde.
 * - "bot:write" — service-token från Bot Service (client credentials grant) för auto-svar.
 *
 * /actuator/** lämnas oskyddat så att k8s liveness/readiness probes inte behöver hantera JWT.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/messages")
                            .access(anyOf(hasScope("profile"), hasScope("bot:write")))
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
