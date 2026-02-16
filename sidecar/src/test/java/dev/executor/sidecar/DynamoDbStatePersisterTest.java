package dev.executor.sidecar;

import static org.junit.jupiter.api.Assertions.*;

import dev.executor.common.CompletionDetails;
import dev.executor.common.FailureDetails;
import dev.executor.common.JobStatus;
import dev.executor.common.RunningDetails;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

class DynamoDbStatePersisterTest {

    private final DynamoDbStatePersister persister = new DynamoDbStatePersister(null, "i-abc123");

    @Test
    void mapsRunningJob() {
        var status = JobStatus.newBuilder()
                .setJobId("job-1")
                .setRunning(RunningDetails.newBuilder().setStartedAt("2026-02-16T00:00:00Z"))
                .build();

        var item = persister.toItem("job-1", status);

        assertEquals("job-1", item.get("JobId").s());
        assertEquals("i-abc123", item.get("InstanceId").s());
        assertNotNull(item.get("UpdatedAt").n());

        var result = item.get("Result").m();
        var running = result.get("Running").m();
        assertEquals("2026-02-16T00:00:00Z", running.get("StartedAt").s());
    }

    @Test
    void mapsCompletedJob() {
        var status = JobStatus.newBuilder()
                .setJobId("job-2")
                .setCompleted(CompletionDetails.newBuilder()
                        .setExitCode(42)
                        .setOomKilled(true)
                        .addRecentLogs("line 1")
                        .addRecentLogs("line 2"))
                .build();

        var item = persister.toItem("job-2", status);
        var completed = item.get("Result").m().get("Completed").m();

        assertEquals("42", completed.get("ExitCode").n());
        assertTrue(completed.get("OomKilled").bool());
        assertEquals(2, completed.get("RecentLogs").l().size());
        assertEquals("line 1", completed.get("RecentLogs").l().get(0).s());
        assertEquals("line 2", completed.get("RecentLogs").l().get(1).s());
    }

    @Test
    void mapsSystemErrorJob() {
        var status = JobStatus.newBuilder()
                .setJobId("job-3")
                .setSystemError(FailureDetails.newBuilder()
                        .setReason("SYSTEM_ERROR")
                        .setMessage("Container not found"))
                .build();

        var item = persister.toItem("job-3", status);
        var error = item.get("Result").m().get("SystemError").m();

        assertEquals("SYSTEM_ERROR", error.get("Reason").s());
        assertEquals("Container not found", error.get("Message").s());
    }
}
