package dev.executor.server;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

public class AuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final String expectedToken;

    public AuthInterceptor(String secret) {
        this.expectedToken = "Bearer " + secret;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String authHeader = headers.get(AUTHORIZATION_KEY);

        if (authHeader == null || !authHeader.equals(expectedToken)) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid or missing authorization token"), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        return Contexts.interceptCall(Context.current(), call, headers, next);
    }
}
