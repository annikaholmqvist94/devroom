package com.devroom.message.config;

import com.devroom.user.grpc.UserGrpcServiceGrpc;
import com.devroom.user.grpc.UserGrpcServiceGrpc.UserGrpcServiceBlockingStub;
import io.grpc.ClientInterceptors;
import io.micrometer.core.instrument.binder.grpc.ObservationGrpcClientInterceptor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

@Configuration
public class GrpcClientConfig {

    @Bean
    UserGrpcServiceBlockingStub userServiceStub(GrpcChannelFactory channels,
                                                ObservationRegistry observationRegistry) {
        var channel = channels.createChannel("user-service");
        var traced = ClientInterceptors.intercept(channel,
                new ObservationGrpcClientInterceptor(observationRegistry));
        return UserGrpcServiceGrpc.newBlockingStub(traced);
    }
}
