package com.devroom.bot.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Huvudflödet för en inkommande message.published-event:
 *   filtrera mentions → slå upp avsändaren → fråga mentorn → posta svaret.
 *
 * Eventets struktur (publicerad av message-service MessageEventPublisher):
 *   {
 *     "event_id", "event_type", "occurred_at",
 *     "message_id", "channel_id", "team_id", "sender_id",
 *     "body", "parent_message_id",
 *     "mentions": [{"userId", "isSystem", "personality"}]
 *   }
 *
 * Bot reagerar bara på mentions där isSystem=true (vanliga user-mentions ignoreras).
 * Bot-svaret hamnar i samma tråd som mention:n (parent_message_id-koherens).
 */
@Service
public class BotReplyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BotReplyOrchestrator.class);

    private final SenderLookup senderLookup;
    private final MentorClient mentor;
    private final MessagePoster poster;

    public BotReplyOrchestrator(SenderLookup senderLookup, MentorClient mentor, MessagePoster poster) {
        this.senderLookup = senderLookup;
        this.mentor = mentor;
        this.poster = poster;
    }

    public void handle(JsonNode event) {
        JsonNode mentions = event.get("mentions");
        if (mentions == null || !mentions.isArray() || mentions.isEmpty()) {
            return;
        }

        String channelId = event.get("channel_id").asString();
        String senderId = event.get("sender_id").asString();
        String body = event.get("body").asString();
        String originalMessageId = event.get("message_id").asString();

        JsonNode parentNode = event.get("parent_message_id");
        String parentForReply = (parentNode == null || parentNode.isNull())
                ? originalMessageId
                : parentNode.asString();

        for (JsonNode m : mentions) {
            JsonNode isSystemNode = m.get("isSystem");
            if (isSystemNode == null || !isSystemNode.asBoolean()) {
                continue;
            }

            String personality = m.get("personality").asString();
            String mentorUserId = m.get("userId").asString();

            String senderName = senderLookup.displayName(senderId);
            log.debug("Bot mention for {} from {} in channel {}", personality, senderName, channelId);

            String reply = mentor.chat(personality, body);

            poster.post(channelId, mentorUserId, reply, parentForReply, originalMessageId);
        }
    }
}
