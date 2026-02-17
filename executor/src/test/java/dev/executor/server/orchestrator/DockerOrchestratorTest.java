package dev.executor.server.orchestrator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DockerOrchestratorTest {

    static DockerJavaOrchestrator orchestrator;

    @BeforeAll
    static void setup() {
        orchestrator = new DockerJavaOrchestrator();
    }

    @Test
    void shouldRunContainerAndComplete() throws InterruptedException {
        var containerId = orchestrator.startContainer(
                "alpine:latest", "echo hello", new ResourceLimits(0, 0));

        assertNotNull(containerId);

        Thread.sleep(2000);

        var state = orchestrator.inspectContainer(containerId);
        assertFalse(state.isRunning());
        assertEquals(0, state.exitCode());
        assertFalse(state.oomKilled());
        assertNull(state.systemError());
    }

    @Test
    void shouldReportNonZeroExitCode() throws InterruptedException {
        var containerId = orchestrator.startContainer(
                "alpine:latest", "exit 42", new ResourceLimits(0, 0));

        Thread.sleep(2000);

        var state = orchestrator.inspectContainer(containerId);
        assertFalse(state.isRunning());
        assertEquals(42, state.exitCode());
    }

    @Test
    void shouldReturnSystemErrorForUnknownContainer() {
        var state = orchestrator.inspectContainer("nonexistent");
        assertEquals("Container not found", state.systemError());
    }

    @Test
    void shouldTailLogs() throws InterruptedException {
        var containerId = orchestrator.startContainer(
                "alpine:latest", "echo Line1; echo Line2", new ResourceLimits(0, 0));

        Thread.sleep(2000);

        var logs = orchestrator.tailLogs(containerId, 100);
        assertTrue(logs.stream().anyMatch(l -> l.contains("Line1")));
        assertTrue(logs.stream().anyMatch(l -> l.contains("Line2")));
    }
}
