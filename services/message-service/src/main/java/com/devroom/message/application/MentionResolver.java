package com.devroom.message.application;

import com.devroom.message.domain.MentionInfo;
import com.devroom.user.grpc.ResolveMentionsRequest;
import com.devroom.user.grpc.ResolveMentionsResponse;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class MentionResolver {

    private final UserGrpcServiceBlockingStub stub;

    public MentionResolver(UserGrpcServiceBlockingStub stub) {
        this.stub = stub;
    }

    public List<MentionInfo> resolve(UUID teamId, List<String> displayNames) {
        if (displayNames.isEmpty()) {
            return List.of();
        }
        try {
            ResolveMentionsResponse resp = stub.resolveMentions(ResolveMentionsRequest.newBuilder()
                    .setTeamId(teamId.toString())
                    .addAllDisplayNames(displayNames)
                    .build());
            return resp.getUsersList().stream()
                    .map(u -> new MentionInfo(
                            u.getUserId(),
                            u.getIsSystem(),
                            u.getMentorPersonality().isEmpty() ? null : u.getMentorPersonality()))
                    .toList();
        } catch (StatusRuntimeException e) {
            throw new MentionResolutionException(
                    "Failed to resolve mentions: " + e.getStatus(), e);
        }
    }
}
