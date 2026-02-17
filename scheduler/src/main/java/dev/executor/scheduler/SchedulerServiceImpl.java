package dev.executor.scheduler;

import dev.executor.common.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

public class SchedulerServiceImpl extends ShellServiceGrpc.ShellServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerServiceImpl.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";

    private final DynamoDbClient dynamoDb;

    public SchedulerServiceImpl(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
    }

    @Override
    public void getJobStatus(JobIdRequest request, StreamObserver<JobStatus> responseObserver) {
        try {
            var response = dynamoDb.getItem(GetItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(Map.of("JobId", AttributeValue.fromS(request.getJobId())))
                    .build());

            if (!response.hasItem()) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Job not found: " + request.getJobId())
                        .asException());
                return;
            }

            responseObserver.onNext(toJobStatus(response.item()));
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to get job status for {}", request.getJobId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get job status: " + e.getMessage())
                    .asException());
        }
    }

    @Override
    public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> responseObserver) {
        try {
            var jobIds = new ArrayList<String>();
            var scanRequest = ScanRequest.builder()
                    .tableName(TABLE_NAME)
                    .projectionExpression("JobId")
                    .build();

            for (var page : dynamoDb.scanPaginator(scanRequest)) {
                for (var item : page.items()) {
                    jobIds.add(item.get("JobId").s());
                }
            }

            responseObserver.onNext(ListJobsResponse.newBuilder()
                    .addAllJobIds(jobIds)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to list jobs", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to list jobs: " + e.getMessage())
                    .asException());
        }
    }

    @Override
    public void startJob(CommandRequest request, StreamObserver<JobResponse> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("Not yet implemented")
                .asException());
    }

    @Override
    public void watchJobLogs(JobIdRequest request, StreamObserver<LogChunk> responseObserver) {
        responseObserver.onError(Status.UNIMPLEMENTED
                .withDescription("Not yet implemented")
                .asException());
    }

    static JobStatus toJobStatus(Map<String, AttributeValue> item) {
        var builder = JobStatus.newBuilder()
                .setJobId(item.get("JobId").s());

        var result = item.get("Result").m();

        if (result.containsKey("Running")) {
            var running = result.get("Running").m();
            builder.setRunning(RunningDetails.newBuilder()
                    .setStartedAt(running.get("StartedAt").s()));
        } else if (result.containsKey("Completed")) {
            var completed = result.get("Completed").m();
            var details = CompletionDetails.newBuilder()
                    .setExitCode(Integer.parseInt(completed.get("ExitCode").n()))
                    .setOomKilled(completed.get("OomKilled").bool());
            for (var log : completed.get("RecentLogs").l()) {
                details.addRecentLogs(log.s());
            }
            builder.setCompleted(details);
        } else if (result.containsKey("SystemError")) {
            var error = result.get("SystemError").m();
            builder.setSystemError(FailureDetails.newBuilder()
                    .setReason(error.get("Reason").s())
                    .setMessage(error.get("Message").s()));
        }

        return builder.build();
    }
}
