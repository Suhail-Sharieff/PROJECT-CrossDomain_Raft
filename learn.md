
### What is Gradle?
**Gradle** is a build tool. It acts as the manager of your project. 
* It reads configuration files (named `build.gradle` and `settings.gradle`) to know what libraries your project needs.
* It automatically downloads those libraries from the internet (from a central repository called Maven Central) and adds them to your classpath.
* It handles compilation of your code in the correct order.

---

## 2. Directory Structure Explained

Here is what all the directories and files in your workspace mean:

```text
raft-kv-java/
│
├── .gradle-dist/                  # Local copy of Gradle downloaded to run the project.
│
├── raft-core/                     # MODULE 1: The core Raft algorithm logic.
│   ├── src/main/java/raftkv/core/ # Core Java source files.
│   └── src/test/java/raftkv/core/ # Unit tests to verify the core logic.
│
├── raft-rpc/                      # MODULE 2: Networking contracts.
│   ├── src/main/proto/            # Protocol Buffer schema definition (raft.proto).
│   └── src/main/java/raftkv/rpc/  # gRPC service implementation handlers.
│
├── raft-server/                   # MODULE 3: Node runtime (starts gRPC and hooks up raft-core).
│   └── src/main/java/raftkv/server/
│
├── raft-client/                   # MODULE 4: Simple CLI client to write/read keys.
│   └── src/main/java/raftkv/client/
│
├── docker/                        # Containerization configs to run multiple nodes easily.
│
├── settings.gradle                # Defines which modules exist in the project.
└── build.gradle                   # Root Gradle build file containing shared project settings.
```

---

## 3. Step-by-Step Study Plan

To understand the project, read the files in the following order:

### Step 1: Understand the Data Structures & Models
* **File:** [`LogEntry.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/LogEntry.java)
  * *What it is:* A simple Java 21 `record`. Unlike a standard class, a record is a concise way to create immutable data structures. It stores the `term`, `index`, and the `command` (e.g. `"PUT key value"`).
* **File:** [`NodeState.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/NodeState.java)
  * *What it is:* An enum representing the three states a Raft node can be in: `FOLLOWER`, `CANDIDATE`, or `LEADER`.

### Step 2: Understand the Log Structure
* **File:** [`RaftLog.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftLog.java)
  * *What it is:* An in-memory list (`ArrayList`) wrapper that manages the logs. 
  * *DSA Connection:* It is 1-indexed. To make life easy, index `0` is pre-populated with a dummy entry. It handles appending and truncating conflicting log entries.

### Step 3: Understand how Timers Work
* **File:** [`ElectionTimer.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/ElectionTimer.java)
  * *What it is:* Uses Java's `ScheduledExecutorService` (which is like a thread pool that can run tasks after a delay) to schedule callbacks when a node doesn't hear from the leader.
  * *Note:* It uses `java.util.Random` to pick a timeout between a minimum and maximum range (e.g. 300ms to 600ms) to prevent split-vote situations.

### Step 4: The Core State Machine (The Meat of Raft)
* **File:** [`RaftNode.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftNode.java)
  * *What it is:* This is the largest and most important file. It contains the logic for what a node does when:
    * Its election timer expires (`onElectionTimeout`).
    * It receives a RequestVote RPC (`handleRequestVote`).
    * It receives a heart-beat/log sync RPC (`handleAppendEntries`).
  * *Note:* Read how `ReentrantLock` is used here. Because multiple threads can access a node's state simultaneously (timers, incoming RPC handlers, client calls), we guard all read/write operations using `lock.lock()` and `lock.unlock()` in a `try-finally` block.

### Step 5: How State Survives Crashes
* **File:** [`PersistentState.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/PersistentState.java)
  * *What it is:* Writes metadata (`currentTerm`, `votedFor`) and log entries to a local text file.
  * *DSA Connection:* It uses Base64 encoding (`java.util.Base64`) to convert commands into safe strings without spaces or newlines, so we can write them line-by-line to a text file and read them back easily.

### Step 6: The Communication Layer (gRPC & Protobuf)
* **File:** [`raft.proto`](file:///C:/Users/suhai/Desktop/kv-claude/raft-rpc/src/main/proto/raft.proto)
  * *What it is:* **Protocol Buffers** is a tool to define network messages and services in a language-neutral format. 
  * *How it works:* We define our messages (like `VoteRequest` and `VoteResponse`) and services here. The Gradle protobuf plugin automatically compiles this file into actual Java classes (`VoteRequest.java`, `RaftServiceGrpc.java`, etc.) inside the `build/` directory so we can use them in our code.
* **File:** [`RaftServiceImpl.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-rpc/src/main/java/raftkv/rpc/RaftServiceImpl.java)
  * *What it is:* Translates gRPC network messages into our core `raft-core` Java records, calls the core methods in `RaftNode`, and translates the response back.

### Step 7: Starting up the Servers and Client
* **File:** [`Main.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-server/src/main/java/raftkv/server/Main.java)
  * *What it is:* Sets up logging, opens a gRPC port, initializes peer connections, and boots the local gRPC server.
* **File:** [`RaftCliClient.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-client/src/main/java/raftkv/client/RaftCliClient.java)
  * *What it is:* The command line app to test writing and reading key-values.

---

## 4. Key Java Concurrency Concepts Used Here

Because Raft runs asynchronously across threads, you will encounter classes you don't use in standard DSA:

1. **`ReentrantLock`**
   * *What it is:* A mutual exclusion lock. Only one thread can hold the lock at a time.
   * *Why:* If the election timer thread tries to increment `currentTerm` while an incoming RPC thread is trying to vote, we get data races. Guarding with a lock ensures only one thread updates the state at a time.
2. **`ScheduledExecutorService`**
   * *What it is:* A scheduler pool. It lets us run a method periodically (e.g., leaders sending heartbeats every 50ms) or after a delay (e.g., election timeouts after 450ms).
3. **`CompletableFuture`**
   * *What it is:* A promise representing a value that will be completed in the future.
   * *Why:* When a client sends a `PUT` command, it calls `RaftNode.clientWrite()`. This doesn't commit immediately. The client thread registers a `CompletableFuture` and blocks. Once the leader receives acknowledgements from a majority of nodes, the apply thread completes the future, letting the client gRPC thread resume and return success.
4. **`ConcurrentHashMap`**
   * *What it is:* A thread-safe hash map.
   * *Why:* Used to store client key-values and manage pending write futures across threads safely.

---

## 5. How to Run It & Watch the Code Work

The best way to learn is to compile and run the project!

1. **Build the project** (compiles Java files and generates gRPC classes):
   ```bash
   .\.gradle-dist\gradle-8.5\bin\gradle.bat build
   ```
2. **Run the Core Unit Tests** to see how a mock cluster does elections:
   ```bash
   .\.gradle-dist\gradle-8.5\bin\gradle.bat :raft-core:test
   ```
3. **Run a 3-node cluster locally using Docker Compose**:
   ```bash
   docker-compose -f docker/docker-compose.yml up --build
   ```
4. **Run a GET or PUT command** via CLI client in another terminal:
   ```bash
   .\.gradle-dist\gradle-8.5\bin\gradle.bat :raft-client:run --args="1=localhost:8001,2=localhost:8002,3=localhost:8003 put mykey myval"
   ```
   *Watch the logs! You will see gRPC requests fly across the containers, replication acknowledgements converge, and state machine commits execute.*
