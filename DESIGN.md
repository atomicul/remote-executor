# Remote Executor: Sidecar Process Design

## 1. Overview
The Remote Executor system is evolving from a single-node script runner into a distributed system. To facilitate this, we are introducing a **Sidecar Process**. 

The sidecar isolates infrastructure concerns—specifically, state persistence and instance lifecycle management—from the core execution logic of the main `server` application. By decoupling these responsibilities, the main executor remains a pure gRPC server focused entirely on container orchestration, while the sidecar handles AWS-specific integrations. Because integrating multiple external systems is risky, the sidecar features a CLI-configurable "Dry Run" mode to allow testing the gRPC integration independently of AWS services.

## 2. Core Responsibilities

The sidecar operates as a daemon running alongside the main executor on the same EC2 instance. It has two primary jobs:

### 2.1. State Synchronization (DynamoDB)
The ephemeral nature of executor instances means that job states must be persisted externally. 
* The sidecar continuously polls the main server over localhost gRPC to retrieve the status of all tracked jobs.
* It parses the `JobStatus` (whether the job is running, completed, or failed with a system error) and pushes these updates to an Amazon DynamoDB table.
* This allows clients and the future Scheduler control plane to query historical job states even after the executor instance has been terminated.

### 2.2. Instance Lifecycle Management (Self-Termination)
To optimize cloud costs, executor instances must not sit idle.
* The sidecar tracks the timestamp of the last active running job.
* If the instance remains inactive beyond a configured threshold (e.g., 15 minutes), the sidecar initiates a self-termination sequence.
* It uses the AWS Instance Metadata Service (IMDSv2) to identify its own Instance ID and calls the AWS EC2 API to terminate itself.

---

## 3. Phased Implementation Strategy

To reduce risk, the sidecar will be implemented in three distinct phases. Its behavior is controlled by command-line arguments: a `--dry-run` flag, followed by positional arguments for the `<host:port>` and `<service>`.

### Phase 0: Server Preparation (gRPC API Expansion)

* **Goal:** Equip the main gRPC server with the ability to report all tracked jobs, allowing the sidecar to discover which jobs it needs to poll.
* **Mechanism:** Update the shared Protobuf contract to include a `ListJobs` procedure and implement the endpoint in the main server logic. 
* **Required Contract Update (`common/src/main/proto/shell.proto`):**
    * Add the `ListJobs` RPC to the `ShellService`.
    * Define the empty `ListJobsRequest` message.
    * Define the `ListJobsResponse` message containing a repeated string of `job_ids`.
    
    ```protobuf
    service ShellService {
      rpc StartJob(CommandRequest) returns (JobResponse) {}
      rpc GetJobStatus(JobIdRequest) returns (JobStatus) {}
      rpc WatchJobLogs(JobIdRequest) returns (stream LogChunk) {}
      rpc ListJobs(ListJobsRequest) returns (ListJobsResponse) {} // New endpoint
    }
    
    // ... existing messages ...
    
    message ListJobsRequest {}
    
    message ListJobsResponse {
      repeated string job_ids = 1;
    }
    ```

* **Required Server Implementation (`server/src/main/java/dev/executor/server/ShellServiceImpl.java`):**
    * The `ShellServiceImpl` currently stores all active jobs in a `ConcurrentHashMap<String, String>` named `jobToContainer`.
    * Implement the newly generated `listJobs` base method to extract the keys (Job IDs) from this map and return them to the caller.
    
    ```java
    @Override
    public void listJobs(ListJobsRequest request, StreamObserver<ListJobsResponse> responseObserver) {
        try {
            var responseBuilder = ListJobsResponse.newBuilder();
            
            // jobToContainer is the existing ConcurrentHashMap tracking UUIDs to container IDs
            responseBuilder.addAllJobIds(jobToContainer.keySet());
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to list jobs: " + e.getMessage())
                    .asException());
        }
    }
    ```

### Phase 1: gRPC Integration, Smart Polling & Logging (Dry Run Mode)

* **Goal:** Verify that the sidecar can successfully connect, discover active jobs, poll the main server efficiently without overwhelming it, and detect state changes without touching AWS.
* **Mechanism:** When the `--dry-run` flag is passed, the sidecar skips all DynamoDB and EC2 API calls. Instead, it implements a stateful polling loop and logs state changes to standard output.

**The Polling Workflow:**
The sidecar operates on a scheduled executor thread, polling the server at regular intervals (e.g., every 10 seconds). To prevent overloading the server's underlying Docker daemon with expensive inspection calls, the sidecar implements a "discover, filter, fetch" pattern:

1. **Discover (`ListJobs`):** The sidecar calls the newly implemented `ListJobs` gRPC endpoint to retrieve the full list of `job_ids` currently tracked by the server.
2. **Filter via Local Cache:** The sidecar maintains an in-memory `ConcurrentHashMap<String, JobStatus>` to track known jobs. 
    * It compares the freshly fetched `job_ids` against this cache.
    * If a job is already in the cache and its last known state was terminal (either `completed` or `system_error`), the sidecar **skips it**. There is no need to query the server again.
    * If a job is new or its last known state was `running`, it is added to a "needs update" queue.
