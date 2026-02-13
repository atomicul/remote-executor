package dev.executor.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;

public class Main {

    private static final int PORT = 9090;

    public static void main(String[] args) throws IOException, InterruptedException {
        Server server = ServerBuilder.forPort(PORT)
                .addService(new ShellServiceImpl())
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        System.out.println("Remote Shell Executor listening on port " + PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
        server.awaitTermination();
    }
}
