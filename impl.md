# Implementation Guide: Rebuilding raft-kv-java From Scratch

This guide is a blueprint to help you rebuild this distributed key-value store in a new project. It details the exact sequence of steps, dependencies, code layouts, and how to link the consensus engine with the network layers.

---

## Phase 0: Project Setup & Build Config

### Step 1: Create the Module Directories
In a new directory, create the following folder structure:
```bash
mkdir raft-core raft-rpc raft-server raft-client
mkdir -p raft-core/src/main/java/raftkv/core
mkdir -p raft-rpc/src/main/proto
mkdir -p raft-rpc/src/main/java/raftkv/rpc
mkdir -p raft-server/src/main/java/raftkv/server
mkdir -p raft-client/src/main/java/raftkv/client
```

### Step 2: Configure Gradle Settings
Create `settings.gradle` at the root to bind the modules together:
```groovy
rootProject.name = 'raft-kv-java'
include 'raft-core'
include 'raft-rpc'
include 'raft-server'
include 'raft-client'
```

### Step 3: Write the Root build.gradle
Create `build.gradle` at the root. Configure the Java version, repositories, and share default logging/testing libraries across all modules:
```groovy
plugins {
    id 'java'
    id 'com.google.protobuf' version '0.9.4' apply false
}

allprojects {
    group = 'raftkv'
    version = '1.0-SNAPSHOT'
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'
    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
    dependencies {
        implementation 'org.slf4j:slf4j-api:2.0.12'
        implementation 'ch.qos.logback:logback-classic:1.5.3'
        testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
        testImplementation 'org.awaitility:awaitility:4.2.0'
    }
    test {
        useJUnitPlatform()
    }
}
```

### Step 4: Configure Module build.gradle Files
1. **`raft-core/build.gradle`**:
   ```groovy
   dependencies {
       // Core is network-free. No additional dependencies.
   }
   ```
2. **`raft-rpc/build.gradle`** (Needs the Protobuf compiler plugin):
   ```groovy
   plugins {
       id 'com.google.protobuf'
   }
   dependencies {
       implementation project(':raft-core')
       implementation 'io.grpc:grpc-netty-shaded:1.62.2'
       implementation 'io.grpc:grpc-protobuf:1.62.2'
       implementation 'io.grpc:grpc-stub:1.62.2'
       compileOnly 'org.apache.tomcat:annotations-api:6.0.53'
   }
   protobuf {
       protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
       plugins { grpc { artifact = "io.grpc:protoc-gen-grpc-java:1.62.2" } }
       generateProtoTasks { all().each { it.plugins { grpc {} } } }
   }
   sourceSets {
       main { java { srcDirs 'build/generated/source/proto/main/grpc', 'build/generated/source/proto/main/java' } }
   }
   ```
3. **`raft-server/build.gradle`**:
   ```groovy
   plugins { id 'application' }
   dependencies {
       implementation project(':raft-core')
       implementation project(':raft-rpc')
       implementation 'io.grpc:grpc-netty-shaded:1.62.2'
       implementation 'io.grpc:grpc-protobuf:1.62.2'
       implementation 'io.grpc:grpc-stub:1.62.2'
   }
   application { mainClass = 'raftkv.server.Main' }
   ```
4. **`raft-client/build.gradle`**:
   ```groovy
   plugins { id 'application' }
   dependencies {
       implementation project(':raft-rpc')
       implementation 'io.grpc:grpc-netty-shaded:1.62.2'
       implementation 'io.grpc:grpc-protobuf:1.62.2'
       implementation 'io.grpc:grpc-stub:1.62.2'
   }
   application { mainClass = 'raftkv.client.RaftCliClient' }
   ```

---

## Phase 1: Define Network Service Contracts (Protobuf)