3. **Fetch Concurrently (`GetJobStatus`):** For each ID in the filtered queue, the sidecar calls the `GetJobStatus` endpoint. To maintain high throughput without blocking, these calls are executed concurrently using Java 21 Virtual Threads. Concurrency should be bounded (e.g., max 5-10 parallel requests) to protect the main server.
4. **Update State & Log:** * The local cache is updated with the newly fetched `JobStatus` records.
    * State transitions are evaluated. If a job just finished, the sidecar logs it to stdout (e.g., `[DRY RUN] Job 123 state changed to COMPLETED. Exit code: 0`).
    * **Idle Timer Management:** The sidecar checks the updated cache. If *any* jobs are currently in the `running` state, it resets its internal idle timer. If no jobs are running, the idle timer continues tracking inactivity for future self-termination.

### Phase 2: DynamoDB State Sync
* **Goal:** Persist the polled states to AWS.
* **Mechanism:** When running *without* the `--dry-run` flag, the sidecar maps the `JobStatus` into a strongly typed DynamoDB document and writes it to a `RemoteExecutor-JobState` table using the AWS SDK v2.

### Phase 3: EC2 Self-Termination
* **Goal:** Optimize costs by terminating idle nodes.
* **Mechanism:** If running without `--dry-run` and the calculated idle time exceeds the configured threshold, the sidecar fetches its Instance ID via IMDSv2 and calls `ec2Client.terminateInstances()`.

---

## 4. Implementation Details

### 4.1. Module Architecture
A new Gradle module named `sidecar` will be added alongside the `common` and `server` modules in `settings.gradle.kts`. 
* **Dependencies:** It will depend on the `:common` project for gRPC stubs. AWS SDK dependencies (`dynamodb`, `ec2`, `imds`) will be added but their execution will be bypassed when `--dry-run` is present.
* **Execution:** The application's `main` method will parse the CLI arguments:
  `sidecar [--dry-run] <host:port> <service>`
  It will then start a scheduled executor thread (e.g., every 10 seconds) to perform the polling loop.

### 4.2. Data Model (DynamoDB)
The sidecar persists job states to a DynamoDB table named `RemoteExecutor-JobState`. To closely mirror the `JobStatus` protobuf message and its `oneof result` construct, the schema utilizes DynamoDB's Document (Map) types. This prevents sparse items with unused columns and keeps the data structure strongly typed.

* **Partition Key:** `JobId` (String) - The UUID generated by the server.

**Top-Level Item Attributes:**
* `JobId` (String): The unique identifier for the job.
* `InstanceId` (String): The EC2 Instance ID of the worker node.
* `UpdatedAt` (Number): Epoch timestamp of the sidecar's last write.
* `TTL` (Number): Optional epoch timestamp for automatic record deletion.
* `Result` (Map): **The Union Type.** Contains exactly *one* of the sub-maps below, mirroring the `oneof` block in `shell.proto`.

**The `Result` Union (Mutually Exclusive Sub-Maps):**

**1. When running (maps to `RunningDetails`):**
```json
"Result": {
  "Running": {
    "StartedAt": "2026-02-15T14:30:00Z"
  }
}
```

**2. When completed (maps to `CompletionDetails`):**
```json
"Result": {
  "Completed": {
    "ExitCode": 0,
    "OomKilled": false,
    "RecentLogs": ["Executing command...", "Done."]
  }
}
```

**3. When system_error (maps to `FailureDetails`):**
```json
"Result": {
  "SystemError": {
    "Reason": "SYSTEM_ERROR",
    "Message": "Container not found"
  }
}
```

---

## 5. Deployment & Infrastructure

### 5.1. Layered AMI Building (EC2 Image Builder)
We will utilize a Golden Image pattern in our CI/CD pipeline defined in `iac/pipeline.yaml`. 
* **Base Image:** The existing pipeline builds the main `server` AMI.
* **Sidecar Image:** A newly introduced EC2 Image Builder pipeline will use the `server` AMI as its `ParentImage` (using the `x.x.x` semantic versioning wildcard to always grab the latest). 
* This prevents lengthy, monolithic builds and allows independent patching of the sidecar logic.

### 5.2. Process Management (Systemd)
The sidecar will be installed as a dedicated `systemd` service (`remote-executor-sidecar.service`). It will be configured with a strict dependency on the main server, passing the expected localhost target and the target service:
```ini
After=remote-executor.service
Requires=remote-executor.service

[Service]
ExecStart=/opt/sidecar/bin/sidecar localhost:9090 dev.executor.common.ShellService
Restart=always
User=root
```
*(Note: Infrastructure provisioning for DynamoDB and IAM roles will be deferred until Phases 2 and 3. The `--dry-run` flag can be temporarily injected into the `ExecStart` command during initial staging deployments).*

