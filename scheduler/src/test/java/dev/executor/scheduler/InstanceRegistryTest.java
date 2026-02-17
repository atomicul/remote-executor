package dev.executor.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void countsOnlyRunningJobs() {
        var items = List.of(
                Map.of("InstanceId", AttributeValue.fromS("i-aaa"), "JobState", AttributeValue.fromS("RUNNING")),
                Map.of("InstanceId", AttributeValue.fromS("i-aaa"), "JobState", AttributeValue.fromS("COMPLETED")),
                Map.of("InstanceId", AttributeValue.fromS("i-bbb"), "JobState", AttributeValue.fromS("COMPLETED")),
                Map.of("InstanceId", AttributeValue.fromS("i-bbb"), "JobState", AttributeValue.fromS("SYSTEM_ERROR")));

        var counts = InstanceRegistry.countByInstanceId(items);

        assertEquals(1, counts.get("i-aaa"));
        assertEquals(0, counts.get("i-bbb"));
    }

    @Test
    void skipsProvisioningAndScheduledStates() {
        var items = List.of(
                Map.of("InstanceId", AttributeValue.fromS("i-aaa"), "JobState", AttributeValue.fromS("PROVISIONING")),
                Map.of("InstanceId", AttributeValue.fromS("i-bbb"), "JobState", AttributeValue.fromS("RUNNING")),
                Map.of("InstanceId", AttributeValue.fromS("i-ccc"), "JobState", AttributeValue.fromS("SCHEDULED")));

        var counts = InstanceRegistry.countByInstanceId(items);

        assertFalse(counts.containsKey("i-aaa"));
        assertEquals(1, counts.get("i-bbb"));
        assertFalse(counts.containsKey("i-ccc"));
    }

    @Test
    void countsByInstanceIdEmptyList() {
        var counts = InstanceRegistry.countByInstanceId(List.of());

        assertTrue(counts.isEmpty());
    }

    @Test
    void pickIpPrefersPublic() {
        var registry = new InstanceRegistry(null, null, null);
        var instance = Instance.builder()
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("1.2.3.4", registry.pickIp(instance));
    }

    @Test
    void pickIpFallsBackToPrivate() {
        var registry = new InstanceRegistry(null, null, null);
        var instance = Instance.builder()
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("10.0.0.1", registry.pickIp(instance));
    }

    @Test
    void findAvailableInstancePicksUnderCapacity() {
        var counts = Map.of("i-full", 5, "i-available", 3);

        var result = InstanceRegistry.findAvailableInstance(counts, 5);

        assertEquals(Optional.of("i-available"), result);
    }

    @Test
    void findAvailableInstanceReturnsEmptyWhenAllFull() {
        var counts = Map.of("i-aaa", 5, "i-bbb", 5);

        var result = InstanceRegistry.findAvailableInstance(counts, 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void findAvailableInstanceReturnsEmptyWhenFleetEmpty() {
        var result = InstanceRegistry.findAvailableInstance(Map.of(), 5);

        assertTrue(result.isEmpty());
    }
}
