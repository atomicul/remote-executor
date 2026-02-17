package dev.executor.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.ec2.model.Instance;

class InstanceRegistryTest {

    @Test
    void countsByInstanceId() {
        var items = List.of(
                Map.of("InstanceId", AttributeValue.fromS("i-aaa"), "JobState", AttributeValue.fromS("RUNNING")),
                Map.of("InstanceId", AttributeValue.fromS("i-aaa"), "JobState", AttributeValue.fromS("RUNNING")),
                Map.of("InstanceId", AttributeValue.fromS("i-bbb"), "JobState", AttributeValue.fromS("RUNNING")));

        var counts = InstanceRegistry.countByInstanceId(items);

        assertEquals(2, counts.get("i-aaa"));
        assertEquals(1, counts.get("i-bbb"));
        assertEquals(2, counts.size());
    }

    @Test
    void countsByInstanceIdEmptyList() {
        var counts = InstanceRegistry.countByInstanceId(List.of());

        assertTrue(counts.isEmpty());
    }

    @Test
    void pickIpPrefersPublic() {
        var registry = new InstanceRegistry(null, null);
        var instance = Instance.builder()
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("1.2.3.4", registry.pickIp(instance));
    }

    @Test
    void pickIpFallsBackToPrivate() {
        var registry = new InstanceRegistry(null, null);
        var instance = Instance.builder()
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("10.0.0.1", registry.pickIp(instance));
    }
}