### 5.3. Required IAM Permissions
Once AWS integrations are enabled, the EC2 Instance Profile (`ImageBuilderInstanceRole`) attached to the executor instances must be updated to grant the sidecar its necessary runtime permissions:
* `dynamodb:PutItem` on the designated job state table.
* `ec2:TerminateInstances` (Condition-restricted to the instance's own ID to prevent accidental termination of other fleet nodes).

---

## 6 Testing
**Dry Run Logic:** When `--dry-run` is passed in the args array, the AWS SDK clients are never instantiated or invoked, and output is routed strictly to the logger.

---

## 6. AI Implementation Plan (Commit-by-Commit Breakdown)

To ensure a smooth, iterative implementation, the development of the Sidecar Process should be executed in the following distinct steps. When assigning these tasks to an AI assistant, provide the context of the current phase and ask for the specific commit deliverables only.

### Commit 1: Project Setup & gRPC Contract (Phase 0)
* **Goal:** Establish the new module and update the shared communication contract.
* **Tasks:**
  * Update `settings.gradle.kts` to include the new `:sidecar` module.
  * Create the basic directory structure for the `sidecar` module (e.g., `build.gradle.kts` with standard Java setup and a dependency on `:common`).
  * Modify `common/src/main/proto/shell.proto` to add the `ListJobs` RPC, `ListJobsRequest`, and `ListJobsResponse` messages.
  * Run the Gradle build to generate the updated gRPC stubs.

### Commit 2: Server Implementation (Phase 0)
* **Goal:** Equip the main gRPC server to handle the new `ListJobs` request.
* **Tasks:**
  * Open `ShellServiceImpl.java` in the `:server` module.
  * Override and implement the `listJobs` method.
  * Extract the keys from the existing `jobToContainer` `ConcurrentHashMap` and return them in the `ListJobsResponse`.
  * Add a basic unit test to verify the endpoint returns the expected active job IDs.

### Commit 3: Sidecar CLI Skeleton & Logging (Phase 1)
* **Goal:** Scaffold the sidecar application and implement CLI argument parsing.
* **Tasks:**
  * Create `Main.java` in the `:sidecar` module.
  * Implement standard Java argument parsing to extract the optional `--dry-run` flag, `<host:port>`, and `<service>` arguments.
  * Set up a basic structured logging configuration (e.g., SLF4J/Logback).
  * Add an initialization log line confirming the startup mode (Dry Run vs. Production) and the target server address.

### Commit 4: The Polling Engine - Dry Run (Phase 1)
* **Goal:** Implement the "Discover, Filter, Fetch" pattern without any AWS dependencies.
* **Tasks:**
  * Create a `PollingEngine` class utilizing a `ScheduledExecutorService` to tick every 10 seconds.
  * Initialize a `ConcurrentHashMap<String, JobStatus>` to act as the local cache.
  * **Discover:** Implement the gRPC call to `ListJobs`.
  * **Filter:** Write the logic to compare fetched IDs against the local cache, skipping jobs already in terminal states (`completed` or `system_error`).
  * **Fetch:** Use Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`) to concurrently call `GetJobStatus` for the filtered jobs (bounded concurrency).
  * **Log:** Update the cache and log any state transitions to `stdout`.

### Commit 5: DynamoDB State Sync (Phase 2)
* **Goal:** Map the internal state to DynamoDB documents and push updates.
* **Tasks:**
  * Add the AWS SDK v2 DynamoDB dependencies to the `:sidecar` module.
  * Create a `DynamoDbStatePersister` class.
  * Implement the data mapping logic to translate the `JobStatus` protobuf into the strongly-typed DynamoDB Document structure defined in the design (handling the Union types for `Running`, `Completed`, and `SystemError`).
  * Update the `PollingEngine` to conditionally call the persister if `--dry-run` is absent.

### Commit 6: EC2 Instance Lifecycle Management (Phase 3)
* **Goal:** Implement the idle timer and self-termination logic.
* **Tasks:**
  * Add AWS SDK v2 EC2 and IMDS dependencies to the `:sidecar` module.
  * Update the `PollingEngine` to track the timestamp of the last observed `running` job.
  * Create an `InstanceTerminator` class.
  * Implement the logic to fetch the local Instance ID via IMDSv2.
  * Implement the `ec2Client.terminateInstances()` call.
  * Integrate the terminator into the polling loop, triggering only if the idle threshold is exceeded and `--dry-run` is absent.

### Commit 7: Systemd Configuration & Deployment Scripts
* **Goal:** Finalize the operational deployment files.
* **Tasks:**
  * Create the `remote-executor-sidecar.service` systemd unit file with the `After` and `Requires` directives pointing to the main server.
  * Update any relevant infrastructure-as-code (IaC) or AMI build scripts to include the new sidecar binary and service file.
