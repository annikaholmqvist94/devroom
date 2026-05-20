package com.devroom.bot.application;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Wrapper runt Nordic Dev Mentor:s REST-API.
 *
 * Anropar POST /api/v1/chat med {personality, message, sessionId?} och returnerar reply-strängen.
 * sessionId skickas som null för MVP — varje bot-svar är fristående. Att mappa
 * (channelId, mentorUserId) → sessionId för konversationskontext kan läggas till senare.
 */
@Component
public class MentorClient {

    private final RestClient client;

    public MentorClient(RestClient mentorRestClient) {
        this.client = mentorRestClient;
    }

    public String chat(String personality, String message) {
        ChatResponse response = client.post()
                .uri("/api/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatRequest(personality, message, null))
                .retrieve()
                .body(ChatResponse.class);
        return response == null ? "" : response.reply();
    }

    record ChatRequest(String personality, String message, String sessionId) {}

    record ChatResponse(String sessionId, String personality, String reply) {}
}
