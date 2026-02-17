# Remote Executor: Scheduler Service Design

## 1. Overview
The Remote Executor system is transitioning from a single-node architecture into a distributed, auto-scaling execution platform. To achieve this, we are introducing the **Scheduler Process** as the central control plane, and renaming the existing `server` module to `executor` for clarity. 

Currently, clients connect directly to a single server instance to execute commands. The new `scheduler` will act as a stateful gRPC proxy and load balancer. It will accept incoming client requests, monitor the real-time capacity of the executor fleet using DynamoDB, route jobs to available nodes, and dynamically provision new AWS EC2 instances when the fleet reaches its maximum concurrency limits.

## 2. Core Responsibilities

The Scheduler operates as a highly available control plane with three primary responsibilities:

### 2.1. Fleet Capacity Management & Scale-Out (EC2)
The scheduler acts as the auto-scaler for the executor fleet. 
* It reads two critical environment variables: `EXECUTOR_INSTANCE_TYPE` (the AWS EC2 instance class to launch) and `MAX_RUNNING_JOBS_PER_INSTANCE` (the concurrency limit per node).
* Before scheduling a job, it evaluates the current workload of all active instances. 
* If all instances are at or above `MAX_RUNNING_JOBS_PER_INSTANCE`, the scheduler will use the AWS EC2 SDK to launch a new worker node, wait for it to report as healthy, and then assign the job.
* *Note: Scale-in (node termination) is entirely decentralized. The existing `sidecar` process already handles self-termination when an instance is idle*. 

### 2.2. Intelligent gRPC Proxying
The scheduler implements the exact same `ShellService` Protobuf contract (`shell.proto`) as the executors. 
* Clients connect to the Scheduler instead of individual workers.
* The scheduler parses incoming `StartJob` or `WatchJobLogs` requests and acts as a gRPC reverse proxy, establishing a channel to the specific downstream executor's IP address and streaming the bytes back to the client.

### 2.3. Global State Aggregation
Because executor instances are ephemeral, the scheduler relies on the `RemoteExecutor-JobState` DynamoDB table as the source of truth. 
* When a client calls `GetJobStatus` or `ListJobs`, the scheduler queries DynamoDB rather than pinging individual workers. This ensures historical job data is available long after the worker instance has been destroyed.

---

## 3. Architecture & Mechanics

### 3.1. Infrastructure & Module Adjustments
* **Module Renaming:** The current `server` module will be renamed to `executor`. All references in `settings.gradle.kts`, `build.gradle.kts`, and deployment scripts will be updated.
* **New Module:** A new `scheduler` module will be created alongside `common`, `executor`, and `sidecar` in `settings.gradle.kts`.

### 3.2. Data Model Additions (DynamoDB GSI)
To effectively balance loads, the scheduler needs to know how many `RUNNING` jobs each instance has. Scanning the entire `RemoteExecutor-JobState` table is inefficient. 
* **Required Infrastructure Change:** We will update `iac/dynamodb.yaml` to add a Global Secondary Index (GSI) named `ActiveInstancesIndex`.
* **State Extraction:** The sidecar will be updated to persist a top-level string attribute called `JobState` (e.g., `RUNNING`, `COMPLETED`, `SYSTEM_ERROR`) alongside the existing `Result` map.
* **GSI Schema:** Partition Key = `InstanceId` (String), **Sort Key = `JobState` (String)**. This allows the scheduler to efficiently execute targeted queries to count only active jobs (e.g., `InstanceId = X AND JobState = RUNNING`) without fetching or filtering out completed/failed jobs.

### 3.3. Job Routing Workflow (`StartJob`)
1. **Capacity Check:** The scheduler queries the DynamoDB GSI using the `JobState` sort key to map `InstanceId` -> `Count(JobState == RUNNING)`.
2. **Select Node:** It filters for instances where the count is `< MAX_RUNNING_JOBS_PER_INSTANCE`. 
3. **Scale Out (If Necessary):** If no instances are available, it calls `ec2Client.runInstances()`, using a pre-configured Launch Template (which includes the executor AMI built by the CI/CD pipeline) and overrides the instance type with `EXECUTOR_INSTANCE_TYPE`. It polls until the new instance passes status checks.
4. **IP Resolution:** It calls `ec2Client.describeInstances()` to resolve the private/public IP of the selected `InstanceId`.
5. **Proxy Request:** It opens a gRPC channel to `IP:9090` (the standard executor port) and forwards the `StartJob` payload.

### 3.4. Log Streaming Workflow (`WatchJobLogs`)
1. **State Lookup:** The scheduler looks up the `JobId` in DynamoDB.
2. **Validation:** If the job is `COMPLETED`, it simply returns the `RecentLogs` array from DynamoDB as a single chunk and closes the stream.
3. **Stream Proxy:** If the job is `RUNNING`, it extracts the `InstanceId`, resolves its IP, and establishes a proxy stream to the executor's `WatchJobLogs` endpoint.

---

## 4. Configuration

The scheduler requires the following environment variables to operate:

