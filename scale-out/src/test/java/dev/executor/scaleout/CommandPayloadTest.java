package dev.executor.scaleout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandPayloadTest {

    @Test
    void roundTripsWithEnvVars() {
        var payload = new CommandPayload(
                "job-1", "i-abc", "echo hello", 512, 1.5f,
                Map.of("FOO", "bar", "BAZ", "qux"));

        var json = payload.toJson();
        var restored = CommandPayload.fromJson(json);

        assertEquals("job-1", restored.jobId());
        assertEquals("i-abc", restored.instanceId());
        assertEquals("echo hello", restored.command());
        assertEquals(512, restored.memoryLimitMb());
        assertEquals(1.5f, restored.cpuLimit(), 0.001f);
        assertEquals(Map.of("FOO", "bar", "BAZ", "qux"), restored.envVars());
    }

    @Test
    void roundTripsWithoutEnvVars() {
        var payload = new CommandPayload(
                "job-2", null, "ls -la", 256, 0.5f, null);

        var json = payload.toJson();
        var restored = CommandPayload.fromJson(json);

        assertEquals("job-2", restored.jobId());
        assertNull(restored.instanceId());
        assertEquals("ls -la", restored.command());
        assertEquals(256, restored.memoryLimitMb());
        assertNull(restored.envVars());
    }

    @Test
    void withInstanceIdCreatesNewPayload() {
        var original = new CommandPayload(
                "job-3", null, "pwd", 128, 1.0f, Map.of("A", "1"));

        var updated = original.withInstanceId("i-xyz");

        assertEquals("i-xyz", updated.instanceId());
        assertNull(original.instanceId());
        assertEquals(original.jobId(), updated.jobId());
        assertEquals(original.command(), updated.command());
        assertEquals(original.envVars(), updated.envVars());
    }
}
