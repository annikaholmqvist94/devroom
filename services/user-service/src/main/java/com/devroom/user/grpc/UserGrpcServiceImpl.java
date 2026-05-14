package com.devroom.user.grpc;

import com.devroom.user.domain.User;
import com.devroom.user.domain.UserRepository;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class UserGrpcServiceImpl extends UserGrpcServiceGrpc.UserGrpcServiceImplBase {

    private final UserRepository repo;

    public UserGrpcServiceImpl(UserRepository repo) {
        this.repo = repo;
    }

    @Override
    public void getUser(GetUserRequest request, StreamObserver<com.devroom.user.grpc.User> responseObserver) {
        UUID id;
        try {
            id = UUID.fromString(request.getUserId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("Invalid user_id").asRuntimeException());
            return;
        }
        repo.findById(id).ifPresentOrElse(
                u -> {
                    responseObserver.onNext(toProto(u));
                    responseObserver.onCompleted();
                },
                () -> responseObserver.onError(
                        Status.NOT_FOUND.withDescription("User not found").asRuntimeException())
        );
    }

    @Override
    public void resolveMentions(ResolveMentionsRequest request,
                                StreamObserver<ResolveMentionsResponse> responseObserver) {
        UUID teamId;
        try {
            teamId = UUID.fromString(request.getTeamId());
        } catch (IllegalArgumentException e) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("Invalid team_id").asRuntimeException());
            return;
        }

        List<String> names = request.getDisplayNamesList();
        List<User> found = names.isEmpty()
                ? List.of()
                : repo.findAllByTeamIdAndDisplayNameIn(teamId, names);

        ResolveMentionsResponse.Builder resp = ResolveMentionsResponse.newBuilder();
        for (User u : found) {
            resp.addUsers(toProto(u));
        }
        responseObserver.onNext(resp.build());
        responseObserver.onCompleted();
    }

    private static com.devroom.user.grpc.User toProto(User u) {
        com.devroom.user.grpc.User.Builder b = com.devroom.user.grpc.User.newBuilder()
                .setUserId(u.getUserId().toString())
                .setDisplayName(u.getDisplayName())
                .setTeamId(u.getTeamId().toString())
                .setIsSystem(u.isSystem());
        if (u.getMentorPersonality() != null) {
            b.setMentorPersonality(u.getMentorPersonality());
        }
        return b.build();
    }
}
