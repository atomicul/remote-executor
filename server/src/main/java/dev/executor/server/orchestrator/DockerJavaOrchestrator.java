package dev.executor.server.orchestrator;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class DockerJavaOrchestrator implements ContainerOrchestrator {

    private static final String DEFAULT_IMAGE = "alpine:latest";

    private final DockerClient docker;

    public DockerJavaOrchestrator() {
        this.docker = DockerClientBuilder.getInstance().build();
    }

    public DockerJavaOrchestrator(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public String startContainer(String image, String command, ResourceLimits limits) {
        var effectiveImage = (image == null || image.isBlank()) ? DEFAULT_IMAGE : image;

        try {
            docker.pullImageCmd(effectiveImage)
                    .start()
                    .awaitCompletion(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OrchestratorException("Image pull interrupted: " + effectiveImage, e);
        } catch (Exception e) {
            throw new OrchestratorException("Failed to pull image: " + effectiveImage, e);
        }

        var hostConfig = new HostConfig();
        if (limits.memoryLimitMb() > 0) {
            hostConfig.withMemory(limits.memoryLimitMb() * 1024L * 1024L);
        }
        if (limits.cpuLimit() > 0) {
            hostConfig.withCpuShares((int) (limits.cpuLimit() * 1024));
        }

        var response = docker.createContainerCmd(effectiveImage)
                .withCmd("sh", "-c", command)
                .withHostConfig(hostConfig)
                .exec();

        docker.startContainerCmd(response.getId()).exec();
        return response.getId();
    }

    @Override
    public ContainerState inspectContainer(String containerId) {
        InspectContainerResponse response;
        try {
            response = docker.inspectContainerCmd(containerId).exec();
        } catch (NotFoundException e) {
            return new ContainerState(containerId, false, null, false, "Container not found");
        }

        var state = response.getState();
        var exitCode = state.getExitCodeLong();
        var oomKilled = state.getOOMKilled() != null && state.getOOMKilled();
        var error = state.getError();
        var systemError = (error != null && !error.isBlank()) ? error : null;

        return new ContainerState(
                containerId,
                Boolean.TRUE.equals(state.getRunning()),
                exitCode != null ? exitCode.intValue() : null,
                oomKilled,
                systemError
        );
    }

    @Override
    public List<String> tailLogs(String containerId, int lines) {
        var logs = new ArrayList<String>();
        var latch = new CountDownLatch(1);

        try {
            docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(lines)
                    .exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            logs.add(new String(frame.getPayload()).stripTrailing());
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                            super.onComplete();
                        }

                        @Override
                        public void onError(Throwable throwable) {
                            latch.countDown();
                            super.onError(throwable);
                        }
                    });
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return logs;
    }
}
