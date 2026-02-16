package dev.executor.sidecar;

import dev.executor.common.ShellServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Command(name = "sidecar", mixinStandardHelpOptions = true)
public class Main implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    @Option(names = "--dry-run", description = "Skip AWS API calls, log state changes to stdout")
    private boolean dryRun;

    @Parameters(index = "0", description = "gRPC server address (e.g. localhost:9090)")
    private String target;

    @Parameters(index = "1", defaultValue = "dev.executor.common.ShellService",
            description = "Fully qualified gRPC service name (default: ${DEFAULT-VALUE})")
    private String service;

    @Override
    public void run() {
        String mode = dryRun ? "Dry Run" : "Production";
        logger.info("Sidecar starting in {} mode, target={}, service={}", mode, target, service);

        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();

        var stub = ShellServiceGrpc.newBlockingStub(channel);
        var engine = new PollingEngine(stub);
        engine.addListener(new LoggingSubscriber());

        if (!dryRun) {
            var dynamoDb = DynamoDbClient.create();
            var instanceId = System.getenv().getOrDefault("INSTANCE_ID", "unknown");
            engine.addListener(new DynamoDbStatePersister(dynamoDb, instanceId));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down sidecar");
            engine.shutdown();
            channel.shutdown();
        }));

        engine.start();

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
