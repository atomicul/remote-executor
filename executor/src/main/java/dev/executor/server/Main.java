package dev.executor.server;

import dev.executor.server.orchestrator.DockerJavaOrchestrator;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;

public class Main {

    private static final int PORT = 9090;
    private static final String API_KEY_ENV = "API_KEY";

    public static void main(String[] args) throws IOException, InterruptedException {
        var orchestrator = new DockerJavaOrchestrator();
        var apiKey = System.getenv(API_KEY_ENV);

        ServerServiceDefinition shellService = new ShellServiceImpl(orchestrator).bindService();
        if (apiKey != null && !apiKey.isBlank()) {
            shellService = io.grpc.ServerInterceptors.intercept(shellService, new AuthInterceptor(apiKey));
            System.out.println("Authorization enabled (API_KEY is set)");
        } else {
            System.out.println("Authorization disabled (API_KEY is not set)");
        }

        Server server = ServerBuilder.forPort(PORT)
                .addService(shellService)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        System.out.println("Remote Shell Executor listening on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
