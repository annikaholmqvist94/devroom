package com.devroom.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain för allt utöver SAS-endpoints — signup-API, actuator, login-form.
 * AuthorizationServerConfig:s @Order(1)-chain har företräde för /oauth2/*, /.well-known/* etc.
 *
 * Variant C (Signup): /signup är JSON-API utan session-CSRF-token → CSRF disable:as för denna path.
 *                     /login servas av Spring default-form (ingen Thymeleaf).
 */
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
                .formLogin(Customizer.withDefaults())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/signup"));
        return http.build();
    }
}
