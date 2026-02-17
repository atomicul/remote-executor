package dev.executor.scheduler;

import com.google.gson.Gson;
import dev.executor.common.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

public class SchedulerServiceImpl extends ShellServiceGrpc.ShellServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(SchedulerServiceImpl.class);
    private static final String TABLE_NAME = "RemoteExecutor-JobState";
    private static final int EXECUTOR_PORT = 9090;

    private final DynamoDbClient dynamoDb;
    private final InstanceRegistry registry;
    private final SnsClient sns;
    private final String snsTopicArn;
    private final Gson gson = new Gson();

    public SchedulerServiceImpl(DynamoDbClient dynamoDb, InstanceRegistry registry,
                                SnsClient sns, String snsTopicArn) {
        this.dynamoDb = dynamoDb;
        this.registry = registry;
        this.sns = sns;
        this.snsTopicArn = snsTopicArn;
    }

    @Override
    public void getJobStatus(JobIdRequest request, StreamObserver<JobStatus> responseObserver) {
        try {
            var item = resolveJobItem(request.getJobId());

            if (item == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Job not found: " + request.getJobId())
                        .asException());
                return;
            }

            responseObserver.onNext(toJobStatus(item));
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
        try {
            var target = registry.selectTarget();

            if (target.isPresent()) {
                proxyStartJob(target.get(), request, responseObserver);
                return;
            }

            var jobId = UUID.randomUUID().toString();
            writeScheduledState(jobId);
            publishToSns(jobId, request);

            logger.info("Scheduled job {} for async provisioning", jobId);
            responseObserver.onNext(JobResponse.newBuilder().setJobId(jobId).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to start job", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to start job: " + e.getMessage())
                    .asException());
        }
    }

    private void proxyStartJob(String targetIp, CommandRequest request,
                               StreamObserver<JobResponse> responseObserver) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(targetIp, EXECUTOR_PORT)
                .usePlaintext()
                .build();
        try {
            var response = ShellServiceGrpc.newBlockingStub(channel).startJob(request);
            logger.info("Proxied startJob to {}, jobId={}", targetIp, response.getJobId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            logger.error("Failed to proxy startJob to {}", targetIp, e);
            responseObserver.onError(Status.UNAVAILABLE
                    .withDescription("Executor at " + targetIp + " is unavailable")
                    .withCause(e)
                    .asException());
        } finally {
            channel.shutdown();
        }
    }

    @Override
    public void watchJobLogs(JobIdRequest request, StreamObserver<LogChunk> responseObserver) {
        try {
            var item = resolveJobItem(request.getJobId());
            if (item == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Job not found: " + request.getJobId())
                        .asException());
                return;
            }

            var jobStateAttr = item.get("JobState");
            var jobState = jobStateAttr != null ? jobStateAttr.s() : "";

            switch (jobState) {
                case "COMPLETED" -> {
                    sendRecentLogs(item, responseObserver);
                    responseObserver.onCompleted();
                }
                case "SYSTEM_ERROR" -> {
                    sendErrorMessage(item, responseObserver);
                    responseObserver.onCompleted();
                }
                case "RUNNING" -> {
                    var instanceIdAttr = item.get("InstanceId");
                    if (instanceIdAttr == null) {
                        responseObserver.onError(Status.FAILED_PRECONDITION
                                .withDescription("Job has no assigned instance")
                                .asException());
                        return;
                    }
                    String ip;
                    try {
                        ip = registry.resolveIp(instanceIdAttr.s());
                    } catch (Exception e) {
                        logger.warn("Instance {} is no longer reachable", instanceIdAttr.s(), e);
                        responseObserver.onError(Status.UNAVAILABLE
                                .withDescription("Executor instance is no longer reachable")
                                .withCause(e)
                                .asException());
                        return;
                    }
                    var executorJobId = item.get("JobId").s();
                    proxyWatchJobLogs(ip, executorJobId, responseObserver);
                }
                default -> responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Job is not running yet (state: " + jobState + ")")
                        .asException());
            }
        } catch (Exception e) {
            logger.error("Failed to watch job logs for {}", request.getJobId(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to watch job logs: " + e.getMessage())
                    .asException());
        }
    }

    private void sendRecentLogs(Map<String, AttributeValue> item,
                                StreamObserver<LogChunk> responseObserver) {
        var result = item.get("Result");
        if (result == null) return;
        var completed = result.m().get("Completed");
        if (completed == null) return;
        var recentLogs = completed.m().get("RecentLogs");
        if (recentLogs == null) return;

        var logs = new StringBuilder();
        for (var log : recentLogs.l()) {
            if (!logs.isEmpty()) logs.append("\n");
            logs.append(log.s());
        }
        if (!logs.isEmpty()) {
            responseObserver.onNext(LogChunk.newBuilder().setContent(logs.toString()).build());
        }
    }

    private void sendErrorMessage(Map<String, AttributeValue> item,
                                  StreamObserver<LogChunk> responseObserver) {
        var result = item.get("Result");
        if (result == null) return;
        var error = result.m().get("SystemError");
        if (error == null) return;

        var message = error.m().get("Message");
        if (message != null) {
            responseObserver.onNext(LogChunk.newBuilder().setContent(message.s()).build());
        }
    }

    private void proxyWatchJobLogs(String targetIp, String executorJobId,
                                   StreamObserver<LogChunk> responseObserver) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(targetIp, EXECUTOR_PORT)
                .usePlaintext()
                .build();

        var proxyRequest = JobIdRequest.newBuilder().setJobId(executorJobId).build();
        ShellServiceGrpc.newStub(channel).watchJobLogs(proxyRequest, new StreamObserver<>() {
            @Override
            public void onNext(LogChunk chunk) {
                responseObserver.onNext(chunk);
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Log stream from {} failed for job {}", targetIp, executorJobId, t);
                responseObserver.onError(Status.UNAVAILABLE
                        .withDescription("Log stream from executor failed")
                        .withCause(t)
                        .asException());
                channel.shutdown();
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
                channel.shutdown();
            }
        });
    }

    Map<String, AttributeValue> resolveJobItem(String jobId) {
        var item = getItem(jobId);
        if (item == null) {
            return null;
        }
        var executorJobId = item.get("ExecutorJobId");
        if (executorJobId != null) {
            var executorItem = getItem(executorJobId.s());
            if (executorItem != null) {
                return executorItem;
            }
        }
        return item;
    }

    private Map<String, AttributeValue> getItem(String jobId) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("JobId", AttributeValue.fromS(jobId)))
                .build());
        return response.hasItem() ? response.item() : null;
    }

    void writeScheduledState(String jobId) {
        var item = Map.of(
                "JobId", AttributeValue.fromS(jobId),
                "JobState", AttributeValue.fromS("SCHEDULED"),
                "UpdatedAt", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond())));

        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(TABLE_NAME)
                .item(item)
                .build());
    }

    void publishToSns(String jobId, CommandRequest request) {
        var payload = new HashMap<String, Object>();
        payload.put("jobId", jobId);
        payload.put("command", request.getCommand());
        payload.put("memoryLimitMb", request.getMemoryLimitMb());
        payload.put("cpuLimit", request.getCpuLimit());
        if (!request.getEnvVarsMap().isEmpty()) {
            payload.put("envVars", request.getEnvVarsMap());
        }

        sns.publish(PublishRequest.builder()
                .topicArn(snsTopicArn)
                .message(gson.toJson(payload))
                .build());
    }

    static JobStatus toJobStatus(Map<String, AttributeValue> item) {
        var builder = JobStatus.newBuilder()
                .setJobId(item.get("JobId").s());

        var resultAttr = item.get("Result");
        if (resultAttr == null) {
            return builder.build();
        }

        var result = resultAttr.m();

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
