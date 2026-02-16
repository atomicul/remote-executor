package dev.executor.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingSubscriber implements JobEventListener {

    private static final Logger logger = LoggerFactory.getLogger(LoggingSubscriber.class);

    @Override
    public void onJobStateChanged(JobStateChanged event) {
        var status = event.current();
        switch (status.getResultCase()) {
            case RUNNING -> logger.info("Job {} is now RUNNING (started at {})",
                    event.jobId(), status.getRunning().getStartedAt());
            case COMPLETED -> logger.info("Job {} COMPLETED (exit code: {}, oom: {})",
                    event.jobId(), status.getCompleted().getExitCode(), status.getCompleted().getOomKilled());
            case SYSTEM_ERROR -> logger.info("Job {} SYSTEM_ERROR (reason: {}, message: {})",
                    event.jobId(), status.getSystemError().getReason(), status.getSystemError().getMessage());
            default -> logger.warn("Job {} in unknown state: {}", event.jobId(), status.getResultCase());
        }
    }
}
