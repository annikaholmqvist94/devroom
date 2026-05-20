package com.devroom.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class MentorRestClientConfig {

    /**
     * RestClient mot Nordic Dev Mentor. baseUrl övergrips i prod via NORDIC_DEV_MENTOR_URL.
     * Inget auth-block — dev-mentor har ingen autentisering (känd limitation i deras README).
     */
    @Bean
    RestClient mentorRestClient(@Value("${devroom.bot.nordic-dev-mentor-url}") String url) {
        return RestClient.builder()
                .baseUrl(url)
                .build();
    }
}
