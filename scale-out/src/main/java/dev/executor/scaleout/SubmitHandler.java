package dev.executor.scaleout;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import dev.executor.common.CommandRequest;
import dev.executor.common.ShellServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;

public class SubmitHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(SubmitHandler.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";
    private static final int EXECUTOR_PORT = 9090;

    private final Ec2Client ec2;
    private final DynamoDbClient dynamoDb;

    public SubmitHandler() {
        this(Ec2Client.create(), DynamoDbClient.create());
    }

    SubmitHandler(Ec2Client ec2, DynamoDbClient dynamoDb) {
        this.ec2 = ec2;
        this.dynamoDb = dynamoDb;
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        var body = event.getRecords().get(0).getBody();
        var payload = CommandPayload.fromJson(body);
        logger.info("Submitting job {} to instance {}", payload.jobId(), payload.instanceId());

        var ip = resolveIp(payload.instanceId());
        logger.info("Resolved instance {} to IP {}", payload.instanceId(), ip);

        ManagedChannel channel = null;
        try {
            channel = NettyChannelBuilder
                    .forAddress(ip, EXECUTOR_PORT)
                    .usePlaintext()
                    .build();

            var stub = ShellServiceGrpc.newBlockingStub(channel);
            var requestBuilder = CommandRequest.newBuilder()
                    .setCommand(payload.command())
                    .setMemoryLimitMb(payload.memoryLimitMb())
                    .setCpuLimit(payload.cpuLimit());
            if (payload.envVars() != null) {
                requestBuilder.putAllEnvVars(payload.envVars());
            }

            var response = stub.startJob(requestBuilder.build());
            var executorJobId = response.getJobId();
            logger.info("Job {} submitted, executor assigned {}", payload.jobId(), executorJobId);

            dynamoDb.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(Map.of("JobId", AttributeValue.fromS(payload.jobId())))
                    .updateExpression("SET ExecutorJobId = :ejid, JobState = :state")
                    .expressionAttributeValues(Map.of(
                            ":ejid", AttributeValue.fromS(executorJobId),
                            ":state", AttributeValue.fromS("SUBMITTED")))
                    .build());
        } finally {
            if (channel != null) {
                channel.shutdownNow();
                try {
                    channel.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return null;
    }

    String resolveIp(String instanceId) {
        var response = ec2.describeInstances(DescribeInstancesRequest.builder()
                .instanceIds(instanceId)
                .filters(Filter.builder()
                        .name("instance-state-name")
                        .values("running")
                        .build())
                .build());

        return response.reservations().stream()
                .flatMap(r -> r.instances().stream())
                .findFirst()
                .map(SubmitHandler::pickIp)
                .orElseThrow(() -> new RuntimeException(
                        "Instance " + instanceId + " is not running yet"));
    }

    static String pickIp(Instance instance) {
        if (instance.publicIpAddress() != null) {
            return instance.publicIpAddress();
        }
        return instance.privateIpAddress();
    }
}