Create [`raft.proto`](file:///C:/Users/suhai/Desktop/kv-claude/raft-rpc/src/main/proto/raft.proto) at `raft-rpc/src/main/proto/raft.proto`.
* Define messages for `VoteRequest`/`Response` and `AppendEntriesRequest`/`Response`.
* Define `RaftService` with `RequestVote` and `AppendEntries` RPC endpoints.
* Define `KVService` with `Put` and `Get` client endpoints.
* **Verify**: Run `./gradlew :raft-rpc:generateProto` to check if Protobuf compiles and generates Java stubs.

---

## Phase 2: Code the Core Consensus Engine (`raft-core`)

Write the core consensus logic, ensuring **no dependencies on gRPC or network classes**.

### Step 1: Base State Models
1. **`NodeState.java`**: Enum with `FOLLOWER`, `CANDIDATE`, `LEADER`.
2. **`LogEntry.java`**: A Java `record` containing `term` (long), `index` (long), and `command` (String).

### Step 2: The Replicated Log & Persistent Disk Store
1. **`PersistentState.java`**:
   * Constructor accepts a `dataDir` path.
   * Open/Read from `metadata.state` (stores `term`, `votedFor`) and `raft.log` (stores log lines).
   * **Atomic Writes**: Save state using temp files, then call `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` to prevent file corruption during power failures.
   * Parse log lines using `term:index:base64(command)`.
2. **`RaftLog.java`**:
   * Wraps an `ArrayList<LogEntry>`. Index 0 contains a dummy entry.
   * Connect it to `PersistentState` so every memory append/truncate is mirrored to the disk file immediately.

### Step 3: Timers & Network Interfaces
1. **`ElectionTimer.java`**: Schedules a randomized task callback (using Java `ScheduledExecutorService` and `java.util.Random` for 300–600ms delays).
2. **`RaftNodeListener.java`**: Interface specifying callbacks like `sendRequestVote()`, `sendAppendEntries()`, `onCommit()`, and `onStateChange()`.

### Step 4: Define Core Request/Response Records
Create local Java records representing network parameters:
* `VoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm)`
* `VoteResponse(long term, boolean voteGranted)`
* `AppendEntriesRequest(long term, int leaderId, long prevLogIndex, long prevLogTerm, List<LogEntry> entries, long leaderCommit)`
* `AppendEntriesResponse(long term, boolean success, long matchIndex)`

### Step 5: The Consensus Engine (`RaftNode.java`)
Combine logs, persistent state, election timers, and role states inside a thread-safe coordinator:
* **Concurrency**: Guard state changes using a `ReentrantLock`.
* **State Recovery**: On construction, if `PersistentState` is present, initialize `currentTerm`, `votedFor`, and `log` from it.
* **RPC Handlers**: Write logic for `handleRequestVote` and `handleAppendEntries` following the rules in Figure 2 of the Raft paper.
* **RPC Response Callbacks**: Write logic for `handleRequestVoteResponse` (counts votes, transitions role to Leader) and `handleAppendEntriesResponse` (advances Leader's commit index or backs off `nextIndex` on conflicts).
* **Verify**: Write a unit test ([`RaftNodeTest.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/test/java/raftkv/core/RaftNodeTest.java)) to verify that a single node starts, times out, and elects itself leader. Run with `./gradlew :raft-core:test`.

---

## Phase 3: Wire Network Services (`raft-rpc` & `raft-server`)

### Step 1: Peer Client Stubs
Create `PeerClient.java` in `raft-server`.
* Wraps `ManagedChannel` and `RaftServiceGrpc.RaftServiceStub`.
* Exposes methods to send non-blocking `RequestVote` and `AppendEntries` requests over gRPC.

### Step 2: Implement gRPC Services
1. **`RaftServiceImpl.java`** in `raft-rpc`:
   * Extends the protobuf-generated base impl class.
   * Maps incoming Protobuf RPC parameters into `raft-core` Java records.
   * Calls `raftNode.handleRequestVote` or `raftNode.handleAppendEntries` and returns the converted response.
2. **`KVServiceImpl.java`** in `raft-rpc`:
   * Manages client writes using a `ConcurrentHashMap` of pending writes (`Map<Long, CompletableFuture<Boolean>>`).
   * When a client `Put` request arrives, call `raftNode.clientWrite(command)`, register a future on the returned index, and block on `future.get(5, TimeUnit.SECONDS)`.
   * When the commit listener notifies that an index is committed, resolve the future to return success to the client.

### Step 3: Cluster Configuration Loader
Create `ClusterConfig.java` in `raft-server` to load environments:
* Parses `NODE_ID` (int) and `NODE_PORT` (int).
* Parses peer maps from comma-separated `nodeId=host:port` string.
* Sets default timeouts and directories.

### Step 4: Orchestrate Node Startup
Create `Main.java` in `raft-server`.
1. Load config and set SLF4J MDC context (`MDC.put("nodeId", id)`).
2. Instantiate `PersistentState` and `PeerClient` channels.
3. Implement `RaftNodeListener`:
   * **`sendRequestVote` / `sendAppendEntries`**: Translate parameters to protobuf messages, look up the target `PeerClient`, and make the gRPC network call.
   * **`onCommit`**: Parse command (e.g., `PUT key value`). Write to the local state machine database map (`ConcurrentHashMap`), then call `kvService.notifyCommit(logIndex, true)`.
   * **`onStateChange`**: If the node steps down or leadership transitions, abort all pending client futures.
4. Bind `RaftServiceImpl` and `KVServiceImpl` on the configured port and start the gRPC server.
5. Boot the `RaftNode`.

---

## Phase 4: Create the Client CLI (`raft-client`)

Create `RaftCliClient.java` in `raft-client`.
* Reads the cluster servers list (e.g. `1=localhost:8001,2=localhost:8002`).
* Performs GET/PUT requests.
* **Redirection Loop**: If the response is unsuccessful and returns a `leaderId`, the client automatically reconnects to that leader's host address and retries the command.

---

## Phase 5: Containerize (Docker)

### Step 1: Write docker/Dockerfile
```dockerfile
FROM gradle:8.5-jdk21 AS builder
WORKDIR /source
COPY --chown=gradle:gradle . /source
RUN gradle :raft-server:installDist :raft-client:installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /source/raft-server/build/install/raft-server /app/server
COPY --from=builder /source/raft-client/build/install/raft-client /app/client
EXPOSE 8001
ENTRYPOINT ["/app/server/bin/raft-server"]
```

### Step 2: Write docker/docker-compose.yml
Define service nodes (`node-1`, `node-2`, `node-3`), ports mapping (`8001:8001`, `8002:8001`, `8003:8001`), and environment parameters pointing container peers to hostnames:
* Node 1 peers: `PEERS=2=node-2:8001,3=node-3:8001`
* Mount volumes to `/app/data` to test persistent recovery.
* Run `docker-compose -f docker/docker-compose.yml up --build` to launch your cluster!
