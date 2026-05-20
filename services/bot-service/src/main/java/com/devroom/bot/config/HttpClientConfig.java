package com.devroom.bot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.http.HttpClient;

/**
 * RestClient kör default över HTTP/2 om servern annonserar stöd för det
 * (via JdkClientHttpRequestFactory + java.net.http.HttpClient). Det krockar med
 * WireMock i integration-test (RST_STREAM: Stream cancelled), och Tomcat-baserade
 * services i prod-clustret pratar ändå HTTP/1.1 default. Tvinga 1.1 globalt för
 * båda Bot-Service:s utgående RestClient:s.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    ClientHttpRequestFactory http1ClientHttpRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        return new JdkClientHttpRequestFactory(httpClient);
    }
}
