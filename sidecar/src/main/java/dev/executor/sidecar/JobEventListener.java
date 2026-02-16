package dev.executor.sidecar;

@FunctionalInterface
public interface JobEventListener {
    void onJobStateChanged(JobStateChanged event);
}