| Variable | Description | Default / Example |
| :--- | :--- | :--- |
| `EXECUTOR_INSTANCE_TYPE` | The AWS EC2 instance type to launch when capacity is exhausted. | `t3.micro` |
| `MAX_RUNNING_JOBS_PER_INSTANCE` | The maximum number of concurrent jobs a single executor should handle. | `5` |
| `LAUNCH_TEMPLATE_ID` | The AWS Launch Template populated with the latest Executor/Sidecar AMI. | `lt-0abcd1234efgh5678` |

---

## 5. Phased Implementation Strategy

### Phase 1: Preparation & Refactoring
Rename the `server` module to `executor`. This is a mechanical change updating directories, Gradle build files, and references in the existing `buildspec.yml` and `pipeline.yaml` scripts.

### Phase 2: DynamoDB Enhancements
Update the sidecar's `DynamoDbStatePersister` to write a top-level `JobState` string attribute. Update the CloudFormation stack in `iac/dynamodb.yaml` to include the GSI using `InstanceId` as the Partition Key and `JobState` as the Sort Key. Update the IAM roles in `iac/executor.yaml` and `iac/pipeline.yaml` to give the future Scheduler permission to `ec2:RunInstances` and `dynamodb:Query`.

### Phase 3: The Scheduler Proxy
Implement the new `scheduler` module. Begin by implementing the read-only endpoints (`GetJobStatus`, `ListJobs`) which only require reading from the DynamoDB table populated by the sidecar. Next, implement the proxy layer that forwards `StartJob` and `WatchJobLogs` to hardcoded downstream IPs.

### Phase 4: Dynamic Provisioning (Auto-Scaling)
Integrate the AWS EC2 SDK. Replace the hardcoded downstream IPs with the dynamic capacity-checking logic leveraging the GSI's sort key efficiency. Implement the scale-out trigger utilizing `EXECUTOR_INSTANCE_TYPE` and `MAX_RUNNING_JOBS_PER_INSTANCE`.

---

## 6. AI Implementation Plan (Commit-by-Commit Breakdown)

To execute this architecture, implement the following steps iteratively:

### Commit 1: Rename Server to Executor (Phase 1)
* **Goal:** Update the nomenclature across the repository to reflect the new architecture.
* **Tasks:**
    * Rename the `server/` directory to `executor/`.
    * Update `settings.gradle.kts` to include `executor` instead of `server`.
    * Update `build.gradle.kts` inside the renamed module.
    * Update `iac/pipeline.yaml`, `scripts/bootstrap-aws-account.sh`, and `scripts/test-server.sh` to reflect the new binary and folder names.

### Commit 2: Initialize Scheduler Module & GSI (Phase 2)
* **Goal:** Scaffold the new module and update IaC/Sidecar for the required index.
* **Tasks:**
    * Add `include("scheduler")` to `settings.gradle.kts`.
    * Create the `scheduler` module with its `build.gradle.kts` containing gRPC and AWS SDK (EC2, DynamoDB) dependencies.
    * Update `DynamoDbStatePersister.java` in the sidecar module to save the `JobState` as a top-level string attribute.
    * Update `iac/dynamodb.yaml` to add a Global Secondary Index (`ActiveInstancesIndex`) using `InstanceId` as the Partition Key and `JobState` as the Sort Key.

### Commit 3: Scheduler Read API via DynamoDB (Phase 3)
* **Goal:** Implement the state-querying endpoints without proxying.
* **Tasks:**
    * Create `SchedulerServiceImpl.java` extending `ShellServiceImplBase`.
    * Implement `getJobStatus` and `listJobs` to read directly from the `RemoteExecutor-JobState` DynamoDB table using the AWS SDK v2, mapping the Document results back to the `JobStatus` protobuf messages.

### Commit 4: Fleet Capacity & Routing Engine (Phase 4)
* **Goal:** Create the logic to track load and pick targets using the new GSI.
* **Tasks:**
    * Create an `InstanceRegistry` class in the `scheduler`.
    * Implement a method to query the DynamoDB GSI (`ActiveInstancesIndex`) using the sort key (`JobState = RUNNING`) to map available `InstanceId`s to their active job count.
    * Implement a method resolving `InstanceId` to IP addresses via `Ec2Client.describeInstances()`.

### Commit 5: EC2 Scale-Out Logic (Phase 4)
* **Goal:** Allow the scheduler to launch nodes when capacity is maxed out.
* **Tasks:**
    * Update `Main.java` in the scheduler to parse the `EXECUTOR_INSTANCE_TYPE`, `MAX_RUNNING_JOBS_PER_INSTANCE`, and `LAUNCH_TEMPLATE_ID` variables.
    * Add logic inside the `InstanceRegistry`: if no node has `< MAX_RUNNING_JOBS` capacity, use `Ec2Client.runInstances()` to start a new worker. Wait for the `InstanceRunning` state before yielding the new IP.

### Commit 6: Proxy `StartJob` and `WatchJobLogs` (Phase 4)
* **Goal:** Connect the client traffic through the scheduler to the chosen worker.
* **Tasks:**
    * Implement `startJob` in `SchedulerServiceImpl`. Call the `InstanceRegistry` to get a target IP, establish a temporary gRPC `ManagedChannel`, forward the `CommandRequest`, and return the resulting `JobId`.
    * Implement `watchJobLogs`. Query DynamoDB for the `InstanceId` of the job. If running, resolve the IP, open a channel, and proxy the `LogChunk` stream back to the client.
