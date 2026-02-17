package dev.executor.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class SchedulerServiceImplTest {

    @Test
    void mapsRunningItem() {
        var item = Map.of(
                "JobId", AttributeValue.fromS("job-1"),
                "InstanceId", AttributeValue.fromS("i-abc"),
                "JobState", AttributeValue.fromS("RUNNING"),
                "Result", AttributeValue.fromM(Map.of(
                        "Running", AttributeValue.fromM(Map.of(
                                "StartedAt", AttributeValue.fromS("2026-02-17T00:00:00Z"))))));

        var status = SchedulerServiceImpl.toJobStatus(item);

        assertEquals("job-1", status.getJobId());
        assertTrue(status.hasRunning());
        assertEquals("2026-02-17T00:00:00Z", status.getRunning().getStartedAt());
    }

    @Test
    void mapsCompletedItem() {
        var item = Map.of(
                "JobId", AttributeValue.fromS("job-2"),
                "InstanceId", AttributeValue.fromS("i-abc"),
                "JobState", AttributeValue.fromS("COMPLETED"),
                "Result", AttributeValue.fromM(Map.of(
                        "Completed", AttributeValue.fromM(Map.of(
                                "ExitCode", AttributeValue.fromN("42"),
                                "OomKilled", AttributeValue.fromBool(true),
                                "RecentLogs", AttributeValue.fromL(List.of(
                                        AttributeValue.fromS("line 1"),
                                        AttributeValue.fromS("line 2"))))))));

        var status = SchedulerServiceImpl.toJobStatus(item);

        assertEquals("job-2", status.getJobId());
        assertTrue(status.hasCompleted());
        assertEquals(42, status.getCompleted().getExitCode());
        assertTrue(status.getCompleted().getOomKilled());
        assertEquals(List.of("line 1", "line 2"), status.getCompleted().getRecentLogsList());
    }

    @Test
    void mapsSystemErrorItem() {
        var item = Map.of(
                "JobId", AttributeValue.fromS("job-3"),
                "InstanceId", AttributeValue.fromS("i-abc"),
                "JobState", AttributeValue.fromS("SYSTEM_ERROR"),
                "Result", AttributeValue.fromM(Map.of(
                        "SystemError", AttributeValue.fromM(Map.of(
                                "Reason", AttributeValue.fromS("SYSTEM_ERROR"),
                                "Message", AttributeValue.fromS("Container not found"))))));

        var status = SchedulerServiceImpl.toJobStatus(item);

        assertEquals("job-3", status.getJobId());
        assertTrue(status.hasSystemError());
        assertEquals("SYSTEM_ERROR", status.getSystemError().getReason());
        assertEquals("Container not found", status.getSystemError().getMessage());
    }
}
