package dev.executor.scheduler;

import dev.executor.common.Config;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.ec2.Ec2Client;

public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final int PORT = 9090;

    private static final String SSM_PREFIX = "/remote-executor/scheduler/";

    public static void main(String[] args) throws IOException, InterruptedException {
        var config = new Config(SSM_PREFIX);
        var dynamoDb = DynamoDbClient.create();
        var ec2 = Ec2Client.create();
        var registry = new InstanceRegistry(dynamoDb, ec2, config);
        var service = new SchedulerServiceImpl(dynamoDb);

        Server server = ServerBuilder.forPort(PORT)
                .addService(service)
                .addService(ProtoReflectionServiceV1.newInstance())
                .build()
                .start();

        logger.info("Scheduler listening on port {}", PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down scheduler");
            server.shutdown();
            dynamoDb.close();
            ec2.close();
            config.close();
        }));

        server.awaitTermination();
    }
}
