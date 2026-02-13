package dev.executor.server;

import dev.executor.common.CommandRequest;
import dev.executor.common.JobResponse;
import dev.executor.common.ShellServiceGrpc;
import io.grpc.stub.StreamObserver;
import java.util.UUID;

public class ShellServiceImpl extends ShellServiceGrpc.ShellServiceImplBase {

    @Override
    public void startJob(CommandRequest request, StreamObserver<JobResponse> responseObserver) {
        var jobId = UUID.randomUUID().toString();
        var response = JobResponse.newBuilder()
                .setJobId(jobId)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
