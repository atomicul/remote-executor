package dev.executor.server;

import dev.executor.common.*;
import dev.executor.server.orchestrator.ContainerOrchestrator;
import dev.executor.server.orchestrator.ResourceLimits;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ShellServiceImpl extends ShellServiceGrpc.ShellServiceImplBase {

    private final ContainerOrchestrator orchestrator;
    private final Map<String, String> jobToContainer = new ConcurrentHashMap<>();

    public ShellServiceImpl(ContainerOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void startJob(CommandRequest request, StreamObserver<JobResponse> responseObserver) {
        try {
            var limits = new ResourceLimits(
                    request.getMemoryLimitMb(),
                    request.getCpuLimit()
            );

            var containerId = orchestrator.startContainer(
                    "alpine:latest",
                    request.getCommand(),
                    limits
            );

            var jobId = UUID.randomUUID().toString();
            jobToContainer.put(jobId, containerId);

            var response = JobResponse.newBuilder()
                    .setJobId(jobId)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to start job: " + e.getMessage())
                    .asException());
        }
    }

    @Override
    public void getJobStatus(JobIdRequest request, StreamObserver<JobStatus> responseObserver) {
        try {
            var containerId = jobToContainer.get(request.getJobId());
            if (containerId == null) {
                responseObserver.onError(io.grpc.Status.NOT_FOUND
                        .withDescription("Job not found: " + request.getJobId())
                        .asException());
                return;
            }

            var state = orchestrator.inspectContainer(containerId);

            var statusBuilder = JobStatus.newBuilder()
                    .setJobId(request.getJobId());

            if (state.systemError() != null) {
                statusBuilder.setSystemError(FailureDetails.newBuilder()
                        .setReason("SYSTEM_ERROR")
                        .setMessage(state.systemError())
                        .build());
            } else if (state.isRunning()) {
                statusBuilder.setRunning(RunningDetails.newBuilder()
                        .setStartedAt(java.time.Instant.now().toString())
                        .build());
            } else {
                var logsBuilder = orchestrator.tailLogs(containerId, 50);
                statusBuilder.setCompleted(CompletionDetails.newBuilder()
                        .setExitCode(state.exitCode() != null ? state.exitCode() : 0)
                        .setOomKilled(state.oomKilled())
                        .addAllRecentLogs(logsBuilder)
                        .build());
            }

            responseObserver.onNext(statusBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to get job status: " + e.getMessage())
                    .asException());
        }
    }

    @Override
    public void watchJobLogs(JobIdRequest request, StreamObserver<LogChunk> responseObserver) {
        var containerId = jobToContainer.get(request.getJobId());
        if (containerId == null) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription("Job not found: " + request.getJobId())
                    .asException());
            return;
        }

        orchestrator.streamLogs(
                containerId,
                line -> responseObserver.onNext(LogChunk.newBuilder().setContent(line).build()),
                responseObserver::onCompleted,
                error -> responseObserver.onError(Status.INTERNAL
                        .withDescription("Log streaming failed: " + error.getMessage())
                        .asException())
        );
    }
}
