package com.devroom.message.application;

import com.devroom.message.domain.ChannelRepository;
import com.devroom.message.domain.MentionInfo;
import com.devroom.message.domain.Message;
import com.devroom.message.domain.MessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PostMessageService {

    private final ChannelRepository channelRepo;
    private final MessageRepository messageRepo;
    private final MentionParser parser;
    private final MentionResolver resolver;
    private final MessageEventPublisher publisher;
    private final UUID demoTeamId;

    public PostMessageService(ChannelRepository channelRepo,
                               MessageRepository messageRepo,
                               MentionParser parser,
                               MentionResolver resolver,
                               MessageEventPublisher publisher,
                               @Value("${devroom.message.demo-team-id}") String demoTeamId) {
        this.channelRepo = channelRepo;
        this.messageRepo = messageRepo;
        this.parser = parser;
        this.resolver = resolver;
        this.publisher = publisher;
        this.demoTeamId = UUID.fromString(demoTeamId);
    }

    @Transactional
    public Message post(UUID channelId, UUID senderId, String body, UUID parentMessageId) {
        if (!channelRepo.existsById(channelId)) {
            throw new ChannelNotFoundException(channelId);
        }

        UUID effectiveParent = parentMessageId;
        if (parentMessageId != null) {
            Message parent = messageRepo.findById(parentMessageId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Parent message not found: " + parentMessageId));
            if (parent.getParentMessageId() != null) {
                effectiveParent = parent.getParentMessageId();
            }
        }

        List<String> mentionNames = parser.extract(body);
        List<MentionInfo> mentions = resolver.resolve(demoTeamId, mentionNames);

        Message msg = new Message(UUID.randomUUID(), channelId, senderId, body, effectiveParent, mentions);
        messageRepo.save(msg);
        publisher.publish(msg);

        return msg;
    }
}
