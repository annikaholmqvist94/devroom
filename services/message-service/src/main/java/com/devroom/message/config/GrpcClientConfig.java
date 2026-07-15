package com.devroom.message.config;

import com.devroom.user.grpc.UserGrpcServiceGrpc;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    UserGrpcServiceBlockingStub userServiceStub(GrpcChannelFactory channels) {
        return UserGrpcServiceGrpc.newBlockingStub(channels.createChannel("user-service"));
    }
}
