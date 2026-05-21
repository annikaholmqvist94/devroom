package com.devroom.gateway.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Servlet-baserad SecurityFilterChain (se ADR-0007). Kedjan:
     *   1. CORS pre-flight släpps igenom utan auth.
     *   2. CSRF disabled — BFF-mönstret betyder att enda klienten är vår
     *      egen frontend som skickar cookies. För prod: aktivera
     *      CookieCsrfTokenRepository.
     *   3. Allowlist av publika paths.
     *   4. Resten kräver auth → oautentiserad request triggar
     *      Authorization Code-flödet via oauth2Login.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        SimpleUrlLogoutSuccessHandler logoutHandler = new SimpleUrlLogoutSuccessHandler();
        logoutHandler.setDefaultTargetUrl("http://localhost:3000");

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                        "/login/**",
                        "/oauth2/**",
                        "/actuator/**",
                        "/signup/**",
                        // /api/me släpps igenom så RouterFunction-handlern (Task 6)
                        // själv kan returnera 200 + user-info eller 401.
                        // Skyddet ligger i handlern, inte i denna kedja.
                        "/api/me"
                ).permitAll()
                .anyRequest().authenticated()
            )
            // Efter lyckad OAuth-login: redirecta tillbaka till frontend.
            // Default är '/' på Gateway vilket inte har någon route -> Whitelabel 404.
            // 'true' = alltid använd denna URL, även om det fanns en savedRequest;
            // matchar BFF-mönstret där Gateway aldrig är slutdestination.
            .oauth2Login(login -> login.defaultSuccessUrl("http://localhost:3000/", true))
            .oauth2Client(Customizer.withDefaults())
            .logout(logout -> logout.logoutSuccessHandler(logoutHandler));

        return http.build();
    }

    /**
     * CORS-config: tillåt frontend (localhost:3000) att skicka credentialed
     * requests så browser bifogar SESSION-cookien. Aktiveras genom
     * .cors(Customizer.withDefaults()) ovan — Spring Security plockar
     * automatiskt upp denna bean.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
