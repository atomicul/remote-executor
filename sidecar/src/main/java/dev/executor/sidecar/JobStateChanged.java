package dev.executor.sidecar;

import dev.executor.common.JobStatus;

public record JobStateChanged(String jobId, JobStatus previous, JobStatus current) {}
