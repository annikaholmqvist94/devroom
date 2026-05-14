package com.devroom.auth.config;

import com.devroom.auth.domain.DevroomUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Lägger till "team_id"-claim på access-tokens utfärdade till user-principals.
 * Resource servers (User, Message) kan då läsa team_id direkt från token utan DB-anrop.
 *
 * För client_credentials-flödet (bot-service) är principal-namnet klient-ID:t — findByUsername
 * returnerar tomt Optional och ingen claim sätts. Bot-service tillhör inget team.
 */
@Configuration
public class TokenCustomizerConfig {

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(DevroomUserRepository repo) {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            if (context.getPrincipal() == null || context.getPrincipal().getName() == null) {
                return;
            }
            repo.findByUsername(context.getPrincipal().getName())
                    .ifPresent(user -> context.getClaims()
                            .claim("team_id", user.getTeamId().toString()));
        };
    }
}
