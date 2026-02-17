package dev.executor.scheduler;

import dev.executor.common.Config;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;

public class InstanceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(InstanceRegistry.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";
    private static final String INDEX_NAME = "ActiveInstancesIndex";

    private final DynamoDbClient dynamoDb;
    private final Ec2Client ec2;
    private final Config config;

    public InstanceRegistry(DynamoDbClient dynamoDb, Ec2Client ec2, Config config) {
        this.dynamoDb = dynamoDb;
        this.ec2 = ec2;
        this.config = config;
    }

    private static final Set<String> EXCLUDED_STATES = Set.of("PROVISIONING", "SCHEDULED");

    public Map<String, Integer> getRunningJobCounts() {
        var scanRequest = ScanRequest.builder()
                .tableName(TABLE_NAME)
                .indexName(INDEX_NAME)
                .projectionExpression("InstanceId, JobState")
                .build();

        var counts = new HashMap<String, Integer>();
        for (var page : dynamoDb.scanPaginator(scanRequest)) {
            for (var item : page.items()) {
                var instanceId = item.get("InstanceId").s();
                var jobState = item.get("JobState").s();
                if (EXCLUDED_STATES.contains(jobState)) {
                    continue;
                }
                counts.merge(instanceId, "RUNNING".equals(jobState) ? 1 : 0, Integer::sum);
            }
        }

        logger.info("Fleet capacity: {}", counts);
        return counts;
    }

    public String resolveIp(String instanceId) {
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
                .map(this::pickIp)
                .orElseThrow(() -> new IllegalStateException(
                        "No running instance found for " + instanceId));
    }

    String pickIp(Instance instance) {
        if (instance.publicIpAddress() != null) {
            return instance.publicIpAddress();
        }
        return instance.privateIpAddress();
    }

    public Optional<String> selectTarget() {
        var counts = getRunningJobCounts();
        int maxJobs = config.getInt("max-running-jobs-per-instance", 5);
        return findAvailableInstance(counts, maxJobs).map(this::resolveIp);
    }

    static Optional<String> findAvailableInstance(Map<String, Integer> counts, int maxJobs) {
        return counts.entrySet().stream()
                .filter(e -> e.getValue() < maxJobs)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    static Map<String, Integer> countByInstanceId(List<Map<String, AttributeValue>> items) {
        var counts = new HashMap<String, Integer>();
        for (var item : items) {
            var instanceId = item.get("InstanceId").s();
            var jobState = item.get("JobState").s();
            if (EXCLUDED_STATES.contains(jobState)) {
                continue;
            }
            counts.merge(instanceId, "RUNNING".equals(jobState) ? 1 : 0, Integer::sum);
        }
        return counts;
    }
}
