package dev.executor.server.orchestrator;

import java.util.List;

public interface ContainerOrchestrator {

    String startContainer(String image, String command, ResourceLimits limits);

    ContainerState inspectContainer(String containerId);

    List<String> tailLogs(String containerId, int lines);
}
