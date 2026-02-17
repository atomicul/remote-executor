package dev.executor.server.orchestrator;

import java.util.List;
import java.util.function.Consumer;

public interface ContainerOrchestrator {

    String startContainer(String image, String command, ResourceLimits limits);

    ContainerState inspectContainer(String containerId);

    List<String> tailLogs(String containerId, int lines);

    void streamLogs(String containerId, Consumer<String> onLine, Runnable onComplete, Consumer<Throwable> onError);
}
