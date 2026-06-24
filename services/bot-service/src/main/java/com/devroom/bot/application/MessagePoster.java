package com.devroom.bot.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import static org.springframework.security.oauth2.client.web.client.RequestAttributeClientRegistrationIdResolver.clientRegistrationId;
import static org.springframework.security.oauth2.client.web.client.RequestAttributePrincipalResolver.principal;

/**
 * Postar bot-svar till Message Service med automatisk OAuth2-token via interceptor:n
 * konfigurerad i MessageServiceClientConfig.
 *
 * Vid varje request bindar vi anropet till client-registration "auth-service" och
 * principal "bot-service" via request-attribut — det säger interceptor:n vilken token
 * den ska hämta. För Client Credentials är "principal" bara en cache-nyckel
 * (det finns ingen riktig användare bakom — clienten ÄR principalen).
 *
 * Idempotency-Key skickas alltid även om Message Service inte läser den än. När den
 * eventuellt implementerar header-dedup räcker det att Bot Service redan satt nyckeln
 * deterministiskt per originalMessageId — duplicates fångas automatiskt.
 */
@Component
public class MessagePoster {

    private static final Logger log = LoggerFactory.getLogger(MessagePoster.class);
    private static final String CLIENT_REGISTRATION_ID = "auth-service";
    private static final String CLIENT_PRINCIPAL = "bot-service";

    private final RestClient client;
    private final Counter botReplies;

    public MessagePoster(RestClient messageServiceRestClient, MeterRegistry meterRegistry) {
        this.client = messageServiceRestClient;
        this.botReplies = Counter.builder("bot.replies")
                .description("Total replies posted by the bot")
                .register(meterRegistry);
    }

    public void post(String channelId, String asUserId, String body,
                     String parentMessageId, String idempotencyKey) {
        PostMessageBody payload = new PostMessageBody(channelId, body, parentMessageId, asUserId);

        client.post()
                .uri("/messages")
                .attributes(clientRegistrationId(CLIENT_REGISTRATION_ID))
                .attributes(principal(CLIENT_PRINCIPAL))
                .header("Idempotency-Key", "bot-reply-" + idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

        log.info("Posted bot reply as {} to channel {}", asUserId, channelId);
        botReplies.increment();
    }

    record PostMessageBody(String channelId, String body, String parentMessageId, String asUserId) {}
}
