package com.devroom.gateway.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class AuthApiConfig {

    /**
     * GET /api/me
     *   - 200 OK + JSON med userId, displayName, teamId om en användare
     *     är inloggad (OAuth2User i SecurityContextHolder).
     *   - 401 Unauthorized annars.
     *
     * Frontend använder denna för att avgöra inloggad-status vid app-start.
     * Endpointen ligger i allowlist:en i SecurityConfig så Spring Security
     * inte trigggar Authorization Code-redirect på en oinloggad request.
     */
    @Bean
    public RouterFunction<ServerResponse> meRoute() {
        return RouterFunctions.route()
                .GET("/api/me", request -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof OAuth2User user)) {
                        return ServerResponse.status(401).build();
                    }

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("userId", user.getAttribute("sub"));
                    body.put("displayName", user.getAttribute("email"));
                    body.put("teamId", user.getAttribute("team_id"));

                    return ServerResponse.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body);
                })
                .build();
    }
}
