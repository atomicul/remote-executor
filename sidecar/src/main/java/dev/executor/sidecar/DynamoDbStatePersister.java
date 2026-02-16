package dev.executor.sidecar;

import dev.executor.common.JobStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class DynamoDbStatePersister implements JobEventListener {

    private static final Logger logger = LoggerFactory.getLogger(DynamoDbStatePersister.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";

    private final DynamoDbClient client;
    private final String instanceId;

    public DynamoDbStatePersister(DynamoDbClient client, String instanceId) {
        this.client = client;
        this.instanceId = instanceId;
    }

    @Override
    public void onJobStateChanged(JobStateChanged event) {
        var item = toItem(event.jobId(), event.current());
        client.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build());
        logger.debug("Persisted state for job {}", event.jobId());
    }

    Map<String, AttributeValue> toItem(String jobId, JobStatus status) {
        var item = new HashMap<String, AttributeValue>();
        item.put("JobId", AttributeValue.fromS(jobId));
        item.put("InstanceId", AttributeValue.fromS(instanceId));
        item.put("UpdatedAt", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond())));
        item.put("Result", AttributeValue.fromM(mapResult(status)));
        return item;
    }

    private Map<String, AttributeValue> mapResult(JobStatus status) {
        return switch (status.getResultCase()) {
            case RUNNING -> Map.of("Running", AttributeValue.fromM(Map.of(
                    "StartedAt", AttributeValue.fromS(status.getRunning().getStartedAt())
            )));
            case COMPLETED -> {
                var details = status.getCompleted();
                var completedMap = new HashMap<String, AttributeValue>();
                completedMap.put("ExitCode", AttributeValue.fromN(String.valueOf(details.getExitCode())));
                completedMap.put("OomKilled", AttributeValue.fromBool(details.getOomKilled()));
                completedMap.put("RecentLogs", AttributeValue.fromL(
                        details.getRecentLogsList().stream()
                                .map(AttributeValue::fromS)
                                .toList()));
                yield Map.of("Completed", AttributeValue.fromM(completedMap));
            }
            case SYSTEM_ERROR -> Map.of("SystemError", AttributeValue.fromM(Map.of(
                    "Reason", AttributeValue.fromS(status.getSystemError().getReason()),
                    "Message", AttributeValue.fromS(status.getSystemError().getMessage())
            )));
            default -> Map.of();
        };
    }
}
