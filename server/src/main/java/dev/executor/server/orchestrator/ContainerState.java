package dev.executor.server.orchestrator;

public record ContainerState(
    String containerId,
    boolean isRunning,
    Integer exitCode,
    boolean oomKilled,
    String systemError
) {}
