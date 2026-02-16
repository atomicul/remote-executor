package dev.executor.sidecar;

import dev.executor.common.JobIdRequest;
import dev.executor.common.JobStatus;
import dev.executor.common.ListJobsRequest;
import dev.executor.common.ShellServiceGrpc.ShellServiceBlockingStub;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PollingEngine {

    private static final Logger logger = LoggerFactory.getLogger(PollingEngine.class);
    private static final int POLL_INTERVAL_SECONDS = 10;
    private static final int MAX_CONCURRENT_FETCHES = 5;

    private final ShellServiceBlockingStub stub;
    private final ConcurrentHashMap<String, JobStatus> cache = new ConcurrentHashMap<>();
    private final List<JobEventListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile Instant lastActiveTime = Instant.now();

    public PollingEngine(ShellServiceBlockingStub stub) {
        this.stub = stub;
    }

    public void addListener(JobEventListener listener) {
        listeners.add(listener);
    }

    public void start() {
        logger.info("Polling engine started, interval={}s", POLL_INTERVAL_SECONDS);
        scheduler.scheduleAtFixedRate(this::poll, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
        logger.info("Running final poll before shutdown");
        poll();
    }

    public Instant getLastActiveTime() {
        return lastActiveTime;
    }

    ConcurrentHashMap<String, JobStatus> getCache() {
        return cache;
    }

    void poll() {
        try {
            List<String> jobIds = discover();
            List<String> toFetch = filter(jobIds);

            if (!toFetch.isEmpty()) {
                fetchAndUpdate(toFetch);
            }

            updateIdleTimer();
        } catch (Exception e) {
            logger.error("Polling cycle failed", e);
        }
    }

    private List<String> discover() {
        var response = stub.listJobs(ListJobsRequest.getDefaultInstance());
        var ids = response.getJobIdsList();
        logger.debug("Discovered {} job(s)", ids.size());
        return ids;
    }

    private List<String> filter(List<String> jobIds) {
        return jobIds.stream()
                .filter(id -> {
                    var cached = cache.get(id);
                    if (cached == null) return true;
                    var state = cached.getResultCase();
                    return state != JobStatus.ResultCase.COMPLETED
                            && state != JobStatus.ResultCase.SYSTEM_ERROR;
                })
                .toList();
    }

    private void fetchAndUpdate(List<String> jobIds) {
        var semaphore = new Semaphore(MAX_CONCURRENT_FETCHES);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String jobId : jobIds) {
                executor.submit(() -> {
                    semaphore.acquire();
                    try {
                        var request = JobIdRequest.newBuilder().setJobId(jobId).build();
                        var status = stub.getJobStatus(request);
                        var previous = cache.put(jobId, status);
                        emitIfChanged(jobId, previous, status);
                    } finally {
                        semaphore.release();
                    }
                    return null;
                });
            }
        }
    }

    private void emitIfChanged(String jobId, JobStatus previous, JobStatus current) {
        var previousState = previous != null ? previous.getResultCase() : null;
        if (previousState == current.getResultCase()) return;

        var event = new JobStateChanged(jobId, previous, current);
        for (var listener : listeners) {
            try {
                listener.onJobStateChanged(event);
            } catch (Exception e) {
                logger.error("Listener failed for job {}", jobId, e);
            }
        }
    }

    private void updateIdleTimer() {
        boolean anyRunning = cache.values().stream()
                .anyMatch(s -> s.getResultCase() == JobStatus.ResultCase.RUNNING);

        if (anyRunning) {
            lastActiveTime = Instant.now();
        }
    }
}
