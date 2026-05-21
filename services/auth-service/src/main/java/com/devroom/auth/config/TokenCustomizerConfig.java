package com.devroom.auth.config;

import com.devroom.auth.domain.DevroomUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Skriver om "sub"-claim till user_id (UUID) och lägger till "team_id" på user-tokens.
 * Spring Authorization Server sätter default sub = principal name (= email vid form-login),
 * men Message Service och nedströms-services behandlar sub som user-UUID. Utan denna
 * override failar POST /messages med "Invalid UUID string: user@example.com".
 *
 * För client_credentials-flödet (bot-service) är principal-namnet klient-ID:t — findByUsername
 * returnerar tomt Optional och inget skrivs över. Bot-service-tokens behåller sub = "bot-service"
 * och Message Service hanterar dem via scope-check ('bot:write') istället för UUID-parse.
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
                            .subject(user.getUserId().toString())
                            .claim("team_id", user.getTeamId().toString()));
        };
    }
}
