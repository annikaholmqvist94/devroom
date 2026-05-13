package com.devroom.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Delade säkerhetsbönor som inte hör hemma i någon annan config.
 *
 * Variant F (2026-05-13): RegisteredClientRepository tas inte upp här —
 * Boot auto-konfigurerar InMemoryRegisteredClientRepository från
 * spring.security.oauth2.authorizationserver.client.* properties.
 */
@Configuration
public class SecurityBeansConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // DelegatingPasswordEncoder prefixar hashen med algoritm-id (t.ex. "{bcrypt}..."),
        // så Spring Security kan verifiera mot rätt algoritm utan att låsa fast valet.
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
