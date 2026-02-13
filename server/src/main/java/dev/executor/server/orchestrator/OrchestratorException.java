package dev.executor.server.orchestrator;

public class OrchestratorException extends RuntimeException {

    public OrchestratorException(String message) {
        super(message);
    }

    public OrchestratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
