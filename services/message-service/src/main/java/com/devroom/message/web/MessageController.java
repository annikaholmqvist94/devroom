package com.devroom.message.web;

import com.devroom.message.application.PostMessageService;
import com.devroom.message.application.ServiceTokenSenderResolver;
import com.devroom.message.domain.Message;
import com.devroom.message.domain.MessageRepository;
import com.devroom.message.web.MessageDtos.MessageResponse;
import com.devroom.message.web.MessageDtos.PostMessageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/messages")
public class MessageController {

    private final PostMessageService postService;
    private final MessageRepository repo;
    private final ServiceTokenSenderResolver senderResolver;

    public MessageController(PostMessageService postService,
                              MessageRepository repo,
                              ServiceTokenSenderResolver senderResolver) {
        this.postService = postService;
        this.repo = repo;
        this.senderResolver = senderResolver;
    }

    @PostMapping
    public ResponseEntity<MessageResponse> post(@RequestBody PostMessageRequest req,
                                                 Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        UUID senderId = resolveSender(jwt, req);
        Message msg = postService.post(req.channelId(), senderId, req.body(), req.parentMessageId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(msg));
    }

    @GetMapping
    public List<MessageResponse> list(@RequestParam UUID channelId,
                                       @RequestParam(required = false) Instant since) {
        List<Message> msgs = since == null
                ? repo.findByChannelIdOrderByCreatedAtAsc(channelId)
                : repo.findByChannelSince(channelId, since);
        return msgs.stream().map(this::toDto).toList();
    }

    private UUID resolveSender(Jwt jwt, PostMessageRequest req) {
        if (isServiceToken(jwt)) {
            return senderResolver.verifyAndResolve(req.asUserId());
        }
        return UUID.fromString(jwt.getSubject());
    }

    private boolean isServiceToken(Jwt jwt) {
        List<String> scopes = jwt.getClaimAsStringList("scope");
        return scopes != null && scopes.contains("bot:write");
    }

    private MessageResponse toDto(Message m) {
        return new MessageResponse(
                m.getId(),
                m.getChannelId(),
                m.getSenderId(),
                m.getBody(),
                m.getParentMessageId(),
                m.getMentions(),
                m.getCreatedAt());
    }
}
