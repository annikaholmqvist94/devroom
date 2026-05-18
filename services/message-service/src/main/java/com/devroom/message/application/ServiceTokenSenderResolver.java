package com.devroom.message.application;

import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.User;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Verifierar att service-token (scope bot:write) bara postar som system-users.
 * Förhindrar confused-deputy: utan check kan Bot Service posta som vilken som helst riktig användare.
 */
@Component
public class ServiceTokenSenderResolver {

    private final UserGrpcServiceBlockingStub stub;

    public ServiceTokenSenderResolver(UserGrpcServiceBlockingStub stub) {
        this.stub = stub;
    }

    public UUID verifyAndResolve(UUID asUserId) {
        if (asUserId == null) {
            throw new IllegalArgumentException("Service token requires as_user_id");
        }
        User user;
        try {
            user = stub.getUser(GetUserRequest.newBuilder()
                    .setUserId(asUserId.toString())
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new IllegalArgumentException("as_user_id does not refer to a known user");
            }
            throw new MentionResolutionException("Failed to verify as_user_id: " + e.getStatus(), e);
        }
        if (!user.getIsSystem()) {
            throw new SenderNotAllowedException("Service token can only post as system users");
        }
        return asUserId;
    }
}
