package dev.executor.scaleout;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import dev.executor.common.Config;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.ec2.model.RunInstancesRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class ProvisionHandler implements RequestHandler<SNSEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(ProvisionHandler.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";
    private static final String SSM_PREFIX = "/remote-executor/scheduler/";

    private final Ec2Client ec2;
    private final DynamoDbClient dynamoDb;
    private final SqsClient sqs;
    private final Config config;
    private final String sqsQueueUrl;

    public ProvisionHandler() {
        this(Ec2Client.create(), DynamoDbClient.create(), SqsClient.create(),
                new Config(SSM_PREFIX), System.getenv("SQS_QUEUE_URL"));
    }

    ProvisionHandler(Ec2Client ec2, DynamoDbClient dynamoDb, SqsClient sqs,
                     Config config, String sqsQueueUrl) {
        this.ec2 = ec2;
        this.dynamoDb = dynamoDb;
        this.sqs = sqs;
        this.config = config;
        this.sqsQueueUrl = sqsQueueUrl;
    }

    @Override
    public Void handleRequest(SNSEvent event, Context context) {
        var message = event.getRecords().get(0).getSNS().getMessage();
        var payload = CommandPayload.fromJson(message);
        logger.info("Provisioning instance for job {}", payload.jobId());

        var launchTemplateId = config.getString("launch-template-id", "");

        var runResponse = ec2.runInstances(RunInstancesRequest.builder()
                .launchTemplate(LaunchTemplateSpecification.builder()
                        .launchTemplateId(launchTemplateId)
                        .build())
                .minCount(1)
                .maxCount(1)
                .build());

        var instanceId = runResponse.instances().get(0).instanceId();
        logger.info("Launched instance {} for job {}", instanceId, payload.jobId());

        dynamoDb.updateItem(UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("JobId", AttributeValue.fromS(payload.jobId())))
                .updateExpression("SET InstanceId = :iid, JobState = :state")
                .expressionAttributeValues(Map.of(
                        ":iid", AttributeValue.fromS(instanceId),
                        ":state", AttributeValue.fromS("PROVISIONING")))
                .build());

        var enrichedPayload = payload.withInstanceId(instanceId);
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(sqsQueueUrl)
                .messageBody(enrichedPayload.toJson())
                .build());

        logger.info("Enqueued job {} to SQS for instance {}", payload.jobId(), instanceId);
        return null;
    }
}
