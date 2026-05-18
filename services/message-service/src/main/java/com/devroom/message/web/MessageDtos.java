package com.devroom.message.web;

import com.devroom.message.domain.MentionInfo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageDtos {

    private MessageDtos() {}

    public record PostMessageRequest(
            UUID channelId,
            String body,
            UUID parentMessageId,
            UUID asUserId
    ) {}

    public record MessageResponse(
            UUID id,
            UUID channelId,
            UUID senderId,
            String body,
            UUID parentMessageId,
            List<MentionInfo> mentions,
            Instant createdAt
    ) {}
}
