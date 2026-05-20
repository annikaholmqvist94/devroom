package com.devroom.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver;
import org.springframework.web.client.RestClient;

/**
 * RestClient mot Message Service med automatisk OAuth2 Client Credentials-token.
 *
 * Spring auto-konfigurerar DefaultOAuth2AuthorizedClientManager för web-flöden (kräver
 * HttpServletRequest i scope). Bot Service triggas av RabbitMQ — ingen request finns —
 * så vi exponerar en egen AuthorizedClientServiceOAuth2AuthorizedClientManager med
 * Client Credentials-provider istället.
 *
 * Token-flödet vid varje request:
 *  1. Interceptor:n läser clientRegistrationId + principal från request-attribut
 *     (satta i MessagePoster via .attributes(...))
 *  2. Manager kollar OAuth2AuthorizedClientService för cached token; om miss/utgången
 *     hämtas en ny via POST {issuer-uri}/oauth2/token med client_credentials-grant
 *  3. Authorization: Bearer <token> sätts på request
 *
 * Token cachas tills den utgår (1h enligt auth-service config).
 */
@Configuration
public class MessageServiceClientConfig {

    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clientService) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    RestClient messageServiceRestClient(
            @Value("${devroom.bot.message-service-url}") String url,
            OAuth2AuthorizedClientManager manager,
            ClientHttpRequestFactory http1ClientHttpRequestFactory) {

        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(manager);
        // PrincipalResolver hämtar principal från request-attribut (satta i MessagePoster).
        // ClientRegistrationIdResolver default:ar redan till samma attribut-baserade mönster.
        interceptor.setPrincipalResolver(new RequestAttributePrincipalResolver());

        return RestClient.builder()
                .baseUrl(url)
                .requestFactory(http1ClientHttpRequestFactory)
                .requestInterceptor(interceptor)
                .build();
    }
}
