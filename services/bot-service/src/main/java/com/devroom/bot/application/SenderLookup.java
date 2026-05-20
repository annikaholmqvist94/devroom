package com.devroom.bot.application;

import com.devroom.user.grpc.GetUserRequest;
import com.devroom.user.grpc.User;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slår upp display-namn för en sender via gRPC mot User Service.
 *
 * Vid gRPC-fel returneras "user" som fallback — bot-flödet ska inte stoppas av att
 * vi inte hittade ett namn. Mentorn kan generera ett svar utan personalisering.
 */
@Component
public class SenderLookup {

    private static final Logger log = LoggerFactory.getLogger(SenderLookup.class);
    private static final String FALLBACK_DISPLAY_NAME = "user";

    private final UserGrpcServiceBlockingStub stub;

    public SenderLookup(UserGrpcServiceBlockingStub stub) {
        this.stub = stub;
    }

    public String displayName(String userId) {
        try {
            User user = stub.getUser(GetUserRequest.newBuilder()
                    .setUserId(userId)
                    .build());
            return user.getDisplayName();
        } catch (StatusRuntimeException e) {
            log.warn("Failed to resolve sender {} via gRPC: {}", userId, e.getStatus());
            return FALLBACK_DISPLAY_NAME;
        }
    }
}
