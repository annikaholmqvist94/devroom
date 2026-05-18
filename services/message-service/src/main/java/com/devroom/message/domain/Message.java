package com.devroom.message.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    private UUID id;

    @Column(name = "channel_id", nullable = false)
    private UUID channelId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(nullable = false)
    private String body;

    @Column(name = "parent_message_id")
    private UUID parentMessageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<MentionInfo> mentions;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Message() {}

    public Message(UUID id, UUID channelId, UUID senderId, String body,
                   UUID parentMessageId, List<MentionInfo> mentions) {
        this.id = id;
        this.channelId = channelId;
        this.senderId = senderId;
        this.body = body;
        this.parentMessageId = parentMessageId;
        this.mentions = mentions;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getChannelId() { return channelId; }
    public UUID getSenderId() { return senderId; }
    public String getBody() { return body; }
    public UUID getParentMessageId() { return parentMessageId; }
    public List<MentionInfo> getMentions() { return mentions; }
    public Instant getCreatedAt() { return createdAt; }
}
